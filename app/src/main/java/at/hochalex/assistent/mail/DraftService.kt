package at.hochalex.assistent.mail

import at.hochalex.assistent.AppContainer
import at.hochalex.assistent.data.DraftEntity
import at.hochalex.assistent.data.DraftStatus
import at.hochalex.assistent.data.MailEntity
import at.hochalex.assistent.notify.Notifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Erstellen, Überarbeiten, Versenden und Verwerfen von Entwürfen. */
class DraftService(private val c: AppContainer) {

    suspend fun createReply(mail: MailEntity, instruction: String? = null): DraftEntity? {
        val (body, local) = c.ai.draftReply(mail, instruction) ?: return null
        c.db.drafts().pendingForMail(mail.id)?.let { c.db.drafts().update(it.copy(status = DraftStatus.DISCARDED)) }
        val subject = if (mail.subject.startsWith("Re:", true) || mail.subject.startsWith("AW:", true)) mail.subject else "Re: ${mail.subject}"
        val draft = DraftEntity(
            mailId = mail.id, accountId = mail.accountId,
            toAddress = mail.fromAddress, toName = mail.fromName,
            subject = subject, body = body,
            inReplyTo = mail.messageId, references = mail.references,
            generatedLocally = local,
        )
        val id = c.db.drafts().insert(draft)
        return draft.copy(id = id)
    }

    suspend fun createNew(toAddress: String, toName: String, subjectHint: String, instruction: String, accountId: String? = null): DraftEntity? {
        val s = c.settings.get()
        val account = s.accounts.firstOrNull { it.id == accountId && it.enabled }
            ?: s.accounts.firstOrNull { it.enabled && it.isComplete } ?: return null
        val (subject, body) = c.ai.composeNew("$toName <$toAddress>", subjectHint, instruction, s.isPrivateSender(toAddress)) ?: return null
        val draft = DraftEntity(mailId = 0, accountId = account.id, toAddress = toAddress, toName = toName, subject = subject, body = body)
        return draft.copy(id = c.db.drafts().insert(draft))
    }

    suspend fun revise(draftId: Long, instruction: String): DraftEntity {
        val d = c.db.drafts().get(draftId) ?: error("Entwurf nicht gefunden")
        val mail = if (d.mailId > 0) c.db.mails().get(d.mailId) else null
        val private = mail?.isPrivate ?: c.settings.get().isPrivateSender(d.toAddress)
        val updated = d.copy(body = c.ai.reviseDraft(d.body, instruction, mail, private))
        c.db.drafts().update(updated)
        return updated
    }

    suspend fun updateBody(draftId: Long, body: String, subject: String? = null) {
        val d = c.db.drafts().get(draftId) ?: return
        c.db.drafts().update(d.copy(body = body, subject = subject ?: d.subject))
    }

    /** Versendet den Entwurf. Aufrufer MUSS vorher die Freigabe (Biometrie/Entsperren) eingeholt haben. */
    suspend fun send(draftId: Long): Result<Unit> = withContext(Dispatchers.IO) {
        val d = c.db.drafts().get(draftId) ?: return@withContext Result.failure(IllegalStateException("Entwurf nicht gefunden"))
        if (d.status == DraftStatus.SENT) return@withContext Result.success(Unit)
        val account = c.settings.get().accounts.firstOrNull { it.id == d.accountId }
            ?: return@withContext Result.failure(IllegalStateException("Mailkonto nicht konfiguriert"))
        runCatching {
            MailClient(account).send(d.toAddress, d.toName, d.subject, d.body, d.inReplyTo, d.references)
        }.onSuccess {
            c.db.drafts().update(d.copy(status = DraftStatus.SENT, error = ""))
            Notifier.cancelDraft(c.app, d.id)
        }.onFailure {
            c.db.drafts().update(d.copy(status = DraftStatus.FAILED, error = it.message ?: it.javaClass.simpleName))
        }
    }

    suspend fun discard(draftId: Long) {
        val d = c.db.drafts().get(draftId) ?: return
        c.db.drafts().update(d.copy(status = DraftStatus.DISCARDED))
        Notifier.cancelDraft(c.app, d.id)
    }
}
