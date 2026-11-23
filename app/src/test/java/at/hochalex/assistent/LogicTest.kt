package at.hochalex.assistent

import at.hochalex.assistent.data.AppSettings
import at.hochalex.assistent.mail.MailClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogicTest {
    @Test
    fun privateSenders_matchAddressAndDomain() {
        val s = AppSettings(privateSenders = listOf("iris@example.com", "@bank.at", "arzt.at"))
        assertTrue(s.isPrivateSender("Iris@Example.com"))
        assertTrue(s.isPrivateSender("service@bank.at"))
        assertTrue(s.isPrivateSender("praxis@arzt.at"))
        assertFalse(s.isPrivateSender("someone@example.com"))
        assertFalse(s.isPrivateSender("x@notbank.at.evil.com"))
        assertFalse(s.isPrivateSender(""))
    }

    @Test
    fun htmlToText_stripsTagsAndEntities() {
        val t = MailClient.htmlToText("<html><style>p{}</style><p>Hallo&nbsp;Alex,</p><p>Frage: geht&#39;s &amp; passt?<br>LG</p></html>")
        assertEquals("Hallo Alex,\n Frage: geht's & passt?\nLG", t)
    }

    @Test
    fun stripQuoted_removesQuotedHistory() {
        val body = "Passt, bis Freitag!\nLG Alex\n\nAm 20.11.2026 um 10:00 schrieb Iris <iris@example.com>:\n> Geht Freitag?"
        assertEquals("Passt, bis Freitag!\nLG Alex", MailClient.stripQuoted(body))
    }
}
