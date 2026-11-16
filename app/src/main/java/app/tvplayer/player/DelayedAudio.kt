package app.tvplayer.player

import android.content.Context
import android.os.Handler
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector

/** The audio delay the viewer has set, shared with the playback thread. */
class AudioDelay {
    /** Positive: the sound comes later; negative: earlier. */
    @Volatile var micros: Long = 0
}

/**
 * The normal audio renderer, but reporting its position shifted by [delay]. The picture keeps time
 * by the audio, so this moves the picture against the sound: lip sync for slow TVs and soundbars.
 */
@UnstableApi
private class DelayedAudioRenderer(
    context: Context,
    selector: MediaCodecSelector,
    fallback: Boolean,
    handler: Handler?,
    listener: AudioRendererEventListener?,
    sink: AudioSink,
    private val delay: AudioDelay,
) : MediaCodecAudioRenderer(context, selector, fallback, handler, listener, sink) {
    override fun getPositionUs(): Long {
        val position = super.getPositionUs()
        return (position + delay.micros).coerceAtLeast(0)
    }
}

@UnstableApi
class DelayedAudioRenderersFactory(context: Context, private val delay: AudioDelay) : DefaultRenderersFactory(context) {
    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>,
    ) {
        val start = out.size
        super.buildAudioRenderers(context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback, audioSink, eventHandler, eventListener, out)
        for (i in start until out.size) {
            if (out[i].javaClass == MediaCodecAudioRenderer::class.java) {
                out[i] = DelayedAudioRenderer(context, mediaCodecSelector, enableDecoderFallback, eventHandler, eventListener, audioSink, delay)
            }
        }
    }
}
