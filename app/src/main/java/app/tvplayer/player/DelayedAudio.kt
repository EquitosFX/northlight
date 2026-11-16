package app.tvplayer.player

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink

/** The audio delay the viewer has set, shared with the playback thread. */
class AudioDelay {
    /** Positive: the sound comes later; negative: earlier. */
    @Volatile var micros: Long = 0
}

/**
 * Reports the sound's position shifted by [delay]. The picture keeps time by the sound, so this
 * moves the picture against it: lip sync for slow TVs and soundbars. Works whichever decoder plays
 * the sound (the TV box's own, or the built-in one for Dolby Digital and the like).
 */
@UnstableApi
private class DelayedAudioSink(sink: AudioSink, private val delay: AudioDelay) : ForwardingAudioSink(sink) {
    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        val position = super.getCurrentPositionUs(sourceEnded)
        if (position == AudioSink.CURRENT_POSITION_NOT_SET) return position
        return (position + delay.micros).coerceAtLeast(0)
    }
}

/**
 * The player's decoders: the TV box's own first, then the built-in FFmpeg decoder for sound it
 * can't handle (common on Google TV for Dolby Digital and MPEG layer 2 sound), plus lip sync.
 */
@UnstableApi
class DelayedAudioRenderersFactory(context: Context, private val delay: AudioDelay) : DefaultRenderersFactory(context) {
    init {
        setExtensionRendererMode(EXTENSION_RENDERER_MODE_ON)
    }

    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink? =
        super.buildAudioSink(context, enableFloatOutput, enableAudioTrackPlaybackParams)?.let { DelayedAudioSink(it, delay) }
}
