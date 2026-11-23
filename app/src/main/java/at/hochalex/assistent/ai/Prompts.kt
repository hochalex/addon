package at.hochalex.assistent.ai

import at.hochalex.assistent.data.MailEntity

object Prompts {

    val ANALYZE_SYSTEM = """
        Du bist der persönliche Mail-Assistent von Alex (Österreich, Zeitzone Europe/Vienna).
        Analysiere die eingehende E-Mail und gib eine strukturierte Einschätzung zurück.
        - category: PERSONAL (von einem Menschen direkt an Alex geschrieben), INFO (automatische Benachrichtigung,
          Bestätigung, Rechnung, Versandinfo) oder NEWSLETTER (Newsletter, Werbung, Marketing).
        - important: true nur wenn zeitkritisch, finanziell/rechtlich relevant oder von einer Person, die eine
          Reaktion erwartet. Im Zweifel false.
        - needs_reply: true nur bei PERSONAL-Mails, auf die Alex persönlich antworten sollte (Frage, Bitte,
          Einladung, Terminabstimmung). Nicht bei reinen Infos oder "Danke"-Mails.
        - summary: 1–2 kurze Sätze auf Deutsch, so formuliert, dass man sie vorlesen kann.
        - todos: konkrete Aufgaben für Alex (z.B. Rechnung bis Datum zahlen, Unterlagen schicken). due_date als
          YYYY-MM-DD oder leer.
        - events: konkrete Termine mit Datum und Uhrzeit (start/end als YYYY-MM-DDTHH:MM, ohne Zeitzone, end leer
          wenn unbekannt). Nur aufnehmen, wenn der Termin feststeht oder Alex eingeladen ist.
    """.trimIndent()

    const val ANALYZE_JSON_HINT = """Antworte NUR mit einem JSON-Objekt der Form:
{"category":"PERSONAL|INFO|NEWSLETTER","important":false,"needs_reply":false,"summary":"...","todos":[{"title":"...","due_date":"YYYY-MM-DD"}],"events":[{"title":"...","start":"YYYY-MM-DDTHH:MM","end":""}]}"""

    val ANALYZE_SCHEMA: Map<String, Any?> = mapOf(
        "type" to "object",
        "additionalProperties" to false,
        "required" to listOf("category", "important", "needs_reply", "summary", "todos", "events"),
        "properties" to mapOf(
            "category" to mapOf("type" to "string", "enum" to listOf("PERSONAL", "INFO", "NEWSLETTER")),
            "important" to mapOf("type" to "boolean"),
            "needs_reply" to mapOf("type" to "boolean"),
            "summary" to mapOf("type" to "string"),
            "todos" to mapOf(
                "type" to "array",
                "items" to mapOf(
                    "type" to "object", "additionalProperties" to false,
                    "required" to listOf("title", "due_date"),
                    "properties" to mapOf("title" to mapOf("type" to "string"), "due_date" to mapOf("type" to "string")),
                ),
            ),
            "events" to mapOf(
                "type" to "array",
                "items" to mapOf(
                    "type" to "object", "additionalProperties" to false,
                    "required" to listOf("title", "start", "end"),
                    "properties" to mapOf(
                        "title" to mapOf("type" to "string"),
                        "start" to mapOf("type" to "string"),
                        "end" to mapOf("type" to "string"),
                    ),
                ),
            ),
        ),
    )

    fun analyzeUser(m: MailEntity, now: String) = """
        Jetzt: $now
        Von: ${m.fromName} <${m.fromAddress}>
        An: ${m.toAddress}
        Betreff: ${m.subject}

        ${m.body.take(12_000)}
    """.trimIndent()

    val DRAFT_SYSTEM = """
        Du schreibst Antwortentwürfe auf E-Mails im Namen von Alex. Alex prüft jeden Entwurf, bevor er gesendet wird.
        Regeln:
        - Schreibe so, wie Alex selbst schreibt (siehe Stilprofil). Ohne Stilprofil: freundlich, knapp, natürlich.
        - Antworte in der Sprache der eingehenden Mail. Du/Sie wie der Absender bzw. wie im Stilprofil.
        - Erfinde keine Fakten, Zusagen oder Termine. Wo Alex etwas entscheiden muss, setze einen klar
          erkennbaren Platzhalter in eckigen Klammern, z.B. [Uhrzeit bestätigen].
        - Gib NUR den Text der Antwort aus (Anrede bis Grußformel), ohne Betreff, ohne Zitat der Originalmail,
          ohne Erklärungen.
    """.trimIndent()

    fun draftUser(m: MailEntity, style: String, extra: String?, now: String) = buildString {
        appendLine("Jetzt: $now")
        if (style.isNotBlank()) appendLine("Stilprofil von Alex:\n$style\n")
        if (!extra.isNullOrBlank()) appendLine("Zusätzliche Anweisung von Alex: $extra\n")
        appendLine("Eingehende Mail:")
        appendLine("Von: ${m.fromName} <${m.fromAddress}>")
        appendLine("Betreff: ${m.subject}")
        appendLine()
        appendLine(m.body.take(12_000))
    }

    val COMPOSE_SYSTEM = """
        Du formulierst eine neue E-Mail im Namen von Alex. Alex prüft sie vor dem Senden.
        Schreibe im Stil von Alex (siehe Stilprofil). Erfinde keine Fakten.
        Ausgabeformat exakt:
        Betreff: <Betreff>
        <leere Zeile>
        <Mailtext von Anrede bis Grußformel>
    """.trimIndent()

    fun composeUser(recipient: String, subject: String, instruction: String, style: String, now: String) = buildString {
        appendLine("Jetzt: $now")
        if (style.isNotBlank()) appendLine("Stilprofil von Alex:\n$style\n")
        appendLine("Empfänger: $recipient")
        if (subject.isNotBlank()) appendLine("Betreff-Idee: $subject")
        appendLine("Inhalt laut Alex: $instruction")
    }

    val REVISE_SYSTEM = """
        Überarbeite den E-Mail-Entwurf von Alex gemäß seiner Anweisung. Behalte Stil und Sprache bei,
        ändere nur, was die Anweisung verlangt. Gib NUR den überarbeiteten Mailtext aus.
    """.trimIndent()

    fun reviseUser(draft: String, instruction: String, m: MailEntity?) = buildString {
        if (m != null) {
            appendLine("Ursprüngliche Mail von ${m.fromName} <${m.fromAddress}>, Betreff: ${m.subject}")
            appendLine(m.body.take(6000))
            appendLine()
        }
        appendLine("Aktueller Entwurf:\n$draft\n")
        appendLine("Anweisung: $instruction")
    }

    val STYLE_SYSTEM = """
        Analysiere die folgenden von Alex gesendeten E-Mails und beschreibe seinen Schreibstil als kompaktes
        Stilprofil (max. 15 Stichpunkte, Deutsch): typische Anreden und Grußformeln (wörtlich), Du/Sie-Verhalten,
        Satzlänge, Tonfall, Emojis ja/nein, typische Formulierungen, Signatur. Keine Inhalte oder Namen Dritter
        übernehmen, nur den Stil.
    """.trimIndent()

    fun styleUser(samples: List<String>) =
        samples.mapIndexed { i, s -> "--- Mail ${i + 1} ---\n$s" }.joinToString("\n\n")
}
