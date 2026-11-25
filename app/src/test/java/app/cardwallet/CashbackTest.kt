package app.cardwallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CashbackTest {
    private val swiggyHdfc = Card(1, "Swiggy HDFC", "HDFC Bank", "1111", 1.0)
    private val axisAce = Card(2, "Axis Ace", "Axis Bank", "2222", 1.5)
    private val cards = listOf(swiggyHdfc, axisAce)

    private val swiggy10 = Rule(10, 1, "Swiggy 10%", "swiggy", null, 10.0, 1500_00)
    private val online5 = Rule(11, 1, "Online 5%", "", "Online", 5.0, 1500_00)
    private val ace4 = Rule(20, 2, "Swiggy/Zomato/Ola 4%", "swiggy, zomato, ola", null, 4.0, 500_00)
    private val rules = listOf(swiggy10, online5, ace4)

    private val table = listOf(MerchantCategory("zomato", "Online"), MerchantCategory("amazon", "Online"))

    private fun rank(merchant: String, rupees: Long, spends: List<Spend> = emptyList()) =
        Cashback.rank(rupees * 100, merchant, cards, rules, spends, table)

    @Test
    fun `picks the card with the most cashback for a merchant`() {
        // Each case: merchant, ₹ amount → best card, its cashback in ₹, the rule that pays.
        val cases = listOf(
            listOf("ZOMATO LTD", 1000, "Swiggy HDFC", 50, "Online 5%"),
            listOf("SWIGGY FOOD", 1000, "Swiggy HDFC", 100, "Swiggy 10%"),
            listOf("OLACABS", 1000, "Axis Ace", 40, "Swiggy/Zomato/Ola 4%"),
            listOf("DMART", 1000, "Axis Ace", 15, null), // base rate wins
        )
        for ((merchant, amount, card, back, rule) in cases) {
            val best = rank(merchant as String, (amount as Int).toLong()).first()
            assertEquals(merchant, card, best.card.name)
            assertEquals(merchant, (back as Int) * 100L, best.cashbackPaise)
            assertEquals(merchant, rule, best.rule?.label)
        }
    }

    @Test
    fun `keyword matches the start of a word only`() {
        // "ola" must not match "COCA COLA", so Axis Ace earns its 1.5% base, not 4%.
        val ace = rank("COCA COLA", 1000).single { it.card == axisAce }
        assertNull(ace.rule)
        assertEquals(15_00L, ace.cashbackPaise)
    }

    @Test
    fun `used-up cap moves the pick to the next best card`() {
        // ₹15,000 at Swiggy this month already paid the full ₹1,500 cap on Swiggy 10%.
        val spent = listOf(Spend(1, 1, 15_000_00, "SWIGGY", 1000))
        val picks = rank("SWIGGY FOOD", 1000, spent)

        val hdfc = picks.single { it.card == swiggyHdfc }
        assertEquals("Online 5% doesn't match Swiggy, so HDFC falls to its 1% base", 10_00L, hdfc.cashbackPaise)
        assertEquals(swiggy10, hdfc.cappedOut)
        assertEquals("Axis Ace 4% now wins", axisAce, picks.first().card)
    }

    @Test
    fun `partly used cap pays only what is left`() {
        // ₹14,000 at Swiggy used ₹1,400 of the ₹1,500 cap: a ₹1,000 spend gets ₹100, not ₹100 + more.
        val spent = listOf(Spend(1, 1, 14_000_00, "SWIGGY", 1000))
        val hdfc = rank("SWIGGY", 2000, spent).single { it.card == swiggyHdfc }
        assertEquals(100_00L, hdfc.cashbackPaise)
        assertEquals(100_00L, hdfc.capLeftPaise)
    }

    @Test
    fun `month replay gives each spend the rule that paid best at the time`() {
        val spends = listOf(
            Spend(1, 1, 10_000_00, "SWIGGY", 1), // 10% → ₹1,000
            Spend(2, 1, 10_000_00, "SWIGGY", 2), // ₹500 cap left on 10%; Online 5% doesn't match Swiggy; 1% base = ₹100 → ₹500
            Spend(3, 1, 2_000_00, "AMAZON", 3),  // Online 5% → ₹100
            Spend(4, 2, 1_000_00, "ZOMATO", 4),  // other card, not counted
        )
        val (earned, total) = Cashback.replayMonth(swiggyHdfc, rules, spends, table)
        assertEquals(1500_00L, earned[swiggy10.id])
        assertEquals(100_00L, earned[online5.id])
        assertEquals(22_000_00L, total.spentPaise)
        assertEquals(1600_00L, total.cashbackPaise)
    }
}
