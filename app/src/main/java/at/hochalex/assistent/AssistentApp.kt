package at.hochalex.assistent

import android.app.Application
import android.util.Log
import at.hochalex.assistent.data.MailEntity
import at.hochalex.assistent.mail.MailClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import at.hochalex.assistent.ai.AiService
import at.hochalex.assistent.ai.ClaudeClient
import at.hochalex.assistent.ai.LocalLlm
import at.hochalex.assistent.data.AppDatabase
import at.hochalex.assistent.data.SettingsRepository
import at.hochalex.assistent.ha.HaClient
import at.hochalex.assistent.mail.DraftService
import at.hochalex.assistent.notify.Notifier
import at.hochalex.assistent.work.Scheduler

class AssistentApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Notifier.createChannels(this)
        Scheduler.scheduleAll(this)
    }
}

/** Einfache manuelle Dependency-Injection. */
class AppContainer(val app: Application) {
    val settings = SettingsRepository(app)
    val db = AppDatabase.create(app)
    val local = LocalLlm(app)

    private var claudeCache: Pair<String, ClaudeClient>? = null

    @Synchronized
    fun claude(): ClaudeClient? {
        val s = settings.get()
        if (!s.claudeConfigured) return null
        val key = s.claudeApiKey + "|" + s.claudeModel
        claudeCache?.let { if (it.first == key) return it.second }
        return ClaudeClient(s.claudeApiKey, s.claudeModel).also { claudeCache = key to it }
    }

    fun ha(): HaClient? = settings.get().takeIf { it.haConfigured }?.let { HaClient(it.haUrl, it.haToken) }

    val ai = AiService({ settings.get() }, { claude() }, local)
    val drafts = DraftService(this)

    /** Für Hintergrundaktionen, die nicht auf die UI warten sollen. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Mail auf dem Server als gelesen markieren (läuft im Hintergrund, Fehler werden nur geloggt). */
    fun markRead(mail: MailEntity) {
        val account = settings.get().accounts.firstOrNull { it.id == mail.accountId && it.isComplete } ?: return
        val validity = settings.getLong("uidValidity_${mail.accountId}")
        appScope.launch {
            runCatching { MailClient(account).markSeen(mail.uid, validity) }
                .onFailure { Log.w("AppContainer", "Als gelesen markieren fehlgeschlagen", it) }
        }
    }
}

val android.content.Context.container: AppContainer
    get() = (applicationContext as AssistentApp).container
