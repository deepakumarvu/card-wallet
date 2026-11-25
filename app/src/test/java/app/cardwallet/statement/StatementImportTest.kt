package app.cardwallet.statement

import app.cardwallet.Card
import app.cardwallet.Spend
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class StatementImportTest {
    private val ornge = Card(1, "Swiggy HDFC Ornge", "HDFC Bank", "0027", 1.0, oldLast4 = "5125")
    private val regalia = Card(2, "HDFC Regalia Gold", "HDFC Bank", "7126", 2.5)
    private val cards = listOf(ornge, regalia)

    private fun statement(last4: String?, vararg lines: ParsedLine) =
        ParsedStatement(LocalDate.of(2026, 11, 14), null, LocalDate.of(2026, 12, 4), null, null, null, last4, lines.toList())

    private fun line(day: Int, what: String, rupees: Long, credit: Boolean = false) =
        ParsedLine(LocalDate.of(2026, 11, day), what, rupees * 100, credit)

    @Test
    fun `statement lands on the card with those digits, old digits included`() {
        assertEquals(regalia, StatementImport.cardFor(statement("7126"), "HDFC Bank", cards))
        assertEquals(ornge, StatementImport.cardFor(statement("5125"), "HDFC Bank", cards))
        // Two HDFC cards and no digits: can't tell which.
        assertEquals(null, StatementImport.cardFor(statement(null), "HDFC Bank", cards))
    }

    @Test
    fun `only purchases become spends, and ones already logged are skipped`() {
        val zone = ZoneOffset.UTC
        val smsLogged = Spend(9, 1, 450_00, "ZOMATO", LocalDate.of(2026, 11, 3).atStartOfDay(zone).toInstant().toEpochMilli())
        val parsed = statement("0027",
            line(3, "ZOMATO LTD", 450),                 // already logged from SMS a day apart → skip
            line(5, "SWIGGY", 300),                     // new purchase → add
            line(6, "PAYMENT RECEIVED - THANK YOU", 5000, credit = true),
            line(7, "REFUND MYNTRA", 200, credit = true),
            line(8, "IGST ON FEES", 18),
            line(9, "10% Sample CashBack", 30, credit = true),
        )
        val spends = StatementImport.newSpends(parsed, ornge, listOf(smsLogged), zone)
        assertEquals(listOf("SWIGGY"), spends.map { it.merchant })
        assertEquals(300_00L, spends.single().amountPaise)
        // Same statement again gives the same keys, so the database ignores the repeat.
        assertEquals(spends.map { it.smsKey }, StatementImport.newSpends(parsed, ornge, listOf(smsLogged), zone).map { it.smsKey })
    }

    @Test
    fun `bank is read from the statement text`() {
        assertEquals("HDFC Bank", StatementImport.issuerOf("HDFC BANK Credit Card Statement"))
        assertEquals("Axis Bank", StatementImport.issuerOf("Axis Bank Privilege American Express"))
        assertEquals(null, StatementImport.issuerOf("Some other statement"))
    }

    @Test
    fun `password pattern is worked out from the details`() {
        val me = PdfProfile(name = "Deepakumar V U", dob = LocalDate.of(1997, 10, 25), pan = "ABCDE1234F")
        val cards = listOf("0027", "7126")
        // Each case: password → the pattern it's built from.
        val cases = mapOf(
            "DEEP2510" to "{NAME4}{DDMM}",
            "deep25101997" to "{name4}{DDMMYYYY}",
            "251019977126" to "{DDMMYYYY}{LAST4}",
            "ABCDE1234F" to "{PAN}",
        )
        for ((password, pattern) in cases) assertEquals(password, pattern, Passwords.inferTemplates(password, me, cards).first())
        assertEquals("a password not built from your details", emptyList<String>(), Passwords.inferTemplates("Xy9!kk", me, cards))
        // A learned pattern opens next month's PDF for the same card.
        assertEquals(true, "DEEP2510" in Passwords.candidates(me.copy(templates = listOf("{NAME4}{DDMM}")), cards))
    }

    @Test
    fun `statement without readable digits is matched by the card's name in it`() {
        val ultimo = Card(3, "HDFC PhonePe Ultimo", "HDFC Bank", "4444", 1.0)
        val all = cards + ultimo
        assertEquals(regalia, StatementImport.cardFor(statement(null), "HDFC Bank", all, "HDFC BANK REGALIA GOLD CREDIT CARD STATEMENT"))
        assertEquals(ultimo, StatementImport.cardFor(statement(null), "HDFC Bank", all, "PhonePe HDFC Bank Ultimo Credit Card Statement"))
        // Swiggy appears as a merchant on a Regalia statement too: two names match, so no guess.
        assertEquals(null, StatementImport.cardFor(statement(null), "HDFC Bank", all, "Regalia Gold ... SWIGGY ... ULTIMO"))
    }
}
