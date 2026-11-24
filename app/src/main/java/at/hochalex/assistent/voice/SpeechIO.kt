package at.hochalex.assistent.voice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

/** Spracherkennung – bevorzugt on-device (Deutsch). Nur vom Main-Thread verwenden. */
class SpeechInput(private val ctx: Context) {
    private var recognizer: SpeechRecognizer? = null
    private var onDevice = SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)

    private fun recognizer(): SpeechRecognizer {
        recognizer?.let { return it }
        val r = if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
        else SpeechRecognizer.createSpeechRecognizer(ctx, systemRecognizer())
        return r.also { recognizer = it }
    }

    /** Ein Erkennungsdienst, der nicht unser eigener Platzhalter ist (z.B. Google). */
    private fun systemRecognizer(): ComponentName? {
        val services = ctx.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
            .map { it.serviceInfo }
            .filter { it.packageName != ctx.packageName }
        val preferred = services.firstOrNull { it.packageName == "com.google.android.googlequicksearchbox" }
            ?: services.firstOrNull { it.packageName.startsWith("com.google") } ?: services.firstOrNull()
        return preferred?.let { ComponentName(it.packageName, it.name) }
    }

    private fun intent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "de-DE")
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
    }

    /** Hört einmal zu. Ergebnis null = nichts verstanden / Abbruch. */
    suspend fun listen(onPartial: (String) -> Unit = {}, onLevel: (Float) -> Unit = {}): String? {
        val result = listenOnce(onPartial, onLevel)
        if (result is ListenResult.LanguageUnavailable && onDevice) {
            // Sprachpaket fehlt: Download anstoßen und auf Netz-Erkenner ausweichen
            runCatching { recognizer?.triggerModelDownload(intent()) }
            destroy()
            onDevice = false
            return (listenOnce(onPartial, onLevel) as? ListenResult.Text)?.text
        }
        return (result as? ListenResult.Text)?.text
    }

    private sealed interface ListenResult {
        data class Text(val text: String) : ListenResult
        data object Nothing : ListenResult
        data object LanguageUnavailable : ListenResult
    }

    private suspend fun listenOnce(onPartial: (String) -> Unit, onLevel: (Float) -> Unit): ListenResult =
        suspendCancellableCoroutine { cont ->
            val r = recognizer()
            r.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) = onLevel(rmsdB)
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) {
                    if (!cont.isActive) return
                    val res = when (error) {
                        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> ListenResult.LanguageUnavailable
                        else -> ListenResult.Nothing
                    }
                    cont.resume(res)
                }
                override fun onResults(results: Bundle?) {
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (cont.isActive) cont.resume(if (text.isNullOrBlank()) ListenResult.Nothing else ListenResult.Text(text))
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(onPartial)
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            r.startListening(intent())
            cont.invokeOnCancellation { runCatching { r.cancel() } }
        }

    fun stop() = runCatching { recognizer?.cancel() }

    fun destroy() {
        runCatching { recognizer?.destroy() }
        recognizer = null
    }
}

/**
 * Sprachausgabe (Deutsch). Wählt die beste installierte deutsche Stimme – bevorzugt Googles
 * natürliche Online-Stimmen, ohne Netz automatisch die beste Offline-Stimme.
 */
class SpeechOutput(
    ctx: Context,
    private val voiceName: String = "",
    private val rate: Float = 1.0f,
    /** Paketname des Sprachausgabe-Moduls (z.B. SherpaTTS), leer = Systemstandard. */
    engine: String = "",
) {
    private val appCtx = ctx.applicationContext
    private var ready = false
    private var pendingInit: ((Boolean) -> Unit)? = null
    private val tts: TextToSpeech = TextToSpeech(appCtx, { status ->
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            tts.language = Locale.GERMANY
            applyVoice(voiceName)
            tts.setSpeechRate(rate)
        }
        pendingInit?.invoke(ready)
    }, engine.ifEmpty { null })

    /** Installierte Sprachausgabe-Module (Google, SherpaTTS, …). */
    suspend fun engines(): List<TextToSpeech.EngineInfo> = if (awaitReady()) tts.engines.orEmpty() else emptyList()

    private suspend fun awaitReady(): Boolean {
        if (ready) return true
        return suspendCancellableCoroutine { cont -> pendingInit = { if (cont.isActive) cont.resume(it) } }
    }

    /** Alle installierten deutschen Stimmen, beste zuerst. */
    suspend fun germanVoices(): List<Voice> {
        if (!awaitReady()) return emptyList()
        return rankVoices(tts.voices.orEmpty())
    }

    private fun rankVoices(all: Collection<Voice>): List<Voice> =
        all.filter { it.locale.language == "de" && TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features }
            .sortedWith(
                compareByDescending<Voice> { it.quality }
                    .thenByDescending { it.locale.country == "DE" || it.locale.country == "AT" }
                    .thenByDescending { it.isNetworkConnectionRequired } // Online-Stimmen klingen natürlicher
                    .thenBy { it.name }
            )

    private fun online(): Boolean {
        val cm = appCtx.getSystemService(ConnectivityManager::class.java) ?: return false
        return cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    }

    /** Gewählte Stimme setzen – ohne Netz wird auf die beste Offline-Stimme ausgewichen. */
    fun applyVoice(name: String) {
        val voices = rankVoices(tts.voices.orEmpty())
        val usable = if (online()) voices else voices.filterNot { it.isNetworkConnectionRequired }
        val chosen = usable.firstOrNull { it.name == name } ?: usable.firstOrNull()
        if (chosen != null) tts.voice = chosen
    }

    fun setRate(r: Float) { tts.setSpeechRate(r) }

        suspend fun speak(text: String) {
        if (text.isBlank() || !awaitReady()) return
        applyVoice(voiceName.ifEmpty { tts.voice?.name.orEmpty() })
        val id = UUID.randomUUID().toString()
        suspendCancellableCoroutine { cont ->
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { if (utteranceId == id && cont.isActive) cont.resume(Unit) }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { if (utteranceId == id && cont.isActive) cont.resume(Unit) }
                override fun onStop(utteranceId: String?, interrupted: Boolean) { if (utteranceId == id && cont.isActive) cont.resume(Unit) }
            })
            tts.speak(clean(text), TextToSpeech.QUEUE_FLUSH, null, id)
            cont.invokeOnCancellation { tts.stop() }
        }
    }

    fun stop() = tts.stop()
    fun shutdown() = tts.shutdown()

    /** Markdown-Reste und Aufzählungszeichen entfernen, damit es natürlich klingt. */
    private fun clean(t: String) = t.replace(Regex("[*_#`]"), "").replace("•", "").replace(Regex("\\[(.*?)]"), "$1")
}
