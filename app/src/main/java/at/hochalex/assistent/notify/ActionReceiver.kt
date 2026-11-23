package at.hochalex.assistent.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import at.hochalex.assistent.container
import at.hochalex.assistent.data.SuggestionEntity
import at.hochalex.assistent.data.SuggestionType
import at.hochalex.assistent.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime

class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_ID, -1)
        val pending = goAsync()
        val c = context.container
        scope.launch {
            try {
                when (intent.action) {
                    ACTION_DISCARD -> c.drafts.discard(id)
                    ACTION_ACCEPT_SUGGESTION -> {
                        val msg = acceptSuggestion(c, id)
                        withContext(Dispatchers.Main) { Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() }
                    }
                    ACTION_REJECT_SUGGESTION -> {
                        c.db.suggestions().get(id)?.let { c.db.suggestions().update(it.copy(status = "REJECTED")) }
                        Notifier.cancel(context, Notifier.suggestionId(id))
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_DISCARD = "at.hochalex.assistent.DISCARD"
        const val ACTION_ACCEPT_SUGGESTION = "at.hochalex.assistent.ACCEPT_SUGGESTION"
        const val ACTION_REJECT_SUGGESTION = "at.hochalex.assistent.REJECT_SUGGESTION"
        const val EXTRA_ID = "id"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** Legt den Vorschlag in Home Assistant an. Gibt eine Meldung für den Nutzer zurück. */
        suspend fun acceptSuggestion(c: AppContainer, id: Long): String {
            val s: SuggestionEntity = c.db.suggestions().get(id) ?: return "Vorschlag nicht gefunden"
            val ha = c.ha() ?: return "Home Assistant ist nicht eingerichtet"
            val settings = c.settings.get()
            return runCatching {
                if (s.type == SuggestionType.TODO) {
                    ha.addTodo(settings.defaultTodoEntity, s.title, s.dueDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() })
                } else {
                    val start = LocalDateTime.parse(s.start!!)
                    val end = s.end?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
                    ha.createEvent(settings.defaultCalendarEntity, s.title, start, end, allDay = false)
                }
                c.db.suggestions().update(s.copy(status = "ACCEPTED"))
                Notifier.cancel(c.app, Notifier.suggestionId(id))
                if (s.type == SuggestionType.TODO) "Aufgabe angelegt" else "Termin eingetragen"
            }.getOrElse { "Fehler: ${it.message}" }
        }
    }
}
