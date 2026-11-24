package at.hochalex.assistent.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class MailAccountConfig(
    val id: String,
    val label: String,
    val email: String = "",
    val displayName: String = "",
    val imapHost: String = "",
    val imapPort: Int = 993,
    val smtpHost: String = "",
    val smtpPort: Int = 465,
    val username: String = "",
    val enabled: Boolean = false,
    /** Wird nicht serialisiert in die normalen Prefs, sondern im SecureStore abgelegt. */
    @kotlinx.serialization.Transient val password: String = "",
) {
    val isGmail get() = imapHost.equals("imap.gmail.com", ignoreCase = true)
    val isComplete get() = email.isNotBlank() && imapHost.isNotBlank() && smtpHost.isNotBlank() && password.isNotBlank()

    companion object {
        fun gmailDefaults() = MailAccountConfig(
            id = "gmail", label = "Gmail",
            imapHost = "imap.gmail.com", imapPort = 993,
            smtpHost = "smtp.gmail.com", smtpPort = 465,
        )

        fun imapDefaults() = MailAccountConfig(id = "imap2", label = "Zweites Konto")
    }
}

data class AppSettings(
    val accounts: List<MailAccountConfig> = listOf(MailAccountConfig.gmailDefaults(), MailAccountConfig.imapDefaults()),
    val haUrl: String = "",
    val haToken: String = "",
    val claudeApiKey: String = "",
    val claudeModel: String = DEFAULT_MODEL,
    /** Absender/Domains (eine pro Zeile), deren Mails NIE an die Cloud gehen. */
    val privateSenders: List<String> = emptyList(),
    val briefingHour: Int = 7,
    val briefingMinute: Int = 0,
    val briefingEnabled: Boolean = true,
    val defaultTodoEntity: String = "todo.aufgaben",
    val defaultCalendarEntity: String = "calendar.alex",
    val reminderLeadMinutes: Int = 30,
    val notifyImportant: Boolean = true,
    val styleProfile: String = "",
    val styleUpdatedAt: Long = 0,
) {
    val claudeConfigured get() = claudeApiKey.isNotBlank()
    val haConfigured get() = haUrl.isNotBlank() && haToken.isNotBlank()

    fun isPrivateSender(address: String): Boolean {
        val a = address.trim().lowercase()
        if (a.isEmpty()) return false
        return privateSenders.any { raw ->
            val p = raw.trim().lowercase()
            p.isNotEmpty() && (a == p || (p.startsWith("@") && a.endsWith(p)) || a.endsWith("@$p"))
        }
    }

    companion object {
        const val DEFAULT_MODEL = "claude-opus-5-5"
    }
}

class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val secure = SecureStore(context)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Volatile private var cached: AppSettings? = null

    fun get(): AppSettings = cached ?: load().also { cached = it }

    private fun load(): AppSettings {
        val d = AppSettings()
        val accounts = prefs.getString("accounts", null)
            ?.let { runCatching { json.decodeFromString<List<MailAccountConfig>>(it) }.getOrNull() }
            ?: d.accounts
        return AppSettings(
            accounts = accounts.map { it.copy(password = secure.get("pw_${it.id}") ?: "") },
            haUrl = prefs.getString("haUrl", d.haUrl)!!,
            haToken = secure.get("haToken") ?: "",
            claudeApiKey = secure.get("claudeKey") ?: "",
            claudeModel = prefs.getString("claudeModel", d.claudeModel)!!,
            privateSenders = prefs.getString("privateSenders", "")!!.lines().map { it.trim() }.filter { it.isNotEmpty() },
            briefingHour = prefs.getInt("briefingHour", d.briefingHour),
            briefingMinute = prefs.getInt("briefingMinute", d.briefingMinute),
            briefingEnabled = prefs.getBoolean("briefingEnabled", d.briefingEnabled),
            defaultTodoEntity = prefs.getString("defaultTodo", d.defaultTodoEntity)!!,
            defaultCalendarEntity = prefs.getString("defaultCalendar", d.defaultCalendarEntity)!!,
            reminderLeadMinutes = prefs.getInt("reminderLead", d.reminderLeadMinutes),
            notifyImportant = prefs.getBoolean("notifyImportant", d.notifyImportant),
            styleProfile = prefs.getString("styleProfile", "")!!,
            styleUpdatedAt = prefs.getLong("styleUpdatedAt", 0),
        )
    }

    @Synchronized
    fun save(s: AppSettings) {
        s.accounts.forEach { secure.put("pw_${it.id}", it.password) }
        secure.put("haToken", s.haToken)
        secure.put("claudeKey", s.claudeApiKey)
        prefs.edit()
            .putString("accounts", json.encodeToString(s.accounts))
            .putString("haUrl", s.haUrl.trim().trimEnd('/'))
            .putString("claudeModel", s.claudeModel.trim().ifEmpty { AppSettings.DEFAULT_MODEL })
            .putString("privateSenders", s.privateSenders.joinToString("\n"))
            .putInt("briefingHour", s.briefingHour)
            .putInt("briefingMinute", s.briefingMinute)
            .putBoolean("briefingEnabled", s.briefingEnabled)
            .putString("defaultTodo", s.defaultTodoEntity.trim())
            .putString("defaultCalendar", s.defaultCalendarEntity.trim())
            .putInt("reminderLead", s.reminderLeadMinutes)
            .putBoolean("notifyImportant", s.notifyImportant)
            .putString("styleProfile", s.styleProfile)
            .putLong("styleUpdatedAt", s.styleUpdatedAt)
            .apply()
        cached = null
    }

    fun update(block: (AppSettings) -> AppSettings) = save(block(get()))

    // Laufzeit-Status (kein Teil der Benutzereinstellungen)
    fun getLong(key: String, def: Long = 0) = prefs.getLong("state_$key", def)
    fun putLong(key: String, value: Long) = prefs.edit().putLong("state_$key", value).apply()
    fun getString(key: String): String? = prefs.getString("state_$key", null)
    fun putString(key: String, value: String) = prefs.edit().putString("state_$key", value).apply()
    fun getStringSet(key: String): Set<String> = prefs.getStringSet("state_$key", emptySet()) ?: emptySet()
    fun putStringSet(key: String, value: Set<String>) = prefs.edit().putStringSet("state_$key", value).apply()
}
