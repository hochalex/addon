package at.hochalex.assistent.ai

import at.hochalex.assistent.data.AppSettings
import at.hochalex.assistent.data.MailCategory
import at.hochalex.assistent.data.MailEntity
import com.anthropic.models.messages.OutputConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import android.util.Log
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

data class TodoHint(val title: String, val dueDate: String?)
data class EventHint(val title: String, val start: String, val end: String?)

data class MailAnalysis(
    val category: String,
    val important: Boolean,
    val needsReply: Boolean,
    val summary: String,
    val todos: List<TodoHint> = emptyList(),
    val events: List<EventHint> = emptyList(),
    val engine: String,
)

/**
 * Hybrid-Logik:
 *  - Analyse (Kategorie, Wichtigkeit, Zusammenfassung, Todos/Termine): lokal, falls Modell vorhanden,
 *    sonst Claude (außer bei privaten Absendern → einfache Heuristik).
 *  - Antwortentwürfe: Claude; private Absender ausschließlich lokal.
 */
class AiService(
    private val settings: () -> AppSettings,
    private val claude: () -> ClaudeClient?,
    private val local: LocalLlm,
) {
    private val lenient = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun now(): String = ZonedDateTime.now().format(DateTimeFormatter.ofPattern("EEEE, d. MMMM yyyy, HH:mm", Locale.GERMAN))

    suspend fun analyze(mail: MailEntity, isBulk: Boolean): MailAnalysis {
        if (isBulk) {
            return MailAnalysis(MailCategory.NEWSLETTER, false, false, mail.subject, engine = "regel")
        }
        val prompt = Prompts.analyzeUser(mail, now())
        if (local.available()) {
            runCatching {
                val raw = withContext(Dispatchers.Default) { local.generate(Prompts.ANALYZE_SYSTEM + "\n\n" + prompt + "\n\n" + Prompts.ANALYZE_JSON_HINT) }
                return parseAnalysis(extractJson(raw), "lokal")
            }.onFailure { Log.w(TAG, "Lokale Analyse fehlgeschlagen", it) }
        }
        val c = claude()
        if (c != null && !mail.isPrivate) {
            val obj = c.json(Prompts.ANALYZE_SYSTEM, prompt, Prompts.ANALYZE_SCHEMA, effort = OutputConfig.Effort.LOW)
            return parseAnalysis(obj, "claude")
        }
        return heuristic(mail)
    }

    /** Erstellt einen Antwortentwurf. Gibt null zurück, wenn kein erlaubter Weg verfügbar ist. */
    suspend fun draftReply(mail: MailEntity, extraInstruction: String? = null): Pair<String, Boolean>? {
        val s = settings()
        val user = Prompts.draftUser(mail, s.styleProfile, extraInstruction, now())
        if (mail.isPrivate) {
            if (!local.available()) return null
            val text = withContext(Dispatchers.Default) { local.generate(Prompts.DRAFT_SYSTEM + "\n\n" + user) }
            return cleanDraft(text) to true
        }
        val c = claude() ?: return if (local.available()) {
            cleanDraft(withContext(Dispatchers.Default) { local.generate(Prompts.DRAFT_SYSTEM + "\n\n" + user) }) to true
        } else null
        return cleanDraft(c.text(Prompts.DRAFT_SYSTEM, user, 8000, OutputConfig.Effort.MEDIUM)) to false
    }

    /** Neue Mail (keine Antwort) aus einer Anweisung formulieren. */
    suspend fun composeNew(recipient: String, subjectHint: String, instruction: String, private: Boolean): Pair<String, String>? {
        val s = settings()
        val user = Prompts.composeUser(recipient, subjectHint, instruction, s.styleProfile, now())
        val raw = if (private || claude() == null) {
            if (!local.available()) return null
            withContext(Dispatchers.Default) { local.generate(Prompts.COMPOSE_SYSTEM + "\n\n" + user) }
        } else claude()!!.text(Prompts.COMPOSE_SYSTEM, user, 8000, OutputConfig.Effort.MEDIUM)
        val lines = raw.trim().lines()
        val subj = lines.firstOrNull { it.startsWith("Betreff:", true) }?.substringAfter(":")?.trim()
        val body = lines.dropWhile { !it.startsWith("Betreff:", true) }.drop(1).joinToString("\n").trim()
        return (subj ?: subjectHint.ifBlank { "Nachricht" }) to cleanDraft(body.ifBlank { raw })
    }

    suspend fun reviseDraft(original: String, instruction: String, mail: MailEntity?, private: Boolean): String {
        val user = Prompts.reviseUser(original, instruction, mail)
        return if (private || claude() == null) {
            check(local.available()) { "Kein lokales Modell für private Entwürfe vorhanden." }
            cleanDraft(withContext(Dispatchers.Default) { local.generate(Prompts.REVISE_SYSTEM + "\n\n" + user) })
        } else cleanDraft(claude()!!.text(Prompts.REVISE_SYSTEM, user, 8000, OutputConfig.Effort.MEDIUM))
    }

    /** Analysiert gesendete Mails und erstellt ein Stilprofil (nur nicht-private Empfänger). */
    suspend fun learnStyle(samples: List<String>): String? {
        val c = claude() ?: return null
        if (samples.isEmpty()) return null
        return c.text(Prompts.STYLE_SYSTEM, Prompts.styleUser(samples), 4000, OutputConfig.Effort.MEDIUM)
    }

    private fun cleanDraft(t: String) = t.trim().removePrefix("```").removeSuffix("```").trim()

    private fun parseAnalysis(o: JsonObject, engine: String): MailAnalysis {
        fun s(k: String) = o[k]?.jsonPrimitive?.contentOrNull.orEmpty()
        fun b(k: String) = o[k]?.jsonPrimitive?.booleanOrNull ?: false
        val cat = s("category").uppercase().let { if (it in setOf(MailCategory.PERSONAL, MailCategory.INFO, MailCategory.NEWSLETTER)) it else MailCategory.INFO }
        val todos = runCatching {
            o["todos"]!!.jsonArray.map { it.jsonObject }.map {
                TodoHint(it["title"]!!.jsonPrimitive.content, it["due_date"]?.jsonPrimitive?.contentOrNull?.takeIf { d -> d.length == 10 })
            }
        }.getOrDefault(emptyList())
        val events = runCatching {
            o["events"]!!.jsonArray.map { it.jsonObject }.mapNotNull {
                val start = it["start"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                EventHint(it["title"]!!.jsonPrimitive.content, start, it["end"]?.jsonPrimitive?.contentOrNull?.ifBlank { null })
            }
        }.getOrDefault(emptyList())
        return MailAnalysis(cat, b("important"), b("needs_reply") && cat == MailCategory.PERSONAL, s("summary"), todos, events, engine)
    }

    private fun extractJson(raw: String): JsonObject {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        require(start >= 0 && end > start) { "Kein JSON in lokaler Antwort" }
        return lenient.parseToJsonElement(raw.substring(start, end + 1)).jsonObject
    }

    private fun heuristic(mail: MailEntity): MailAnalysis {
        val text = (mail.subject + " " + mail.body.take(2000)).lowercase()
        val question = mail.body.take(3000).contains("?")
        val urgent = listOf("dringend", "wichtig", "frist", "bis morgen", "asap", "mahnung").any { it in text }
        return MailAnalysis(
            category = MailCategory.PERSONAL,
            important = urgent,
            needsReply = question,
            summary = mail.subject,
            engine = "heuristik",
        )
    }

    companion object {
        private const val TAG = "AiService"
    }
}
