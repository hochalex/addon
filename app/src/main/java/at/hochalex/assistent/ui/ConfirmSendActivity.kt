package at.hochalex.assistent.ui

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import at.hochalex.assistent.container
import at.hochalex.assistent.voice.AuthGate
import kotlinx.coroutines.launch

/** Unsichtbare Activity hinter dem „Senden“-Knopf der Benachrichtigung: Fingerabdruck → Versand. */
class ConfirmSendActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getLongExtra(EXTRA_DRAFT_ID, -1)
        lifecycleScope.launch {
            val c = container
            val d = c.db.drafts().get(id)
            if (d == null) { finish(); return@launch }
            val ok = AuthGate(this@ConfirmSendActivity).authorize("Mail an ${d.toName.ifBlank { d.toAddress }} senden", d.subject)
            if (ok) {
                val r = c.drafts.send(id)
                Toast.makeText(this@ConfirmSendActivity,
                    r.fold({ "Gesendet ✓" }, { "Senden fehlgeschlagen: ${it.message}" }), Toast.LENGTH_LONG).show()
            }
            finish()
        }
    }

    companion object { const val EXTRA_DRAFT_ID = "draftId" }
}
