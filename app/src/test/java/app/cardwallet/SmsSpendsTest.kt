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

    @Test
    fun `waiting messages say why`() {
        assertEquals("Card ending 1234 isn't in the app, or its digits aren't set", SmsSpends.whyWaiting(axisSms, "AX-AXISBK-S", emptyList()))
        assertEquals("Couldn't read this bank's message format", SmsSpends.whyWaiting("Your card was used somewhere", "AX-AXISBK-S", listOf(axis)))
    }

    @Test
    fun `real message shapes from HDFC, ICICI and Axis land on the right card`() {
        val hdfc = Card(21, "Ornge", "HDFC Bank", "0027", 1.0)
        val icici = Card(22, "Amazon Pay", "ICICI Bank", "5004", 1.0)
        val airtel = Card(23, "Airtel Axis", "Axis Bank", "2944", 1.0)
        val cards = listOf(hdfc, icici, airtel, Card(24, "Regalia", "HDFC Bank", "7126", 2.5))
        // Each case: sender, message (made-up digits) → card id it should land on.
        val cases = listOf(
            Triple("AD-HDFCBK-S", "Spent Rs.450 On HDFC Bank Card 0027 At ZOMATO On 2026-11-20:12:30:00Not You? To Block+Reissue Call 18002586161/SMS BLOCK CC 0027 to 7308080808", 21L),
            Triple("BP-ICICIT-S", "INR 450.00 spent using ICICI Bank Card XX5004 on 20-Nov-26 on AMAZON PAY IN G. Avl Limit: INR 1,00,000.00 If not you, call 1800 2662/SMS BLOCK 5004 to 9215676766", 22L),
            Triple("JM-ICICIT-S", "Rs 450.00 spent on ICICI Bank Card XX5004 on 20-Oct-26 at AMAZON PAY IN F. Avl Lmt: Rs 1,00,000.00 To dispute, call 18001080/SMS BLOCK 5004 to 9215676766 To convert this txn to EMI give a missed call on 9876543210 Know", 22L),
            Triple("AD-AXISBK-S", "Spent INR 825\nusing Axis Bank Credit Card no. XX2944 on 20-11-26 12:30:00 IST at \nAIRTEL PAYM. \nAvl limit: INR 50000 Not you? SMS BLOCK 2944 to +919951860002", 23L),
        )
        for ((sender, body, cardId) in cases) {
            val result = SmsSpends.read(body, sender, 42, cards)
            assertTrue("$sender → $result", result is Result.CardSpend && result.spend.cardId == cardId)
        }
    }

    @Test
    fun `declined and unconfirmed transactions are not spends`() {
        val cards = listOf(Card(23, "Airtel Axis", "Axis Bank", "2944", 1.0), Card(22, "Amazon Pay", "ICICI Bank", "5004", 1.0))
        val notSpends = listOf(
            "AD-AXISBK-S" to "Txn on Axis Bank Credit Card XX2944 has been declined as amt exceeds usage limit. Update limit @ Control Center on https://example.invalid or call 18604195555 - Axis Bank",
            "AD-ICICIT-S" to "We're unable to confirm txn of INR 450.00 on ICICI Bank Credit Card XX5004 on 20-OCT-26 To dispute call 18001080 or 912233667777 if not in India",
        )
        for ((sender, body) in notSpends) assertEquals(sender, Result.Ignore, SmsSpends.read(body, sender, 42, cards))
    }

    @Test
    fun `review form comes pre-filled from the message`() {
        // Card digits not in the app yet, so it waits; the form still gets amount and merchant.
        val sms = UnreadSms(sender = "AX-AXISBK-S", body = axisSms, timeMillis = 42, smsKey = "k")
        val spend = SmsSpends.draft(sms, listOf(axis.copy(last4 = "")))
        assertEquals(1568_00L, spend.amountPaise)
        assertEquals("BLINKIT", spend.merchant)
        assertEquals("the only Axis card is the guess", axis.id, spend.cardId)
        assertEquals("k", spend.smsKey)
    }

    @Test
    fun `SMS from a replaced card land on its new card`() {
        // The old card ended 1234; the new one ends 9999 and lists 1234 as old digits.
        val renewed = axis.copy(last4 = "9999", oldLast4 = "1234")
        val result = SmsSpends.read(axisSms, "AX-AXISBK-S", 42, listOf(renewed))
        assertEquals(renewed.id, (result as Result.CardSpend).spend.cardId)
    }

    @Test
    fun `a message shape marked not a spend is skipped next time, whatever the numbers`() {
        val declined = "Txn on Axis Bank Credit Card XX1234 has been declined as amt exceeds usage limit. Call 18604195555 - Axis Bank"
        val later = "Txn on Axis Bank Credit Card XX9876 has been declined as amt exceeds usage limit. Call 18604195555 - Axis Bank"
        assertEquals("AXISBK", SmsSpends.senderKey("AD-AXISBK-S"))
        assertEquals(SmsSpends.shapeKey("AD-AXISBK-S", declined), SmsSpends.shapeKey("VM-AXISBK-S", later))
        val ignored = setOf(SmsSpends.shapeKey("AD-AXISBK-S", declined))
        assertEquals(Result.Ignore, SmsSpends.read(later, "JD-AXISBK-S", 1, listOf(axis), ignored))
        // Real spends from the same bank still get logged.
        assertTrue(SmsSpends.read(axisSms, "AX-AXISBK-S", 1, listOf(axis), ignored) is Result.CardSpend)
    }

    @Test
    fun `near-identical ignored shapes merge into one with a wildcard`() {
        val a = SmsSpends.shapeOf("Payment of Rs 500 to SWIGGY declined due to low limit")
        val b = SmsSpends.shapeOf("Payment of Rs 120 to ZOMATO declined due to low limit")
        val merged = SmsSpends.merge(a, b)!!
        assertEquals("payment of rs # to * declined due to low limit", merged)
        val ignored = setOf("AXISBK|$merged")
        assertEquals(Result.Ignore, SmsSpends.read("Payment of Rs 75 to UBER declined due to low limit", "AD-AXISBK-S", 1, listOf(axis), ignored))
        // Too different (more than 2 words) stays separate.
        assertEquals(null, SmsSpends.merge(a, SmsSpends.shapeOf("Your card limit has been raised to Rs 50000 from today")))
    }

    @Test
    fun `an SMS spend already added from a statement isn't logged again`() {
        val sms = Spend(cardId = 1, amountPaise = 1568_00, merchant = "BLINKIT", timeMillis = 10 * 86_400_000L, smsKey = "abc")
        val fromStatement = Spend(9, 1, 1568_00, "BLINKIT COMMERCE", 11 * 86_400_000L, "stmt:x")
        val otherSms = Spend(8, 1, 1568_00, "BLINKIT", 10 * 86_400_000L + 60_000, "def")
        assertEquals(true, SmsSpends.alreadyLogged(sms, listOf(fromStatement)))
        assertEquals("two real same-amount SMS spends both count", false, SmsSpends.alreadyLogged(sms, listOf(otherSms)))
    }
}
