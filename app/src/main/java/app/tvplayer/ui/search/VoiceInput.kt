package app.tvplayer.ui.search

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * Listens through the remote's microphone inside the app, so the words land in the search box rather
 * than in Android's own search. Words appear as they're heard; [onFinal] gets the finished phrase.
 */
@Stable
class VoiceInput(private val context: Context, private val onWords: (String) -> Unit) {
    var listening by mutableStateOf(false)
        private set
    var problem by mutableStateOf<String?>(null)
        internal set

    private var recognizer: SpeechRecognizer? = null

    val available: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun start() {
        problem = null
        if (!available) {
            problem = "Voice search isn't available on this device."
            return
        }
        stop()
        val r = runCatching { SpeechRecognizer.createSpeechRecognizer(context) }.getOrNull()
        if (r == null) {
            problem = "Voice search isn't available on this device."
            return
        }
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { listening = true }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { listening = false }
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onPartialResults(partialResults: Bundle?) {
                words(partialResults)?.let(onWords)
            }
            override fun onResults(results: Bundle?) {
                listening = false
                words(results)?.let(onWords)
            }
            override fun onError(error: Int) {
                listening = false
                problem = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Didn't catch that. Press the microphone and try again."
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Skyfall needs permission to use the microphone."
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Voice search needs an internet connection."
                    else -> "Voice search didn't work. Try again."
                }
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        listening = true
        runCatching { r.startListening(intent) }.onFailure {
            listening = false
            problem = "Voice search didn't work. Try again."
        }
    }

    fun stop() {
        recognizer?.let {
            runCatching { it.cancel() }
            runCatching { it.destroy() }
        }
        recognizer = null
        listening = false
    }

    private fun words(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }
}

@Composable
fun rememberVoiceInput(onWords: (String) -> Unit): VoiceInput {
    val context = LocalContext.current
    val voice = remember { VoiceInput(context, onWords) }
    DisposableEffect(voice) { onDispose { voice.stop() } }
    return voice
}

/** A drawn microphone, matching the player's drawn icons. */
@Composable
fun MicIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.1f
        // Capsule
        val capW = w * 0.38f
        val capH = h * 0.56f
        drawRoundRect(
            color = tint,
            topLeft = Offset((w - capW) / 2, h * 0.04f),
            size = Size(capW, capH),
            cornerRadius = CornerRadius(capW / 2, capW / 2),
        )
        // Cradle
        val cradleW = w * 0.62f
        drawArc(
            color = tint,
            startAngle = 0f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset((w - cradleW) / 2, h * 0.22f),
            size = Size(cradleW, h * 0.5f),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
        // Stem and base
        drawLine(tint, Offset(w / 2, h * 0.72f), Offset(w / 2, h * 0.9f), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(tint, Offset(w * 0.32f, h * 0.92f), Offset(w * 0.68f, h * 0.92f), strokeWidth = stroke, cap = StrokeCap.Round)
    }
}
