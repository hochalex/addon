package at.hochalex.assistent.work

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import at.hochalex.assistent.AppContainer
import at.hochalex.assistent.container
import at.hochalex.assistent.data.MailCategory
import at.hochalex.assistent.data.MailEntity
import at.hochalex.assistent.data.SuggestionEntity
import at.hochalex.assistent.data.SuggestionType
import at.hochalex.assistent.mail.MailClient
import at.hochalex.assistent.notify.Notifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

object Scheduler {
    private val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun scheduleAll(ctx: Context) {
        val wm = WorkManager.getInstance(ctx)
        wm.enqueueUniquePeriodicWork(
            "mail_sync", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<MailSyncWorker>(15, TimeUnit.MINUTES).setConstraints(net).build()
        )
        wm.enqueueUniquePeriodicWork(
            "reminders", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ReminderWorker>(15, TimeUnit.MINUTES).setConstraints(net).build()
        )
        wm.enqueueUniquePeriodicWork(
            "style", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<StyleWorker>(7, TimeUnit.DAYS).setConstraints(net).build()
        )
        scheduleBriefing(ctx)
    }

    /** Briefing als Einmal-Job zur nächsten Briefing-Zeit; der Job plant sich selbst neu. */
    fun scheduleBriefing(ctx: Context) {
        val s = ctx.container.settings.get()
        val wm = WorkManager.getInstance(ctx)
        if (!s.briefingEnabled) {
            wm.cancelUniqueWork("briefing"); return
        }
        val now = ZonedDateTime.now()
        var next = now.withHour(s.briefingHour).withMinute(s.briefingMinute).withSecond(0).withNano(0)
        if (!next.isAfter(now.plusMinutes(1))) next = next.plusDays(1)
        wm.enqueueUniqueWork(
            "briefing", ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<BriefingWorker>()
                .setInitialDelay(Duration.between(now, next).toMillis(), TimeUnit.MILLISECONDS)
                .build()
        )
    }

    fun syncNow(ctx: Context) {
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            "mail_sync_now", ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<MailSyncWorker>().setConstraints(net).build()
        )
    }

    fun learnStyleNow(ctx: Context) {
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            "style_now", ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<StyleWorker>().setConstraints(net).build()
        )
    }
}

/** Holt neue Mails, analysiert sie, erstellt Entwürfe und Vorschläge, benachrichtigt. */
class MailSyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext.container
        val s = c.settings.get()
        val ownAddresses = s.accounts.map { it.email.lowercase() }.toSet()
        var errors = 0
        for (acc in s.accounts.filter { it.enabled && it.isComplete }) {
            try {
                val lastUid = c.settings.getLong("lastUid_${acc.id}")
                val validity = c.settings.getLong("uidValidity_${acc.id}")
                val result = withContext(Dispatchers.IO) { MailClient(acc).fetchNew(lastUid, validity) }
                for (f in result.mails) {
                    if (f.fromAddress.lowercase() in ownAddresses) continue
                    val entity = MailEntity(
                        accountId = acc.id, uid = f.uid, messageId = f.messageId,
                        fromAddress = f.fromAddress, fromName = f.fromName, toAddress = f.to,
                        subject = f.subject, receivedAt = f.receivedAt, body = f.body, references = f.references,
                        isPrivate = s.isPrivateSender(f.fromAddress),
                        category = if (f.isBulk) MailCategory.NEWSLETTER else MailCategory.UNKNOWN,
                        processed = f.isBulk, summary = if (f.isBulk) f.subject else "",
                    )
                    c.db.mails().insert(entity)
                }
                c.settings.putLong("lastUid_${acc.id}", maxOf(result.highestUid, if (result.uidValidity == validity) lastUid else 0))
                c.settings.putLong("uidValidity_${acc.id}", result.uidValidity)
            } catch (e: Exception) {
                errors++
                Log.w(TAG, "Abruf ${acc.label} fehlgeschlagen", e)
                if (runAttemptCount == 0) Notifier.showStatus(applicationContext, "Mailabruf ${acc.label} fehlgeschlagen", e.message ?: e.javaClass.simpleName)
            }
        }
        processPending(c)
        c.db.mails().deleteOlderThan(System.currentTimeMillis() - 60L * 24 * 3600 * 1000)
        return if (errors > 0 && runAttemptCount < 2) Result.retry() else Result.success()
    }

    private suspend fun processPending(c: AppContainer) {
        val s = c.settings.get()
        for (mail in c.db.mails().unprocessed().take(20)) {
            val analysis = try {
                c.ai.analyze(mail, isBulk = false)
            } catch (e: Exception) {
                Log.w(TAG, "Analyse fehlgeschlagen", e); continue
            }
            val updated = mail.copy(
                category = analysis.category, important = analysis.important,
                needsReply = analysis.needsReply, summary = analysis.summary, processed = true,
            )
            c.db.mails().update(updated)

            if (updated.important && s.notifyImportant) Notifier.showImportant(applicationContext, updated)

            if (updated.needsReply) {
                runCatching { c.drafts.createReply(updated) }
                    .onSuccess { d -> d?.let { Notifier.showDraft(applicationContext, it, updated) } }
                    .onFailure { Log.w(TAG, "Entwurf fehlgeschlagen", it) }
            }

            analysis.todos.forEach { t ->
                val sug = SuggestionEntity(mailId = updated.id, type = SuggestionType.TODO, title = t.title, dueDate = t.dueDate)
                Notifier.showSuggestion(applicationContext, sug.copy(id = c.db.suggestions().insert(sug)), updated)
            }
            analysis.events.forEach { e ->
                if (runCatching { LocalDateTime.parse(e.start) }.isFailure) return@forEach
                val sug = SuggestionEntity(mailId = updated.id, type = SuggestionType.EVENT, title = e.title, start = e.start, end = e.end)
                Notifier.showSuggestion(applicationContext, sug.copy(id = c.db.suggestions().insert(sug)), updated)
            }
        }
    }

    companion object { private const val TAG = "MailSync" }
}

/** Erinnert an Termine kurz vor Beginn und an Aufgaben mit Fälligkeit. */
class ReminderWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext.container
        val ha = c.ha() ?: return Result.success()
        val lead = c.settings.get().reminderLeadMinutes.toLong()
        val now = ZonedDateTime.now()
        val done = c.settings.getStringSet("reminded").toMutableSet()
        runCatching {
            ha.allEvents(now, now.plusMinutes(lead + 16)).filter { !it.allDay && it.start.isAfter(now) }.forEach { ev ->
                val key = "ev|${ev.uid}|${ev.summary}|${ev.start.toEpochSecond()}"
                if (key in done) return@forEach
                if (Duration.between(now, ev.start).toMinutes() <= lead) {
                    val mins = Duration.between(now, ev.start).toMinutes()
                    Notifier.showReminder(applicationContext, key, ev.summary,
                        "in $mins Minuten (${ev.start.toLocalTime()})${if (ev.location.isNotBlank()) " – ${ev.location}" else ""}")
                    done += key
                }
            }
        }.onFailure { Log.w("Reminder", "Termine", it) }
        runCatching {
            val today = LocalDate.now()
            ha.todoLists().forEach { list ->
                ha.todoItems(list).filter { it.due != null }.forEach { t ->
                    val due = t.due!!
                    val key = "todo|${t.uid}|$due"
                    if (key in done) return@forEach
                    val dueNow = if (due.length > 10) {
                        runCatching { ZonedDateTime.parse(due) }.getOrNull()?.let { !it.isAfter(now.plusMinutes(lead)) } ?: false
                    } else runCatching { !LocalDate.parse(due).isAfter(today) }.getOrDefault(false) && now.hour >= 9
                    if (dueNow) {
                        Notifier.showReminder(applicationContext, key, "Fällig: ${t.summary}", "Liste ${t.list}")
                        done += key
                    }
                }
            }
        }.onFailure { Log.w("Reminder", "Todos", it) }
        c.settings.putStringSet("reminded", done.toList().takeLast(300).toSet())
        return Result.success()
    }
}

/** Erstellt den Text des Morgen-Briefings (auch für den Sprachbefehl „Briefing“ genutzt). */
object Briefing {
    suspend fun build(c: AppContainer): String = buildString {
        val now = ZonedDateTime.now()
        val ha = c.ha()
        if (ha != null) {
            val events = runCatching { ha.allEvents(now.toLocalDate().atStartOfDay(now.zone), now.toLocalDate().plusDays(1).atStartOfDay(now.zone)) }.getOrDefault(emptyList())
            if (events.isEmpty()) appendLine("Heute stehen keine Termine im Kalender.")
            else {
                appendLine("Heute ${if (events.size == 1) "steht ein Termin" else "stehen ${events.size} Termine"} an:")
                events.forEach { e -> appendLine("• ${if (e.allDay) "ganztägig" else e.start.toLocalTime().toString()} ${e.summary}${if (e.calendar.isNotBlank()) " (${e.calendar})" else ""}") }
            }
            val today = LocalDate.now()
            val due = runCatching {
                ha.todoLists().flatMap { l -> ha.todoItems(l) }
                    .filter { it.due != null && runCatching { !LocalDate.parse(it.due!!.take(10)).isAfter(today) }.getOrDefault(false) }
            }.getOrDefault(emptyList())
            if (due.isNotEmpty()) {
                appendLine("Fällige Aufgaben:")
                due.forEach { appendLine("• ${it.summary} (${it.list})") }
            }
        } else appendLine("Home Assistant ist nicht eingerichtet.")
        val since = System.currentTimeMillis() - 24 * 3600 * 1000
        val mails = c.db.mails().relevantSince(since)
        if (mails.isNotEmpty()) {
            appendLine("Wichtige Mails seit gestern:")
            mails.take(6).forEach { appendLine("• ${it.senderLabel}: ${it.summary.ifBlank { it.subject }}") }
        }
        val drafts = c.db.drafts().pending()
        if (drafts.isNotEmpty()) appendLine("${drafts.size} Antwortentwürfe warten auf deine Freigabe.")
    }.trim()
}

class BriefingWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext.container
        try {
            Notifier.showBriefing(applicationContext, Briefing.build(c))
        } finally {
            Scheduler.scheduleBriefing(applicationContext)
        }
        return Result.success()
    }
}

/** Lernt den Schreibstil aus gesendeten Mails (nur an nicht-private Empfänger). */
class StyleWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext.container
        val s = c.settings.get()
        if (!s.claudeConfigured) return Result.success()
        val samples = s.accounts.filter { it.enabled && it.isComplete }.flatMap { acc ->
            runCatching { withContext(Dispatchers.IO) { MailClient(acc).fetchSentBodies(20) } }.getOrDefault(emptyList())
        }.filterNot { (to, _) -> s.isPrivateSender(to) }.map { it.second }.filter { it.length > 20 }.take(30)
        val profile = runCatching { c.ai.learnStyle(samples) }.getOrNull() ?: return Result.retry()
        c.settings.update { it.copy(styleProfile = profile, styleUpdatedAt = System.currentTimeMillis()) }
        return Result.success()
    }
}
