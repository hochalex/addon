package at.hochalex.assistent.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import at.hochalex.assistent.R
import at.hochalex.assistent.data.DraftEntity
import at.hochalex.assistent.data.MailEntity
import at.hochalex.assistent.data.SuggestionEntity
import at.hochalex.assistent.data.SuggestionType
import at.hochalex.assistent.ui.ConfirmSendActivity
import at.hochalex.assistent.ui.DraftReviewActivity
import at.hochalex.assistent.ui.MainActivity
import at.hochalex.assistent.voice.VoiceActivity

object Notifier {
    const val CH_DRAFTS = "drafts"
    const val CH_IMPORTANT = "important"
    const val CH_BRIEFING = "briefing"
    const val CH_REMINDERS = "reminders"
    const val CH_SUGGESTIONS = "suggestions"
    const val CH_STATUS = "status"

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        listOf(
            NotificationChannel(CH_DRAFTS, "Antwortentwürfe", NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel(CH_IMPORTANT, "Wichtige Mails", NotificationManager.IMPORTANCE_HIGH),
            NotificationChannel(CH_BRIEFING, "Morgen-Briefing", NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel(CH_REMINDERS, "Erinnerungen", NotificationManager.IMPORTANCE_HIGH),
            NotificationChannel(CH_SUGGESTIONS, "Vorschläge aus Mails", NotificationManager.IMPORTANCE_LOW),
            NotificationChannel(CH_STATUS, "Status & Fehler", NotificationManager.IMPORTANCE_LOW),
        ).forEach { nm.createNotificationChannel(it) }
    }

    private fun notify(ctx: Context, id: Int, n: android.app.Notification) {
        if (ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        NotificationManagerCompat.from(ctx).notify(id, n)
    }

    private fun flags() = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    private fun openApp(ctx: Context) = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), flags())

    fun draftId(id: Long) = 100_000 + id.toInt()

    fun showDraft(ctx: Context, d: DraftEntity, mail: MailEntity?) {
        val review = PendingIntent.getActivity(ctx, draftId(d.id),
            Intent(ctx, DraftReviewActivity::class.java).putExtra(DraftReviewActivity.EXTRA_DRAFT_ID, d.id), flags())
        val send = PendingIntent.getActivity(ctx, draftId(d.id) + 1,
            Intent(ctx, ConfirmSendActivity::class.java).putExtra(ConfirmSendActivity.EXTRA_DRAFT_ID, d.id), flags())
        val discard = PendingIntent.getBroadcast(ctx, draftId(d.id) + 2,
            Intent(ctx, ActionReceiver::class.java).setAction(ActionReceiver.ACTION_DISCARD).putExtra(ActionReceiver.EXTRA_ID, d.id), flags())
        val who = d.toName.ifBlank { d.toAddress }
        val n = NotificationCompat.Builder(ctx, CH_DRAFTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Entwurf an $who bereit")
            .setContentText(mail?.summary?.ifBlank { null } ?: d.subject)
            .setStyle(NotificationCompat.BigTextStyle().bigText("${mail?.summary?.let { "$it\n\n" } ?: ""}Entwurf:\n${d.body}"))
            .setContentIntent(review)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .addAction(0, "Senden", send)
            .addAction(0, "Bearbeiten", review)
            .addAction(0, "Verwerfen", discard)
            .setAutoCancel(false)
            .build()
        notify(ctx, draftId(d.id), n)
    }

    fun cancelDraft(ctx: Context, id: Long) = NotificationManagerCompat.from(ctx).cancel(draftId(id))

    fun showImportant(ctx: Context, m: MailEntity) {
        val n = NotificationCompat.Builder(ctx, CH_IMPORTANT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Wichtig: ${m.senderLabel}")
            .setContentText(m.summary.ifBlank { m.subject })
            .setStyle(NotificationCompat.BigTextStyle().bigText("${m.subject}\n\n${m.summary}"))
            .setContentIntent(openApp(ctx))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .build()
        notify(ctx, 200_000 + m.id.toInt(), n)
    }

    fun showSuggestion(ctx: Context, s: SuggestionEntity, mail: MailEntity?) {
        val accept = PendingIntent.getBroadcast(ctx, 300_000 + s.id.toInt(),
            Intent(ctx, ActionReceiver::class.java).setAction(ActionReceiver.ACTION_ACCEPT_SUGGESTION).putExtra(ActionReceiver.EXTRA_ID, s.id), flags())
        val reject = PendingIntent.getBroadcast(ctx, 310_000 + s.id.toInt(),
            Intent(ctx, ActionReceiver::class.java).setAction(ActionReceiver.ACTION_REJECT_SUGGESTION).putExtra(ActionReceiver.EXTRA_ID, s.id), flags())
        val what = if (s.type == SuggestionType.TODO) "Aufgabe" else "Termin"
        val whenTxt = if (s.type == SuggestionType.TODO) s.dueDate?.let { " (fällig $it)" } ?: "" else " (${s.start?.replace('T', ' ')})"
        val n = NotificationCompat.Builder(ctx, CH_SUGGESTIONS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("$what anlegen?")
            .setContentText(s.title + whenTxt)
            .setSubText(mail?.senderLabel)
            .setContentIntent(openApp(ctx))
            .addAction(0, "Anlegen", accept)
            .addAction(0, "Ignorieren", reject)
            .build()
        notify(ctx, suggestionId(s.id), n)
    }

    fun suggestionId(id: Long) = 300_000 + id.toInt()

    fun cancel(ctx: Context, id: Int) = NotificationManagerCompat.from(ctx).cancel(id)

    fun showBriefing(ctx: Context, text: String) {
        val speak = PendingIntent.getActivity(ctx, 400_001,
            Intent(ctx, VoiceActivity::class.java).putExtra(VoiceActivity.EXTRA_SPEAK, text)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags())
        val n = NotificationCompat.Builder(ctx, CH_BRIEFING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Guten Morgen – dein Tag")
            .setContentText(text.lineSequence().firstOrNull())
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp(ctx))
            .addAction(0, "Vorlesen", speak)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
        notify(ctx, 400_000, n)
    }

    fun showReminder(ctx: Context, key: String, title: String, text: String) {
        val n = NotificationCompat.Builder(ctx, CH_REMINDERS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(openApp(ctx))
            .setAutoCancel(true)
            .build()
        notify(ctx, 500_000 + (key.hashCode() and 0xFFFF), n)
    }

    fun showStatus(ctx: Context, title: String, text: String) {
        val n = NotificationCompat.Builder(ctx, CH_STATUS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp(ctx))
            .setAutoCancel(true)
            .build()
        notify(ctx, 600_000 + (title.hashCode() and 0xFFF), n)
    }
}
