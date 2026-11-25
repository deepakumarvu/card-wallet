package app.cardwallet

import app.cardwallet.SmsSpends.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsSpendsTest {
    private val axis = Card(1, "Axis Ace", "Axis Bank", "1234", 1.5)
    private val axisSms = "Spent INR 1568 using Axis Bank Credit Card no. XX1234 on 24-10-26 11:07:10 IST at BLINKIT. " +
        "Avl limit: INR 50999.49. Not you? SMS BLOCK 1234 to +919951860002."

    @Test
    fun `card spend SMS is logged on the card with those last 4 digits`() {
        val result = SmsSpends.read(axisSms, "AX-AXISBK-S", 42, listOf(axis))
        val spend = (result as Result.CardSpend).spend
        assertEquals(axis.id, spend.cardId)
        assertEquals(1568_00L, spend.amountPaise)
        assertEquals("BLINKIT", spend.merchant)
        assertEquals(42L, spend.timeMillis)
    }

    @Test
    fun `card spend on a card you have not added waits for you`() {
        val other = axis.copy(last4 = "9999")
        assertTrue(SmsSpends.read(axisSms, "AX-AXISBK-S", 42, listOf(other)) is Result.NeedsYou)
    }

    @Test
    fun `same SMS gives the same key so it is never logged twice`() {
        val a = SmsSpends.read(axisSms, "AX-AXISBK-S", 42, listOf(axis)) as Result.CardSpend
        val b = SmsSpends.read(axisSms, "AX-AXISBK-S", 42, listOf(axis)) as Result.CardSpend
        assertEquals(a.spend.smsKey, b.spend.smsKey)
    }

    @Test
    fun `OTPs and messages from unknown senders are ignored`() {
        val otp = "123456 is your OTP for txn of INR 500 on Axis Bank Card XX1234. Do not share."
        assertEquals(Result.Ignore, SmsSpends.read(otp, "AX-AXISBK-S", 1, listOf(axis)))
        assertEquals(Result.Ignore, SmsSpends.read(axisSms, "+919999999999", 1, listOf(axis)))
    }

    @Test
    fun `debit card spend is logged only when the last 4 digits match`() {
        val debitSms = "Spent Rs.450 From HDFC Bank Card x5678 At ZOMATO On 2026-11-20:12:30:00 Bal Rs.10000 Not You? Call 18002586161"
        val debit = Card(2, "HDFC Debit", "HDFC Bank", "5678", 0.0, isDebit = true)
        val logged = SmsSpends.read(debitSms, "AD-HDFCBK-S", 42, listOf(debit))
        assertEquals(debit.id, (logged as Result.CardSpend).spend.cardId)
        assertEquals(Result.Ignore, SmsSpends.read(debitSms, "AD-HDFCBK-S", 42, listOf(debit.copy(last4 = "0000"))))
    }
}
