package at.hochalex.assistent.ai

import android.content.Context
import android.net.Uri
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import java.io.File

/**
 * On-device-Modell (z.B. Gemma 3n als MediaPipe-.task-Datei) – läuft komplett auf dem Pixel,
 * nichts verlässt das Gerät. Wird für Klassifizierung/Zusammenfassung genutzt und für
 * Antwortentwürfe an „private“ Absender.
 */
class LocalLlm(private val context: Context) {
    val modelFile: File get() = File(context.filesDir, "models/model.task")

    @Volatile private var engine: LlmInference? = null

    fun available(): Boolean = modelFile.exists() && modelFile.length() > 1_000_000

    @Synchronized
    private fun engine(): LlmInference {
        engine?.let { return it }
        val options = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(modelFile.absolutePath)
            .setMaxTokens(4096)
            .build()
        return LlmInference.createFromOptions(context, options).also { engine = it }
    }

    /** Blockierend – nur aus Hintergrund-Threads aufrufen. */
    @Synchronized
    fun generate(prompt: String): String {
        val wrapped = "<start_of_turn>user\n$prompt<end_of_turn>\n<start_of_turn>model\n"
        return engine().generateResponse(wrapped).replace("<end_of_turn>", "").trim()
    }

    @Synchronized
    fun close() {
        runCatching { engine?.close() }
        engine = null
    }

    /** Kopiert eine vom Nutzer gewählte Modelldatei in den App-Speicher. */
    fun import(uri: Uri, onProgress: (Long) -> Unit = {}) {
        close()
        modelFile.parentFile?.mkdirs()
        val tmp = File(modelFile.parentFile, "model.tmp")
        context.contentResolver.openInputStream(uri)!!.use { input ->
            tmp.outputStream().use { out ->
                val buf = ByteArray(1 shl 20)
                var total = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    total += n
                    onProgress(total)
                }
            }
        }
        tmp.renameTo(modelFile)
    }

    fun delete() {
        close()
        modelFile.delete()
    }
}
