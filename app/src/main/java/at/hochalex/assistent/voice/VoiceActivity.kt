package at.hochalex.assistent.voice

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import at.hochalex.assistent.container
import at.hochalex.assistent.data.DraftEntity
import at.hochalex.assistent.ui.AssistentTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Sprachdialog – wird vom Standard-Assistenten gestartet und funktioniert auch bei gesperrtem Handy.
 * Mails werden erst nach Entsperren bzw. Fingerabdruck gesendet (AuthGate).
 */
class VoiceActivity : ComponentActivity(), AgentHost {

    enum class Phase { IDLE, LISTENING, THINKING, SPEAKING }
    data class Line(val fromUser: Boolean, val text: String)

    private var phase by mutableStateOf(Phase.IDLE)
    private var partial by mutableStateOf("")
    private var level by mutableFloatStateOf(0f)
    private val lines = mutableStateListOf<Line>()

    private lateinit var input: SpeechInput
    private lateinit var output: SpeechOutput
    private lateinit var gate: AuthGate
    private lateinit var agent: VoiceAgent
    private var loop: Job? = null

    override val isLocked: Boolean get() = gate.isLocked

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        input = SpeechInput(this)
        output = SpeechOutput(this)
        gate = AuthGate(this)
        agent = VoiceAgent(container, this)

        setContent {
            AssistentTheme {
                VoiceScreen(
                    phase = phase, partial = partial, level = level, lines = lines, locked = isLocked,
                    onMic = { startConversation() },
                    onClose = { finish() },
                )
            }
        }

        val speakOnly = intent.getStringExtra(EXTRA_SPEAK)
        if (speakOnly != null) {
            lines += Line(false, speakOnly)
            loop = lifecycleScope.launch {
                phase = Phase.SPEAKING
                output.speak(speakOnly)
                phase = Phase.IDLE
            }
        } else startConversation()
    }

    private fun startConversation() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            lines += Line(false, "Mikrofon-Berechtigung fehlt. Bitte öffne die Assistent-App einmal entsperrt und erlaube das Mikrofon.")
            return
        }
        loop?.cancel()
        loop = lifecycleScope.launch {
            output.stop()
            while (true) {
                phase = Phase.LISTENING
                partial = ""
                val heard = input.listen(onPartial = { partial = it }, onLevel = { level = it })
                partial = ""
                if (heard == null) break // nichts gehört → Dialog endet
                lines += Line(true, heard)
                if (STOP_WORDS.any { heard.trim().lowercase().trimEnd('.', '!') == it }) {
                    phase = Phase.SPEAKING
                    output.speak("Gerne.")
                    finish()
                    return@launch
                }
                phase = Phase.THINKING
                val reply = runCatching { agent.handle(heard) }.getOrElse { "Fehler: ${it.message}" }
                lines += Line(false, reply)
                phase = Phase.SPEAKING
                output.speak(reply)
            }
            phase = Phase.IDLE
            delay(AUTO_CLOSE_MS)
            if (phase == Phase.IDLE) finish()
        }
    }

    override suspend fun confirmSend(draft: DraftEntity): Boolean {
        val who = draft.toName.ifBlank { draft.toAddress }
        phase = Phase.SPEAKING
        output.speak(if (isLocked) "Zum Senden bitte entsperren." else "Bitte mit Fingerabdruck bestätigen.")
        return gate.authorize("Mail an $who senden", draft.subject)
    }

    override suspend fun speakDirect(text: String) {
        lines += Line(false, text)
        val previous = phase
        phase = Phase.SPEAKING
        output.speak(text)
        phase = previous
    }

    override fun onDestroy() {
        loop?.cancel()
        input.destroy()
        output.shutdown()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_SPEAK = "speak"
        private const val AUTO_CLOSE_MS = 6000L
        private val STOP_WORDS = setOf("danke", "danke schön", "fertig", "stopp", "stop", "abbrechen", "tschüss", "passt")
    }
}

@Composable
private fun VoiceScreen(
    phase: VoiceActivity.Phase,
    partial: String,
    level: Float,
    lines: List<VoiceActivity.Line>,
    locked: Boolean,
    onMic: () -> Unit,
    onClose: () -> Unit,
) {
    val outside = remember { MutableInteractionSource() }
    val inside = remember { MutableInteractionSource() }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(interactionSource = outside, indication = null, onClick = onClose),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
                .navigationBarsPadding()
                .clickable(interactionSource = inside, indication = null) {},
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            Column(Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Assistent", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    if (locked) Icon(Icons.Default.Lock, contentDescription = "Gesperrt", modifier = Modifier.size(18.dp))
                    IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Schließen") }
                }
                val listState = rememberLazyListState()
                LaunchedEffect(lines.size) { if (lines.isNotEmpty()) listState.animateScrollToItem(lines.size - 1) }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(lines) { l ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = if (l.fromUser) Arrangement.End else Arrangement.Start) {
                            Text(
                                l.text,
                                modifier = Modifier
                                    .background(
                                        if (l.fromUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                        RoundedCornerShape(16.dp)
                                    )
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                }
                Spacer(Modifier.size(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when (phase) {
                            VoiceActivity.Phase.LISTENING -> partial.ifBlank { "Ich höre zu …" }
                            VoiceActivity.Phase.THINKING -> "Einen Moment …"
                            VoiceActivity.Phase.SPEAKING -> "…"
                            VoiceActivity.Phase.IDLE -> "Tippe aufs Mikrofon"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    val scale by animateFloatAsState(if (phase == VoiceActivity.Phase.LISTENING) 1f + (level.coerceIn(0f, 10f) / 25f) else 1f, label = "mic")
                    FilledIconButton(onClick = onMic, modifier = Modifier.size(64.dp).scale(scale)) {
                        Icon(Icons.Default.Mic, contentDescription = "Sprechen")
                    }
                }
            }
        }
    }
}
