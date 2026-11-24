package at.hochalex.assistent.ui

import android.app.role.RoleManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import at.hochalex.assistent.container
import at.hochalex.assistent.data.MailAccountConfig
import at.hochalex.assistent.mail.MailClient
import at.hochalex.assistent.work.Scheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsTab() {
    val ctx = LocalContext.current
    val c = ctx.container
    val scope = rememberCoroutineScope()
    var s by remember { mutableStateOf(c.settings.get()) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var modelInfo by remember { mutableStateOf(if (c.local.available()) "Lokales Modell vorhanden (${c.local.modelFile.length() / 1_000_000} MB)" else "Kein lokales Modell") }

    fun toast(t: String) = Toast.makeText(ctx, t, Toast.LENGTH_LONG).show()
    fun run(label: String, block: suspend () -> String) = scope.launch {
        busy = true; status = "$label …"
        status = runCatching { withContext(Dispatchers.IO) { block() } }.fold({ "$label: $it" }, { "$label: Fehler – ${it.message}" })
        busy = false
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) run("Modell importieren") {
            c.local.import(uri) { bytes -> modelInfo = "Kopiere … ${bytes / 1_000_000} MB" }
            modelInfo = "Lokales Modell vorhanden (${c.local.modelFile.length() / 1_000_000} MB)"
            "fertig"
        }
    }

    val roleHeld = remember { ctx.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_ASSISTANT) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)

        Section("Standard-Assistent")
        Text(if (roleHeld) "✓ Diese App ist der Standard-Assistent." else "Noch nicht aktiv: Einstellungen → Apps → Standard-Apps → Digitaler Assistent → „Assistent“ wählen. Danach Power-Taste lang drücken.")
        OutlinedButton(onClick = { openAssistantSettings(ctx) }) { Text("Assistenten-Einstellungen öffnen") }

        s.accounts.forEachIndexed { idx, acc ->
            Section(if (acc.id == "gmail") "Gmail (IMAP + App-Passwort)" else "Zweites Mailkonto (IMAP/SMTP)")
            AccountEditor(acc) { updated -> s = s.copy(accounts = s.accounts.toMutableList().also { it[idx] = updated }) }
            OutlinedButton(enabled = !busy, onClick = { run("Test ${acc.label}") { MailClient(s.accounts[idx]).testConnection() } }) { Text("Verbindung testen") }
        }

        Section("Home Assistant")
        Field("URL (https://…)", s.haUrl) { s = s.copy(haUrl = it) }
        Field("Long-Lived Access Token", s.haToken, secret = true) { s = s.copy(haToken = it) }
        Field("Standard-Aufgabenliste (Entity)", s.defaultTodoEntity) { s = s.copy(defaultTodoEntity = it) }
        Field("Standard-Kalender (Entity)", s.defaultCalendarEntity) { s = s.copy(defaultCalendarEntity = it) }
        OutlinedButton(enabled = !busy, onClick = {
            run("Home Assistant") {
                val ha = at.hochalex.assistent.ha.HaClient(s.haUrl, s.haToken)
                val cals = ha.calendars().joinToString { it.entityId }
                val todos = ha.todoLists().joinToString { it.entityId }
                "${ha.ping()}\nKalender: $cals\nListen: $todos"
            }
        }) { Text("Verbindung testen & Entities anzeigen") }

        Section("Claude (Antwortentwürfe & Sprachagent)")
        Field("API-Schlüssel", s.claudeApiKey, secret = true) { s = s.copy(claudeApiKey = it) }
        Field("Modell", s.claudeModel) { s = s.copy(claudeModel = it) }
        c.settings.getString("lastClaudeError")?.let {
            Text("Letzter Claude-Fehler: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        OutlinedButton(enabled = !busy, onClick = {
            run("Claude") { at.hochalex.assistent.ai.ClaudeClient(s.claudeApiKey, s.claudeModel).test() }
        }) { Text("Claude testen") }

        Section("Lokales Modell (on-device)")
        Text("Gemma-Modell als MediaPipe-.task-Datei (z.B. gemma-3n-E2B-it-int4.task). Wird für Mail-Analyse und private Entwürfe genutzt – nichts verlässt das Handy.",
            style = MaterialTheme.typography.bodySmall)
        Text(modelInfo)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !busy, onClick = { picker.launch(arrayOf("*/*")) }) { Text("Modell importieren") }
            if (c.local.available()) OutlinedButton(onClick = { c.local.delete(); modelInfo = "Kein lokales Modell" }) { Text("Löschen") }
        }

        Section("Datenschutz")
        Text("Absender oder Domains, deren Mails NIE an die Cloud gehen (eine pro Zeile, z.B. iris@example.com oder @bank.at):",
            style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(
            value = s.privateSenders.joinToString("\n"),
            onValueChange = { v -> s = s.copy(privateSenders = v.lines()) },
            modifier = Modifier.fillMaxWidth(), minLines = 3,
        )

        Section("Proaktiv")
        Toggle("Morgen-Briefing", s.briefingEnabled) { s = s.copy(briefingEnabled = it) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field("Stunde", s.briefingHour.toString(), number = true, modifier = Modifier.weight(1f)) { v -> v.toIntOrNull()?.takeIf { it in 0..23 }?.let { s = s.copy(briefingHour = it) } }
            Field("Minute", s.briefingMinute.toString(), number = true, modifier = Modifier.weight(1f)) { v -> v.toIntOrNull()?.takeIf { it in 0..59 }?.let { s = s.copy(briefingMinute = it) } }
        }
        Field("Erinnerung vor Terminen (Minuten)", s.reminderLeadMinutes.toString(), number = true) { v -> v.toIntOrNull()?.let { s = s.copy(reminderLeadMinutes = it.coerceIn(5, 240)) } }
        Toggle("Wichtige Mails sofort melden", s.notifyImportant) { s = s.copy(notifyImportant = it) }

        Section("Schreibstil")
        Text(if (s.styleProfile.isBlank()) "Noch nicht gelernt." else s.styleProfile, style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { Scheduler.learnStyleNow(ctx); toast("Stil wird im Hintergrund aus gesendeten Mails gelernt.") }) { Text("Stil jetzt lernen") }

        HorizontalDivider()
        Button(modifier = Modifier.fillMaxWidth(), onClick = {
            val cleaned = s.copy(privateSenders = s.privateSenders.map { it.trim() }.filter { it.isNotEmpty() })
            c.settings.save(cleaned.copy(styleProfile = c.settings.get().styleProfile, styleUpdatedAt = c.settings.get().styleUpdatedAt))
            s = c.settings.get()
            Scheduler.scheduleBriefing(ctx)
            toast("Gespeichert")
        }) { Text("Speichern") }
    }
}

@Composable
private fun AccountEditor(acc: MailAccountConfig, onChange: (MailAccountConfig) -> Unit) {
    Toggle("Aktiv", acc.enabled) { onChange(acc.copy(enabled = it)) }
    Field("E-Mail-Adresse", acc.email) { onChange(acc.copy(email = it.trim())) }
    Field("Anzeigename", acc.displayName) { onChange(acc.copy(displayName = it)) }
    Field("Benutzername (leer = E-Mail)", acc.username) { onChange(acc.copy(username = it.trim())) }
    Field(if (acc.id == "gmail") "App-Passwort (16 Zeichen)" else "Passwort", acc.password, secret = true) { onChange(acc.copy(password = it.replace(" ", ""))) }
    if (acc.id != "gmail") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field("IMAP-Server", acc.imapHost, modifier = Modifier.weight(2f)) { onChange(acc.copy(imapHost = it.trim())) }
            Field("Port", acc.imapPort.toString(), number = true, modifier = Modifier.weight(1f)) { v -> v.toIntOrNull()?.let { onChange(acc.copy(imapPort = it)) } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field("SMTP-Server", acc.smtpHost, modifier = Modifier.weight(2f)) { onChange(acc.copy(smtpHost = it.trim())) }
            Field("Port", acc.smtpPort.toString(), number = true, modifier = Modifier.weight(1f)) { v -> v.toIntOrNull()?.let { onChange(acc.copy(smtpPort = it)) } }
        }
    }
}

@Composable
private fun Section(t: String) = Text(t, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))

@Composable
private fun Field(label: String, value: String, modifier: Modifier = Modifier, secret: Boolean = false, number: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true, modifier = if (modifier == Modifier) Modifier.fillMaxWidth() else modifier,
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = when {
            secret -> KeyboardType.Password
            number -> KeyboardType.Number
            else -> KeyboardType.Text
        }),
    )
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked, onChange)
    }
}
