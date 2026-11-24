package at.hochalex.assistent.mail

import at.hochalex.assistent.data.MailAccountConfig
import com.sun.mail.imap.IMAPFolder
import java.util.Date
import java.util.Properties
import javax.mail.Address
import javax.mail.Flags
import javax.mail.Folder
import javax.mail.Message
import javax.mail.Multipart
import javax.mail.Part
import javax.mail.Session
import javax.mail.Store
import javax.mail.Transport
import javax.mail.UIDFolder
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeUtility

data class FetchedMail(
    val uid: Long,
    val messageId: String,
    val fromAddress: String,
    val fromName: String,
    val to: String,
    val subject: String,
    val receivedAt: Long,
    val body: String,
    val references: String,
    val isBulk: Boolean,
)

data class FetchResult(val mails: List<FetchedMail>, val uidValidity: Long, val highestUid: Long)

/**
 * IMAP/SMTP-Zugriff (Gmail per App-Passwort oder beliebiger Anbieter).
 * Alle Methoden blockieren – nur aus Dispatchers.IO aufrufen.
 */
class MailClient(private val cfg: MailAccountConfig) {

    private fun session(): Session {
        val p = Properties().apply {
            put("mail.store.protocol", "imaps")
            put("mail.imaps.host", cfg.imapHost)
            put("mail.imaps.port", cfg.imapPort.toString())
            put("mail.imaps.ssl.enable", "true")
            put("mail.imaps.ssl.checkserveridentity", "true")
            put("mail.imaps.peek", "true") // Mails beim Lesen NICHT als gelesen markieren
            put("mail.imaps.connectiontimeout", "15000")
            put("mail.imaps.timeout", "30000")
            put("mail.smtp.host", cfg.smtpHost)
            put("mail.smtp.port", cfg.smtpPort.toString())
            put("mail.smtp.auth", "true")
            put("mail.smtp.connectiontimeout", "15000")
            put("mail.smtp.timeout", "30000")
            if (cfg.smtpPort == 465) {
                put("mail.smtp.ssl.enable", "true")
            } else {
                put("mail.smtp.starttls.enable", "true")
                put("mail.smtp.starttls.required", "true")
            }
            put("mail.smtp.ssl.checkserveridentity", "true")
        }
        return Session.getInstance(p)
    }

    private fun <T> withStore(block: (Store) -> T): T {
        val store = session().getStore("imaps")
        store.connect(cfg.imapHost, cfg.imapPort, cfg.username.ifBlank { cfg.email }, cfg.password)
        try {
            return block(store)
        } finally {
            runCatching { store.close() }
        }
    }

    fun testConnection(): String = withStore { store ->
        val inbox = store.getFolder("INBOX")
        "OK – ${inbox.messageCount} Mails im Posteingang"
    }

    /**
     * Holt neue Mails aus dem Posteingang (UID > lastUid).
     * Beim allerersten Abruf (lastUid == 0) werden nur die letzten [initialCount] Mails geholt.
     */
    fun fetchNew(lastUid: Long, knownValidity: Long, initialCount: Int = 5, max: Int = 40): FetchResult = withStore { store ->
        val folder = store.getFolder("INBOX") as IMAPFolder
        folder.open(Folder.READ_ONLY)
        try {
            val validity = folder.uidValidity
            val start = if (lastUid == 0L || validity != knownValidity) {
                val count = folder.messageCount
                if (count == 0) return@withStore FetchResult(emptyList(), validity, 0)
                val first = folder.getMessage(maxOf(1, count - initialCount + 1))
                folder.getUID(first)
            } else lastUid + 1
            val msgs = folder.getMessagesByUID(start, UIDFolder.LASTUID)
                .filter { folder.getUID(it) >= start }
                .takeLast(max)
            val mails = msgs.mapNotNull { m -> runCatching { toFetched(m, folder.getUID(m)) }.getOrNull() }
            val highest = msgs.maxOfOrNull { folder.getUID(it) } ?: (start - 1).coerceAtLeast(lastUid)
            FetchResult(mails, validity, highest)
        } finally {
            runCatching { folder.close(false) }
        }
    }

    /**
     * Markiert eine Mail im Posteingang als gelesen (auf dem Server, also auch in Gmail sichtbar).
     * [expectedValidity] schützt davor, nach einem UID-Reset eine falsche Mail zu markieren.
     */
    fun markSeen(uid: Long, expectedValidity: Long): Boolean = withStore { store ->
        val folder = store.getFolder("INBOX") as IMAPFolder
        folder.open(Folder.READ_WRITE)
        try {
            if (expectedValidity != 0L && folder.uidValidity != expectedValidity) return@withStore false
            val msg = folder.getMessageByUID(uid) ?: return@withStore false
            msg.setFlag(Flags.Flag.SEEN, true)
            true
        } finally {
            runCatching { folder.close(false) }
        }
    }

    /** Texte der zuletzt gesendeten Mails (für das Stil-Lernen). */
    fun fetchSentBodies(count: Int = 25): List<Pair<String, String>> = withStore { store ->
        val sent = findSpecialFolder(store, "\\Sent") ?: return@withStore emptyList()
        sent.open(Folder.READ_ONLY)
        try {
            val n = sent.messageCount
            if (n == 0) return@withStore emptyList()
            sent.getMessages(maxOf(1, n - count + 1), n).mapNotNull { m ->
                runCatching {
                    val to = (m.getRecipients(Message.RecipientType.TO)?.firstOrNull() as? InternetAddress)?.address.orEmpty()
                    to to stripQuoted(extractText(m)).take(3000)
                }.getOrNull()
            }.filter { it.second.isNotBlank() }
        } finally {
            runCatching { sent.close(false) }
        }
    }

    fun send(toAddress: String, toName: String, subject: String, body: String, inReplyTo: String, references: String) {
        val session = session()
        val msg = MimeMessage(session).apply {
            setFrom(InternetAddress(cfg.email, cfg.displayName.ifBlank { null }, "UTF-8"))
            setRecipient(Message.RecipientType.TO, InternetAddress(toAddress, toName.ifBlank { null }, "UTF-8"))
            setSubject(subject, "UTF-8")
            setText(body, "UTF-8")
            sentDate = Date()
            if (inReplyTo.isNotBlank()) {
                setHeader("In-Reply-To", inReplyTo)
                setHeader("References", (references.split(Regex("\\s+")) + inReplyTo).filter { it.isNotBlank() }.distinct().joinToString(" "))
            }
            saveChanges()
        }
        Transport.send(msg, cfg.username.ifBlank { cfg.email }, cfg.password)
        // Gmail legt gesendete Mails automatisch ab, andere Anbieter meist nicht.
        if (!cfg.isGmail) runCatching {
            withStore { store ->
                findSpecialFolder(store, "\\Sent")?.let { sent ->
                    msg.setFlag(Flags.Flag.SEEN, true)
                    sent.appendMessages(arrayOf(msg))
                }
            }
        }
    }

    private fun findSpecialFolder(store: Store, attr: String): IMAPFolder? {
        val all = store.defaultFolder.list("*").filterIsInstance<IMAPFolder>()
        return all.firstOrNull { f -> runCatching { f.attributes.any { it.equals(attr, true) } }.getOrDefault(false) }
            ?: all.firstOrNull { it.name.lowercase() in listOf("sent", "gesendet", "gesendete elemente", "sent items", "gesendete objekte") }
    }

    private fun toFetched(m: Message, uid: Long): FetchedMail {
        val mm = m as MimeMessage
        val from = (m.from?.firstOrNull() as? InternetAddress)
        val headers = { name: String -> mm.getHeader(name)?.firstOrNull().orEmpty() }
        val bulk = headers("List-Unsubscribe").isNotEmpty() ||
            headers("Precedence").lowercase() in listOf("bulk", "list", "junk") ||
            headers("Auto-Submitted").let { it.isNotEmpty() && it.lowercase() != "no" }
        return FetchedMail(
            uid = uid,
            messageId = mm.messageID ?: "uid-$uid",
            fromAddress = from?.address.orEmpty(),
            fromName = from?.personal?.let { decode(it) }.orEmpty(),
            to = m.getRecipients(Message.RecipientType.TO)?.joinToString(", ") { addr(it) }.orEmpty(),
            subject = m.subject?.let { decode(it) }.orEmpty(),
            receivedAt = (m.receivedDate ?: m.sentDate ?: Date()).time,
            body = extractText(m).take(20_000),
            references = headers("References"),
            isBulk = bulk,
        )
    }

    private fun addr(a: Address) = (a as? InternetAddress)?.let { it.personal ?: it.address } ?: a.toString()

    private fun decode(s: String) = runCatching { MimeUtility.decodeText(s) }.getOrDefault(s)

    companion object {
        fun extractText(p: Part): String {
            return when {
                p.isMimeType("text/plain") -> p.content as? String ?: ""
                p.isMimeType("text/html") -> htmlToText(p.content as? String ?: "")
                p.isMimeType("multipart/alternative") -> {
                    val mp = p.content as Multipart
                    val parts = (0 until mp.count).map { mp.getBodyPart(it) }
                    parts.firstOrNull { it.isMimeType("text/plain") }?.let { extractText(it) }
                        ?: parts.firstNotNullOfOrNull { extractText(it).takeIf { t -> t.isNotBlank() } } ?: ""
                }
                p.isMimeType("multipart/*") -> {
                    val mp = p.content as Multipart
                    (0 until mp.count).map { mp.getBodyPart(it) }
                        .filter { it.disposition == null || it.disposition.equals(Part.INLINE, true) }
                        .joinToString("\n") { extractText(it) }
                }
                p.isMimeType("message/rfc822") -> extractText(p.content as Part)
                else -> ""
            }.trim()
        }

        fun htmlToText(html: String): String = html
            .replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
            .replace(Regex("(?i)<br\\s*/?>|</p>|</div>|</tr>|</li>"), "\n")
            .replace(Regex("<[^>]+>"), " ")
            .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'")
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\\n\\s*\\n+"), "\n\n")
            .trim()

        /** Entfernt zitierte Vorgänger-Mails, damit nur der eigene Text übrig bleibt. */
        fun stripQuoted(text: String): String {
            val lines = text.lines()
            val cut = lines.indexOfFirst { l ->
                l.startsWith(">") || Regex("^(Am|On) .{5,120}(schrieb|wrote).*:\\s*$").matches(l.trim()) ||
                    l.trim().startsWith("-----Original") || l.trim().startsWith("Von: ") || l.trim().startsWith("From: ")
            }
            return (if (cut > 0) lines.take(cut) else lines).joinToString("\n").trim()
        }
    }
}
