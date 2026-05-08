/*
 * Copyright (c) 2022 Auxio Project
 * ReplayGainAudioProcessor.kt is part of Auxio.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
 
package org.oxycblt.auxio.playback.replaygain

import android.os.Build
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlin.math.pow
import org.oxycblt.auxio.playback.PlaybackSettings
import org.oxycblt.auxio.playback.state.PlaybackStateManager
import org.oxycblt.auxio.playback.state.QueueChange
import org.oxycblt.musikr.Album
import org.oxycblt.musikr.Music
import org.oxycblt.musikr.MusicParent
import org.oxycblt.musikr.Song
import timber.log.Timber as L

/**
 * An [AudioProcessor] that handles ReplayGain values and their amplification of the audio stream.
 * Instead of leveraging the volume attribute like other implementations, this system manipulates
 * the bitstream itself to modify the volume, which allows the use of positive ReplayGain values.
 *
 * Note: This audio processor must be attached to a respective [Player] instance as a
 * [Player.Listener] to function properly.
 *
 * @author Alexander Capehart (OxygenCobalt)
 */
class ReplayGainAudioProcessor
@Inject
constructor(
    private val playbackManager: PlaybackStateManager,
    private val playbackSettings: PlaybackSettings,
) : BaseAudioProcessor(), PlaybackStateManager.Listener, PlaybackSettings.Listener {
    private var volume = 1f
        set(value) {
            field = value
            // Processed bytes are no longer valid, flush the stream
            flush()
        }

    @Volatile
    private var bypass = false

    /**
     * A cache that remembers whether a track was detected to contain AAC embedded loudness.
     * When a cached track starts again (e.g. via gapless), bypass is applied immediately.
     * Only used on Android 15+.
     */
    private val loudnessCache = ConcurrentHashMap<Music.UID, Boolean>()

    /** Whether the loudness detection feature is available on this device. */
    private val loudnessDetectionEnabled = Build.VERSION.SDK_INT >= 35

    /**
     * Enable or disable bypass mode. When bypass is active, the processor copies
     * the input buffer unchanged (no gain adjustment, no pre‑amp).
     * This is used for AAC tracks that have integrated loudness metadata.
     */
    fun setBypassGain(bypass: Boolean) {
        if (this.bypass != bypass) {
            this.bypass = bypass
            if (bypass) {
                volume = 1f
            }
        }
    }

    /**
     * Stores the loudness detection result for a track and immediately activates bypass
     * if the track is currently playing. Called by the audio renderer when the decoder
     * reports [android.media.MediaFormat.KEY_AAC_DRC_OUTPUT_LOUDNESS] with a non‑negative value.
     */
    fun reportLoudnessDetected(songUid: Music.UID, hasLoudness: Boolean) {
        if (!loudnessDetectionEnabled) return
        loudnessCache[songUid] = hasLoudness
        if (playbackManager.currentSong?.uid == songUid && hasLoudness) {
            L.d("Loudness detected for current track, enabling bypass")
            setBypassGain(true)
        }
    }

    fun attach() {
        playbackManager.addListener(this)
        playbackSettings.registerListener(this)
    }

    /** Remove this instance from the components required for it to function correctly. */
    fun release() {
        playbackManager.removeListener(this)
        playbackSettings.unregisterListener(this)
    }

    // --- OVERRIDES ---

    override fun onIndexMoved(index: Int) {
        L.d("Index moved, updating current song")
        bypass = false
        applyReplayGain(playbackManager.currentSong)
    }

    override fun onQueueChanged(queue: List<Song>, index: Int, change: QueueChange) {
        // Other types of queue changes preserve the current song.
        if (change.type == QueueChange.Type.SONG) {
            L.d("Song changed, resetting bypass and checking cache")
            bypass = false
            applyReplayGain(playbackManager.currentSong)
        }
    }

    override fun onNewPlayback(
        parent: MusicParent?,
        queue: List<Song>,
        index: Int,
        isShuffled: Boolean,
    ) {
        L.d("New playback started, updating playback information")
        bypass = false
        applyReplayGain(playbackManager.currentSong)
    }

    override fun onReplayGainSettingsChanged() {
        // ReplayGain config changed, we need to set it up again.
        applyReplayGain(playbackManager.currentSong)
    }

    // --- REPLAYGAIN PARSING ---

    /**
     * Updates the volume adjustment based on the given [Format].
     *
     * @param song The [Format] of the currently playing track, or null if nothing is playing.
     */
    private fun applyReplayGain(song: Song?) {
        if (song == null) {
            L.d("Nothing playing, disabling adjustment")
            volume = 1f
            return
        }

        // Only use loudness cache if the feature is enabled (Android 15+)
        if (loudnessDetectionEnabled && (bypass || loudnessCache[song.uid] == true)) {
            setBypassGain(true)
            L.d("Bypass active (cached=${loudnessCache[song.uid]}), volume=1.0")
            return
        }

        L.d("Applying ReplayGain adjustment for $song")

        val gain = song.replayGainAdjustment
        val preAmp = playbackSettings.replayGainPreAmp

        // ReplayGain is configurable, so determine what to do based off of the mode.
        val resolvedAdjustment =
            when (playbackSettings.replayGainMode) {
                // User wants no adjustment.
                ReplayGainMode.OFF -> {
                    L.d("ReplayGain is off")
                    null
                }
                // User wants track gain to be preferred. Default to album gain only if
                // there is no track gain.
                ReplayGainMode.TRACK -> {
                    L.d("Using track strategy")
                    gain.track ?: gain.album
                }
                // User wants album gain to be preferred. Default to track gain only if
                // here is no album gain.
                ReplayGainMode.ALBUM -> {
                    L.d("Using album strategy")
                    gain.album ?: gain.track
                }
                // User wants album gain to be used when in an album, track gain otherwise.
                ReplayGainMode.DYNAMIC -> {
                    L.d("Using dynamic strategy")
                    gain.album?.takeIf {
                        playbackManager.parent is Album &&
                            playbackManager.currentSong?.album == playbackManager.parent
                    } ?: gain.track
                }
            }

        val amplifiedAdjustment =
            if (resolvedAdjustment != null) {
                // Successfully resolved an adjustment, apply the corresponding pre-amp
                L.d("Applying with pre-amp")
                resolvedAdjustment + preAmp.with
            } else {
                // No adjustment found, use the corresponding user-defined pre-amp
                L.d("Applying without pre-amp")
                preAmp.without
            }

        L.d("Applying ReplayGain adjustment ${amplifiedAdjustment}db")

        // Final adjustment along the volume curve.
        volume = 10f.pow(amplifiedAdjustment / 20f)
    }

    // --- AUDIO PROCESSOR IMPLEMENTATION ---

    override fun onConfigure(
        inputAudioFormat: AudioProcessor.AudioFormat
    ): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) {
            // AudioProcessor is only provided 16-bit PCM audio data, so that's the only
            // encoding we need to check for.
            // TODO: Convert to a low-level audio processor capable of handling any kind of
            //  PCM data, once ExoPlayer can support it.
            return inputAudioFormat
        }

        throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val pos = inputBuffer.position()
        val limit = inputBuffer.limit()
        val buffer = replaceOutputBuffer(limit - pos)

        if (bypass || volume == 1f) {
            // Nothing to adjust, just copy the audio data.
            // isActive is technically a much better way of doing a no-op like this, but since
            // the adjustment can change during playback I'm largely forced to do this.
            buffer.put(inputBuffer.slice())
        } else {
            for (i in pos until limit step 2) {
                // 16-bit PCM audio, deserialize a little-endian short.
                var sample = inputBuffer.getLeShort(i)
                // Ensure we clamp the values to the minimum and maximum values possible
                // for the encoding. This prevents issues where samples amplified beyond
                // 1 << 16 will end up becoming truncated during the conversion to a short,
                // resulting in popping.
                sample =
                    (sample * volume)
                        .toInt()
                        .coerceAtLeast(Short.MIN_VALUE.toInt())
                        .coerceAtMost(Short.MAX_VALUE.toInt())
                        .toShort()
                buffer.putLeShort(sample)
            }
        }

        inputBuffer.position(limit)
        buffer.flip()
    }

    /**
     * Always read a little-endian [Short] from the [ByteBuffer] at the given index.
     *
     * @param at The index to read the [Short] from.
     */
    private fun ByteBuffer.getLeShort(at: Int) =
        get(at + 1).toInt().shl(8).or(get(at).toInt().and(0xFF)).toShort()

    /**
     * Always write a little-endian [Short] at the end of the [ByteBuffer].
     *
     * @param short The [Short] to write.
     */
    private fun ByteBuffer.putLeShort(short: Short) {
        put(short.toByte())
        put(short.toInt().shr(8).toByte())
    }
}

