package at.hochalex.assistent.voice

import android.util.Log
import at.hochalex.assistent.AppContainer
import at.hochalex.assistent.ai.ClaudeClient
import at.hochalex.assistent.data.DraftEntity
import at.hochalex.assistent.data.DraftStatus
import at.hochalex.assistent.ha.HaClient
import at.hochalex.assistent.notify.ActionReceiver
import at.hochalex.assistent.work.Briefing
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.Tool
import com.anthropic.models.messages.ToolResultBlockParam
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Schnittstelle zur Sprach-Activity (Sperrstatus, Sende-Freigabe, direktes Vorlesen). */
interface AgentHost {
    val isLocked: Boolean
    suspend fun confirmSend(draft: DraftEntity): Boolean
    suspend fun speakDirect(text: String)
}

/**
 * Sprachagent: Claude entscheidet, welche Werkzeuge (Kalender, Todos, Mails, Entwürfe, Smart Home)
 * aufgerufen werden. Die Schleife läuft auf dem Handy (Messages-API mit Tool-Use).
 * Ohne Claude-Key oder Netz greift [LocalCommands] als Fallback.
 */
class VoiceAgent(private val c: AppContainer, private val host: AgentHost) {
    private val history = mutableListOf<MessageParam>()
    private val local = LocalCommands(c)

    suspend fun handle(utterance: String): String {
        val claude = c.claude() ?: return local.handle(utterance, reason = null)
        val checkpoint = history.size
        history += MessageParam.ofUser(utterance)
        try {
            return runLoop(claude)
        } catch (e: Exception) {
            Log.w(TAG, "Claude-Agent fehlgeschlagen (mit Verlauf: ${checkpoint > 0})", e)
            recordError(e)
            rollback(checkpoint)
            if (checkpoint > 0) {
                // Folgefrage gescheitert: einmal ohne bisherigen Verlauf neu versuchen
                history.clear()
                history += MessageParam.ofUser(utterance)
                try {
                    return runLoop(claude)
                } catch (e2: Exception) {
                    Log.w(TAG, "Claude-Agent auch ohne Verlauf fehlgeschlagen", e2)
                    recordError(e2)
                    history.clear()
                }
            }
        }
        return local.handle(utterance, reason = lastError)
    }

    /** Letzter Fehler der Claude-Anfrage (kurz, zum Anzeigen/Vorlesen). */
    var lastError: String? = null
        private set

    private fun recordError(e: Exception) {
        val msg = when (e) {
            is AnthropicServiceException -> "HTTP ${e.statusCode()}: ${e.message?.take(300)}"
            else -> "${e.javaClass.simpleName}: ${e.message?.take(300)}"
        }
        lastError = msg
        c.settings.putString("lastClaudeError", "${java.time.LocalDateTime.now().withNano(0)} – $msg")
    }

    private fun rollback(size: Int) {
        while (history.size > size) history.removeAt(history.size - 1)
    }

    private suspend fun runLoop(claude: ClaudeClient): String {
        repeat(MAX_STEPS) {
            val params = claude.params(16000, OutputConfig.Effort.LOW)
                .system(systemPrompt())
                .messages(history)
                .apply { TOOLS.forEach { addTool(it) } }
                .build()
            val msg = claude.create(params)
            history += msg.toParam()
            val uses = msg.content().mapNotNull { it.toolUse().orElse(null) }
            if (uses.isEmpty() || msg.stopReason().orElse(null) != StopReason.TOOL_USE) {
                return ClaudeClient.textOf(msg).ifBlank { "Erledigt." }
            }
            val results = uses.map { tu ->
                val input = runCatching { Json.parseToJsonElement(ClaudeClient.jsonToString(tu._input())).jsonObject }.getOrDefault(JsonObject(emptyMap()))
                val (text, isError) = try {
                    execute(tu.name(), input) to false
                } catch (e: Exception) {
                    "Fehler: ${e.message ?: e.javaClass.simpleName}" to true
                }
                ContentBlockParam.ofToolResult(
                    ToolResultBlockParam.builder().toolUseId(tu.id()).content(text).isError(isError).build()
                )
            }
            history += MessageParam.ofUserOfContentBlockParams(results)
        }
        return "Das war mir zu komplex – bitte formuliere es einfacher."
    }

    private fun systemPrompt(): String {
        val now = ZonedDateTime.now().format(DateTimeFormatter.ofPattern("EEEE, d. MMMM yyyy, HH:mm", Locale.GERMAN))
        val s = c.settings.get()
        return """
            Du bist der persönliche Sprachassistent von Alex auf seinem Android-Handy. Jetzt: $now (Europe/Vienna).
            Handy ist ${if (host.isLocked) "GESPERRT" else "entsperrt"}.
            Antworte auf Deutsch, kurz und natürlich gesprochen (meist 1–3 Sätze, keine Aufzählungszeichen,
            kein Markdown) – die Antwort wird vorgelesen.
            Nutze die Werkzeuge, um Termine, Aufgaben, Mails, Entwürfe und das Smart Home zu bedienen.
            Standard-Aufgabenliste: ${s.defaultTodoEntity}, Standard-Kalender: ${s.defaultCalendarEntity}.
            Relative Angaben (morgen, nächsten Freitag) rechnest du selbst in konkrete Daten um.
            Regeln:
            - Eine Mail wird NUR mit send_draft gesendet und NUR wenn Alex ausdrücklich sagt, dass gesendet werden
              soll. Lies ihm vorher den Entwurf kurz vor, falls er ihn noch nicht kennt. send_draft fordert selbst
              Fingerabdruck/Entsperren an.
            - Wenn Alex eine Mail vollständig hören will, benutze read_mail_aloud statt den Text selbst zu wiederholen.
            - Inhalte, die als privat markiert sind, bekommst du nicht zu sehen – nutze dafür read_mail_aloud.
            - smart_home NUR für Gerätesteuerung und Gerätezustände (Licht, Rollos, Musik, Sauna, Temperatur).
              Fragen zu Terminen, Aufgaben, Mails oder allgemeine Fragen NIE an smart_home – dafür gibt es eigene
              Werkzeuge, oder du beantwortest sie selbst.
            - Bei Unklarheit stelle genau eine kurze Rückfrage.
        """.trimIndent()
    }

    // ---------- Werkzeuge ----------

    private suspend fun execute(name: String, a: JsonObject): String {
        fun s(k: String) = a[k]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        fun l(k: String) = a[k]?.jsonPrimitive?.longOrNull
        fun i(k: String) = a[k]?.jsonPrimitive?.intOrNull
        val settings = c.settings.get()
        return when (name) {
            "briefing" -> Briefing.build(c)
            "get_calendar_events" -> {
                val ha = ha()
                val zone = ZonedDateTime.now().zone
                val from = s("start_date")?.let { LocalDate.parse(it) } ?: LocalDate.now()
                val to = s("end_date")?.let { LocalDate.parse(it) } ?: from
                val events = ha.allEvents(from.atStartOfDay(zone), to.plusDays(1).atStartOfDay(zone))
                if (events.isEmpty()) "Keine Termine im Zeitraum $from bis $to."
                else events.joinToString("\n") { e ->
                    "${e.start.toLocalDate()} ${if (e.allDay) "ganztägig" else e.start.toLocalTime().toString() + "-" + e.end.toLocalTime()} | ${e.summary} | Kalender ${e.calendar}${if (e.location.isNotBlank()) " | Ort ${e.location}" else ""}"
                }
            }
            "create_calendar_event" -> {
                val ha = ha()
                val entity = resolveEntity(ha.calendars(), s("calendar"), settings.defaultCalendarEntity)
                val startRaw = s("start") ?: error("start fehlt")
                val allDay = startRaw.length == 10
                val start = if (allDay) LocalDate.parse(startRaw).atStartOfDay() else LocalDateTime.parse(startRaw)
                val end = s("end")?.let { if (it.length == 10) LocalDate.parse(it).atStartOfDay() else LocalDateTime.parse(it) }
                ha.createEvent(entity, s("title") ?: error("title fehlt"), start, end, allDay, location = s("location"))
                "Termin eingetragen in $entity."
            }
            "list_todos" -> {
                val ha = ha()
                val lists = ha.todoLists()
                val selected = s("list")?.let { q -> lists.filter { it.name.contains(q, true) || it.entityId.contains(q, true) } } ?: lists
                selected.map { l -> l to ha.todoItems(l) }.joinToString("\n\n") { (l, items) ->
                    "Liste ${l.name} (${l.entityId}): " + if (items.isEmpty()) "leer" else items.joinToString("; ") { it.summary + (it.due?.let { d -> " (fällig $d)" } ?: "") }
                }.ifBlank { "Keine Aufgabenlisten gefunden." }
            }
            "add_todo" -> {
                val ha = ha()
                val entity = resolveEntity(ha.todoLists(), s("list"), settings.defaultTodoEntity)
                val due = s("due")
                ha.addTodo(entity, s("title") ?: error("title fehlt"),
                    dueDate = due?.takeIf { it.length == 10 }?.let { LocalDate.parse(it) },
                    dueDateTime = due?.takeIf { it.length > 10 }?.let { LocalDateTime.parse(it) })
                "Aufgabe hinzugefügt zu $entity."
            }
            "complete_todo" -> {
                val ha = ha()
                val q = s("title") ?: error("title fehlt")
                val lists = ha.todoLists().let { all -> s("list")?.let { f -> all.filter { it.name.contains(f, true) || it.entityId.contains(f, true) } } ?: all }
                for (l in lists) {
                    val item = ha.todoItems(l).firstOrNull { it.summary.contains(q, true) || q.contains(it.summary, true) }
                    if (item != null) {
                        ha.completeTodo(l.entityId, item.summary)
                        return "Erledigt: ${item.summary} (${l.name})."
                    }
                }
                "Keine passende offene Aufgabe gefunden."
            }
            "list_mails" -> {
                val all = c.db.mails().recent(i("limit") ?: 15)
                val mails = if (s("filter") == "all") all else all.filter { it.important || it.needsReply }
                if (mails.isEmpty()) "Keine passenden Mails."
                else mails.joinToString("\n") { m ->
                    val draft = if (m.needsReply) " | Antwort nötig" else ""
                    "id=${m.id} | ${fmtTime(m.receivedAt)} | von ${m.senderLabel} | Betreff: ${m.subject} | ${if (m.isPrivate) "[privat]" else m.summary}$draft${if (m.important) " | WICHTIG" else ""}"
                }
            }
            "get_mail" -> {
                val m = c.db.mails().get(l("mail_id") ?: error("mail_id fehlt")) ?: error("Mail nicht gefunden")
                if (m.isPrivate) "Mail von ${m.senderLabel}, Betreff ${m.subject}. [Privat – Inhalt wird nicht an die Cloud gegeben. Nutze read_mail_aloud.]"
                else "Von: ${m.fromName} <${m.fromAddress}>\nBetreff: ${m.subject}\nEmpfangen: ${fmtTime(m.receivedAt)}\n\n${m.body.take(8000)}"
            }
            "read_mail_aloud" -> {
                val m = c.db.mails().get(l("mail_id") ?: error("mail_id fehlt")) ?: error("Mail nicht gefunden")
                host.speakDirect("Mail von ${m.senderLabel}. Betreff: ${m.subject}. ${m.body.take(2500)}")
                "Die Mail wurde vorgelesen."
            }
            "list_drafts" -> {
                val drafts = c.db.drafts().pending()
                if (drafts.isEmpty()) "Keine offenen Entwürfe."
                else drafts.joinToString("\n\n") { d ->
                    "draft_id=${d.id} | an ${d.toName.ifBlank { d.toAddress }} | Betreff: ${d.subject}${if (d.status == DraftStatus.FAILED) " | VERSAND FEHLGESCHLAGEN: ${d.error}" else ""}\n${d.body}"
                }
            }
            "create_reply_draft" -> {
                val m = c.db.mails().get(l("mail_id") ?: error("mail_id fehlt")) ?: error("Mail nicht gefunden")
                val d = c.drafts.createReply(m, s("instruction")) ?: error("Kein Entwurf möglich (Claude/lokales Modell nicht verfügbar).")
                "Entwurf erstellt, draft_id=${d.id}:\n${d.body}"
            }
            "compose_mail" -> {
                val recipient = s("recipient") ?: error("recipient fehlt")
                val (addr, nameOrCandidates) = resolveRecipient(recipient)
                if (addr == null) return nameOrCandidates
                val d = c.drafts.createNew(addr, nameOrCandidates, s("subject").orEmpty(), s("instruction") ?: error("instruction fehlt"))
                    ?: error("Kein Mailkonto oder keine KI verfügbar.")
                "Entwurf erstellt, draft_id=${d.id}, an $addr, Betreff ${d.subject}:\n${d.body}"
            }
            "revise_draft" -> {
                val d = c.drafts.revise(l("draft_id") ?: error("draft_id fehlt"), s("instruction") ?: error("instruction fehlt"))
                "Überarbeitet (draft_id=${d.id}):\n${d.body}"
            }
            "send_draft" -> {
                val d = c.db.drafts().get(l("draft_id") ?: error("draft_id fehlt")) ?: error("Entwurf nicht gefunden")
                if (!host.confirmSend(d)) return "Senden abgebrochen – keine Freigabe."
                c.drafts.send(d.id).fold({ "Gesendet an ${d.toName.ifBlank { d.toAddress }}." }, { "Senden fehlgeschlagen: ${it.message}" })
            }
            "discard_draft" -> {
                c.drafts.discard(l("draft_id") ?: error("draft_id fehlt")); "Entwurf verworfen."
            }
            "list_suggestions" -> {
                val sug = c.db.suggestions().pending()
                if (sug.isEmpty()) "Keine offenen Vorschläge."
                else sug.joinToString("\n") { "suggestion_id=${it.id} | ${it.type} | ${it.title} | ${it.dueDate ?: it.start ?: ""}" }
            }
            "accept_suggestion" -> ActionReceiver.acceptSuggestion(c, l("suggestion_id") ?: error("suggestion_id fehlt"))
            "reject_suggestion" -> {
                val sg = c.db.suggestions().get(l("suggestion_id") ?: error("suggestion_id fehlt")) ?: error("nicht gefunden")
                c.db.suggestions().update(sg.copy(status = "REJECTED")); "Vorschlag verworfen."
            }
            "smart_home" -> ha().conversation(s("command") ?: error("command fehlt"))
            else -> error("Unbekanntes Werkzeug $name")
        }
    }

    private fun ha(): HaClient = c.ha() ?: error("Home Assistant ist nicht eingerichtet.")

    private fun resolveEntity(all: List<at.hochalex.assistent.ha.HaEntity>, query: String?, default: String): String {
        if (query.isNullOrBlank()) return default
        if (query.contains('.')) return query
        return all.firstOrNull { it.name.equals(query, true) }?.entityId
            ?: all.firstOrNull { it.name.contains(query, true) || it.entityId.contains(query.lowercase()) }?.entityId
            ?: default
    }

    /** Liefert (Adresse, Name) oder (null, Hinweistext mit Kandidaten). */
    private suspend fun resolveRecipient(q: String): Pair<String?, String> {
        Regex("[\\w.+-]+@[\\w.-]+\\.[a-z]{2,}", RegexOption.IGNORE_CASE).find(q)?.let { return it.value to q.substringBefore('<').trim() }
        val hits = c.db.mails().findContacts(q.trim())
        return when (hits.size) {
            0 -> null to "Ich kenne keine Adresse für \"$q\". Bitte nenne die E-Mail-Adresse."
            1 -> {
                val h = hits.first()
                h.substringAfter('<').substringBefore('>') to h.substringBefore('<').trim()
            }
            else -> null to "Mehrere Treffer: ${hits.joinToString("; ")}. Frag nach, welcher gemeint ist."
        }
    }

    private fun fmtTime(ms: Long) = java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("dd.MM. HH:mm"))

    companion object {
        private const val TAG = "VoiceAgent"
        private const val MAX_STEPS = 8

        private fun tool(name: String, description: String, props: Map<String, Map<String, Any>> = emptyMap(), required: List<String> = emptyList()): Tool =
            Tool.builder()
                .name(name)
                .description(description)
                .inputSchema(
                    Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder().apply {
                            props.forEach { (k, v) -> putAdditionalProperty(k, JsonValue.from(v)) }
                        }.build())
                        .required(required)
                        .build()
                )
                .build()

        private fun str(desc: String) = mapOf("type" to "string", "description" to desc)
        private fun int(desc: String) = mapOf("type" to "integer", "description" to desc)

        val TOOLS: List<Tool> = listOf(
            tool("briefing", "Tagesüberblick: heutige Termine, fällige Aufgaben, wichtige Mails, offene Entwürfe."),
            tool("get_calendar_events", "Termine aus allen Kalendern im Zeitraum.",
                mapOf("start_date" to str("YYYY-MM-DD, Standard heute"), "end_date" to str("YYYY-MM-DD inklusive, Standard = start_date"))),
            tool("create_calendar_event", "Neuen Termin anlegen.",
                mapOf("title" to str("Titel"), "start" to str("YYYY-MM-DDTHH:MM, oder YYYY-MM-DD für ganztägig"),
                    "end" to str("Ende im selben Format, optional (Standard 1 Stunde)"), "calendar" to str("Kalendername, optional"),
                    "location" to str("Ort, optional")), listOf("title", "start")),
            tool("list_todos", "Offene Aufgaben anzeigen (alle Listen oder eine bestimmte, z.B. Einkaufsliste).",
                mapOf("list" to str("Listenname, optional"))),
            tool("add_todo", "Aufgabe oder Einkaufsartikel hinzufügen.",
                mapOf("title" to str("Text der Aufgabe"), "list" to str("Listenname, optional (z.B. Einkaufsliste)"),
                    "due" to str("Fälligkeit YYYY-MM-DD oder YYYY-MM-DDTHH:MM, optional")), listOf("title")),
            tool("complete_todo", "Aufgabe als erledigt markieren.",
                mapOf("title" to str("Text bzw. Teil des Textes der Aufgabe"), "list" to str("Listenname, optional")), listOf("title")),
            tool("list_mails", "Zuletzt empfangene Mails mit Kurzfassung (id, Absender, Betreff).",
                mapOf("filter" to mapOf("type" to "string", "enum" to listOf("relevant", "all"), "description" to "relevant = wichtig oder Antwort nötig"),
                    "limit" to int("Anzahl, Standard 15"))),
            tool("get_mail", "Vollständigen Inhalt einer Mail abrufen (zum Zusammenfassen oder Beantworten).",
                mapOf("mail_id" to int("id aus list_mails")), listOf("mail_id")),
            tool("read_mail_aloud", "Mail direkt wörtlich vorlesen (Inhalt geht nicht an die Cloud).",
                mapOf("mail_id" to int("id aus list_mails")), listOf("mail_id")),
            tool("list_drafts", "Offene Antwortentwürfe mit Text anzeigen."),
            tool("create_reply_draft", "Antwortentwurf zu einer Mail erstellen.",
                mapOf("mail_id" to int("id aus list_mails"), "instruction" to str("Was inhaltlich geantwortet werden soll, optional")), listOf("mail_id")),
            tool("compose_mail", "Neue Mail (keine Antwort) als Entwurf formulieren.",
                mapOf("recipient" to str("Name oder E-Mail-Adresse"), "subject" to str("Betreff, optional"),
                    "instruction" to str("Was in der Mail stehen soll")), listOf("recipient", "instruction")),
            tool("revise_draft", "Entwurf überarbeiten (z.B. kürzer, förmlicher, für Freitag zusagen).",
                mapOf("draft_id" to int("draft_id"), "instruction" to str("Änderungswunsch")), listOf("draft_id", "instruction")),
            tool("send_draft", "Entwurf senden. Nur auf ausdrücklichen Wunsch. Fordert Fingerabdruck/Entsperren an.",
                mapOf("draft_id" to int("draft_id")), listOf("draft_id")),
            tool("discard_draft", "Entwurf verwerfen.", mapOf("draft_id" to int("draft_id")), listOf("draft_id")),
            tool("list_suggestions", "Aus Mails erkannte Vorschläge für Aufgaben/Termine anzeigen."),
            tool("accept_suggestion", "Vorschlag übernehmen (Aufgabe/Termin anlegen).",
                mapOf("suggestion_id" to int("suggestion_id")), listOf("suggestion_id")),
            tool("reject_suggestion", "Vorschlag verwerfen.", mapOf("suggestion_id" to int("suggestion_id")), listOf("suggestion_id")),
            tool("smart_home", "Nur Gerätesteuerung über Home Assistant (Licht, Rollos, Musik, Sauna, Gerätezustand). Nicht für Termine, Aufgaben, Mails oder allgemeine Fragen.",
                mapOf("command" to str("Befehl in natürlicher Sprache, z.B. 'Rollos schließen'")), listOf("command")),
        )
    }
}

/** Einfache Befehle ohne Cloud – Fallback ohne Claude-Key oder ohne Netz. */
class LocalCommands(private val c: AppContainer) {
    /** [reason] = Fehlertext, falls Claude gerade nicht erreichbar war (null = Claude nicht eingerichtet). */
    suspend fun handle(text: String, reason: String?): String {
        val t = text.lowercase(Locale.GERMAN).trim()
        val ha = c.ha()
        return try {
            when {
                Regex("briefing|mein tag|überblick|was steht (heute )?an").containsMatchIn(t) -> Briefing.build(c)
                Regex("termin").containsMatchIn(t) && ha != null -> {
                    val day = if ("morgen" in t) LocalDate.now().plusDays(1) else LocalDate.now()
                    val zone = ZonedDateTime.now().zone
                    val ev = ha.allEvents(day.atStartOfDay(zone), day.plusDays(1).atStartOfDay(zone))
                    if (ev.isEmpty()) "Keine Termine ${if ("morgen" in t) "morgen" else "heute"}."
                    else ev.joinToString(". ") { (if (it.allDay) "" else "${it.start.toLocalTime()} ") + it.summary }
                }
                ha != null && Regex("^(erinnere mich (daran )?|neue aufgabe|aufgabe|notiere|schreib(e)? auf)").containsMatchIn(t) -> {
                    val item = text.replace(Regex("(?i)^(erinnere mich (daran,? )?|neue aufgabe:?|aufgabe:?|notiere:?|schreib(e)? auf:?)\\s*"), "").trim()
                    ha.addTodo(c.settings.get().defaultTodoEntity, item.replaceFirstChar { it.uppercase() })
                    "Notiert: $item."
                }
                ha != null && Regex("(.+) (auf|zur|in die) (die )?(einkaufsliste)").containsMatchIn(t) -> {
                    val item = Regex("(?i)^(setz(e)?|schreib(e)?|füg(e)?)?\\s*(.+?) (auf|zur|in die) (die )?einkaufsliste.*").find(text)?.groupValues?.get(5) ?: text
                    val list = ha.todoLists().firstOrNull { it.name.contains("einkauf", true) }?.entityId ?: "todo.einkaufsliste"
                    ha.addTodo(list, item.trim().replaceFirstChar { it.uppercase() })
                    "${item.trim()} steht auf der Einkaufsliste."
                }
                Regex("entw(u|ü)rf").containsMatchIn(t) -> {
                    val n = c.db.drafts().pending().size
                    if (n == 0) "Keine offenen Entwürfe." else "$n Entwürfe warten auf Freigabe. Öffne die App zum Prüfen."
                }
                ha != null && SMART_HOME.containsMatchIn(t) -> ha.conversation(text)
                reason != null -> "Claude ist gerade nicht erreichbar, daher kann ich das nicht beantworten. Fehler: $reason"
                else -> "Dafür brauche ich den Claude-Schlüssel. Bitte trage ihn in der App unter Einstellungen ein."
            }
        } catch (e: Exception) {
            "Das hat nicht geklappt: ${e.message ?: "unbekannter Fehler"}"
        }
    }
    companion object {
        private val SMART_HOME = Regex(
            "licht|lampe|leuchte|rollo|raffstore|jalousie|musik|sonos|lautsprecher|sauna|fernseh|heizung|temperatur|" +
                "schalt|einschalten|ausschalten|dimm|szene|steckdose|kaffeemaschine|rasenmäher|waschmaschine|\\btv\\b"
        )
    }
}
