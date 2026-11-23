package at.hochalex.assistent.ui

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import at.hochalex.assistent.container
import at.hochalex.assistent.data.DraftEntity
import at.hochalex.assistent.data.DraftStatus
import at.hochalex.assistent.data.MailEntity
import at.hochalex.assistent.data.SuggestionEntity
import at.hochalex.assistent.data.SuggestionType
import at.hochalex.assistent.notify.ActionReceiver
import at.hochalex.assistent.voice.VoiceActivity
import at.hochalex.assistent.work.Scheduler
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AssistentTheme { MainScreen() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen() {
    val ctx = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }
    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    LaunchedEffect(Unit) {
        perms.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS))
    }
    Scaffold(
        topBar = { TopAppBar(title = { Text(listOf("Heute", "Entwürfe", "Einstellungen")[tab]) }) },
        floatingActionButton = {
            if (tab != 2) FloatingActionButton(onClick = { ctx.startActivity(Intent(ctx, VoiceActivity::class.java)) }) {
                Icon(Icons.Default.Mic, "Sprechen")
            }
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(tab == 0, { tab = 0 }, { Icon(Icons.Default.Home, null) }, label = { Text("Heute") })
                NavigationBarItem(tab == 1, { tab = 1 }, { Icon(Icons.Default.Edit, null) }, label = { Text("Entwürfe") })
                NavigationBarItem(tab == 2, { tab = 2 }, { Icon(Icons.Default.Settings, null) }, label = { Text("Einstellungen") })
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                0 -> TodayTab()
                1 -> DraftsTab()
                else -> SettingsTab()
            }
        }
    }
}

@Composable
private fun TodayTab() {
    val ctx = LocalContext.current
    val c = ctx.container
    val scope = rememberCoroutineScope()
    val drafts by c.db.drafts().observePending().collectAsState(initial = emptyList())
    val suggestions by c.db.suggestions().observePending().collectAsState(initial = emptyList())
    val mails by c.db.mails().observeRelevant().collectAsState(initial = emptyList())
    var briefing by remember { mutableStateOf<String?>(null) }
    val s = c.settings.get()

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (!s.haConfigured || !s.claudeConfigured || s.accounts.none { it.enabled && it.isComplete }) {
            item {
                InfoCard("Einrichtung unvollständig",
                    "Bitte unter Einstellungen Mailkonten, Home Assistant und Claude-API-Schlüssel eintragen und die App als Standard-Assistent festlegen.")
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { Scheduler.syncNow(ctx) }) { Text("Mails abrufen") }
                OutlinedButton(onClick = { scope.launch { briefing = at.hochalex.assistent.work.Briefing.build(c) } }) { Text("Briefing") }
            }
        }
        briefing?.let { b -> item { InfoCard("Dein Tag", b) } }
        if (drafts.isNotEmpty()) item { SectionTitle("Entwürfe zur Freigabe (${drafts.size})") }
        items(drafts, key = { "d${it.id}" }) { DraftRow(it) }
        if (suggestions.isNotEmpty()) item { SectionTitle("Vorschläge aus Mails") }
        items(suggestions, key = { "s${it.id}" }) { sug ->
            SuggestionRow(sug,
                onAccept = { scope.launch { android.widget.Toast.makeText(ctx, ActionReceiver.acceptSuggestion(c, sug.id), android.widget.Toast.LENGTH_SHORT).show() } },
                onReject = { scope.launch { c.db.suggestions().update(sug.copy(status = "REJECTED")) } })
        }
        item { SectionTitle("Wichtig & zu beantworten") }
        if (mails.isEmpty()) item { Text("Noch keine relevanten Mails.", style = MaterialTheme.typography.bodyMedium) }
        items(mails, key = { "m${it.id}" }) { MailRow(it) }
    }
}

@Composable
private fun DraftsTab() {
    val c = LocalContext.current.container
    val drafts by c.db.drafts().observePending().collectAsState(initial = emptyList())
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (drafts.isEmpty()) item { Text("Keine offenen Entwürfe 🎉", Modifier.padding(top = 16.dp)) }
        items(drafts, key = { it.id }) { DraftRow(it) }
    }
}

@Composable
private fun DraftRow(d: DraftEntity) {
    val ctx = LocalContext.current
    Card(Modifier.fillMaxWidth().clickable {
        ctx.startActivity(Intent(ctx, DraftReviewActivity::class.java).putExtra(DraftReviewActivity.EXTRA_DRAFT_ID, d.id))
    }) {
        Column(Modifier.padding(12.dp)) {
            Text("An ${d.toName.ifBlank { d.toAddress }}", style = MaterialTheme.typography.titleSmall)
            Text(d.subject, style = MaterialTheme.typography.bodySmall)
            if (d.status == DraftStatus.FAILED) Text("Versand fehlgeschlagen: ${d.error}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            Text(d.body, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun SuggestionRow(s: SuggestionEntity, onAccept: () -> Unit, onReject: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)) {
            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text(if (s.type == SuggestionType.TODO) "Aufgabe" else "Termin", style = MaterialTheme.typography.labelSmall)
                Text(s.title, style = MaterialTheme.typography.bodyLarge)
                (s.dueDate ?: s.start?.replace('T', ' '))?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            TextButton(onClick = onReject) { Text("Nein") }
            TextButton(onClick = onAccept) { Text("Anlegen") }
        }
    }
}

@Composable
private fun MailRow(m: MailEntity) {
    val fmt = remember { SimpleDateFormat("dd.MM. HH:mm", Locale.GERMAN) }
    Card(
        Modifier.fillMaxWidth(),
        colors = if (m.important) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer) else CardDefaults.cardColors(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row {
                Text(m.senderLabel, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(fmt.format(Date(m.receivedAt)), style = MaterialTheme.typography.labelSmall)
            }
            Text(m.subject, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (m.summary.isNotBlank()) Text(m.summary, style = MaterialTheme.typography.bodyMedium)
            if (m.isPrivate) Text("🔒 privat – nur lokal verarbeitet", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
internal fun SectionTitle(t: String) = Text(t, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))

@Composable
internal fun InfoCard(title: String, text: String, action: (@Composable () -> Unit)? = null) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(text, style = MaterialTheme.typography.bodyMedium)
            action?.invoke()
        }
    }
}

internal fun openAssistantSettings(ctx: android.content.Context) {
    val intents = listOf(
        Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
        Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
        Intent(Settings.ACTION_SETTINGS),
    )
    for (i in intents) {
        if (runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
    }
}
