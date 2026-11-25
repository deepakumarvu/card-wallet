package app.cardwallet.statement

import app.cardwallet.Card
import app.cardwallet.Spend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GmailSyncTest {
    private val ornge = Card(1, "Swiggy HDFC Ornge", "HDFC Bank", "0027", 1.0)
    private val t = 1_764_000_000_000L
    private fun alert(kind: String = TxnKind.SPEND, credit: Boolean = false, last4: String = "0027", currency: String = "INR") =
        CardAlert("HDFC Bank", last4, kind, credit, 450_00, currency, "ZOMATO", t, null, "", "credit")

    @Test
    fun `alert email becomes a spend on the card with those digits`() {
        val spend = GmailSync.spendFromAlert(alert(), "<id1@hdfc>", listOf(ornge), emptyList())!!
        assertEquals(ornge.id, spend.cardId)
        assertEquals(450_00L, spend.amountPaise)
        assertEquals("ZOMATO", spend.merchant)
    }

    @Test
    fun `refunds, foreign currency, unknown cards and SMS-logged spends are skipped`() {
        assertNull(GmailSync.spendFromAlert(alert(kind = TxnKind.REFUND, credit = true), "a", listOf(ornge), emptyList()))
        assertNull(GmailSync.spendFromAlert(alert(currency = "USD"), "a", listOf(ornge), emptyList()))
        assertNull(GmailSync.spendFromAlert(alert(last4 = "9999"), "a", listOf(ornge), emptyList()))
        val fromSms = Spend(5, 1, 450_00, "ZOMATO LTD", t + 60_000)
        assertNull(GmailSync.spendFromAlert(alert(), "a", listOf(ornge), listOf(fromSms)))
    }
}
