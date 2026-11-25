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
}
