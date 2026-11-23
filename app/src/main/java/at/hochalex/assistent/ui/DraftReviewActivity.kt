package at.hochalex.assistent.ui

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import at.hochalex.assistent.container
import at.hochalex.assistent.data.DraftEntity
import at.hochalex.assistent.data.MailEntity
import at.hochalex.assistent.voice.AuthGate
import kotlinx.coroutines.launch

/** Entwurf prüfen, bearbeiten, per Anweisung überarbeiten, senden oder verwerfen. */
class DraftReviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getLongExtra(EXTRA_DRAFT_ID, -1)
        lifecycleScope.launch {
            val draft = container.db.drafts().get(id)
            if (draft == null) { finish(); return@launch }
            val mail = if (draft.mailId > 0) container.db.mails().get(draft.mailId) else null
            setContent { AssistentTheme { ReviewScreen(draft, mail) } }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun ReviewScreen(initial: DraftEntity, mail: MailEntity?) {
        val c = container
        val scope = rememberCoroutineScope()
        var subject by remember { mutableStateOf(initial.subject) }
        var body by remember { mutableStateOf(initial.body) }
        var instruction by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }

        fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

        Scaffold(topBar = {
            TopAppBar(
                title = { Text("An ${initial.toName.ifBlank { initial.toAddress }}") },
                navigationIcon = { IconButton(onClick = { finish() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") } },
            )
        }) { pad ->
            Column(
                Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (initial.error.isNotBlank()) Text("Letzter Versand fehlgeschlagen: ${initial.error}", color = MaterialTheme.colorScheme.error)
                if (initial.generatedLocally) Text("Entwurf wurde lokal auf dem Gerät erstellt (privat).", style = MaterialTheme.typography.labelMedium)
                if (mail != null) {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text("${mail.senderLabel} – ${mail.subject}", style = MaterialTheme.typography.titleSmall)
                            if (mail.summary.isNotBlank()) Text(mail.summary, style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(6.dp))
                            Text(mail.body.take(3000), style = MaterialTheme.typography.bodySmall, maxLines = 12)
                        }
                    }
                }
                OutlinedTextField(subject, { subject = it }, label = { Text("Betreff") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(body, { body = it }, label = { Text("Antwort") }, modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp))
                OutlinedTextField(instruction, { instruction = it }, label = { Text("Überarbeiten: z.B. „kürzer“, „für Freitag zusagen“") },
                    modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = !busy && instruction.isNotBlank(), onClick = {
                        scope.launch {
                            busy = true
                            runCatching {
                                c.drafts.updateBody(initial.id, body, subject)
                                c.drafts.revise(initial.id, instruction)
                            }.onSuccess { body = it.body; instruction = "" }.onFailure { toast("Fehler: ${it.message}") }
                            busy = false
                        }
                    }) { Text("Überarbeiten") }
                    OutlinedButton(enabled = !busy, onClick = {
                        scope.launch { c.drafts.discard(initial.id); finish() }
                    }) { Text("Verwerfen") }
                }
                Button(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                    scope.launch {
                        busy = true
                        c.drafts.updateBody(initial.id, body, subject)
                        val ok = AuthGate(this@DraftReviewActivity).authorize("Mail an ${initial.toName.ifBlank { initial.toAddress }} senden", subject)
                        if (ok) {
                            c.drafts.send(initial.id).onSuccess { toast("Gesendet ✓"); finish() }.onFailure { toast("Senden fehlgeschlagen: ${it.message}") }
                        }
                        busy = false
                    }
                }) {
                    Icon(Icons.AutoMirrored.Filled.Send, null)
                    Spacer(Modifier.height(0.dp).padding(4.dp))
                    Text("Senden")
                }
            }
        }
    }

    companion object { const val EXTRA_DRAFT_ID = "draftId" }
}
