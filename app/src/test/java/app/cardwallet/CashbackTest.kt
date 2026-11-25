package app.cardwallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar

class CashbackTest {
    private val swiggyHdfc = Card(1, "Swiggy HDFC", "HDFC Bank", "1111", 1.0)
    private val axisAce = Card(2, "Axis Ace", "Axis Bank", "2222", 1.5)
    private val cards = listOf(swiggyHdfc, axisAce)

    private val swiggy10 = Rule(10, 1, "Swiggy 10%", "swiggy", null, 10.0, 1500_00)
    private val online5 = Rule(11, 1, "Online 5%", "", "Online", 5.0, 1500_00)
    private val ace4 = Rule(20, 2, "Swiggy/Zomato/Ola 4%", "swiggy, zomato, ola", null, 4.0, 500_00)
    private val rules = listOf(swiggy10, online5, ace4)

    private val table = listOf(MerchantCategory("zomato", "Online"), MerchantCategory("amazon", "Online"))

    /** Midnight (phone time zone) on the given date. */
    private fun day(year: Int, month: Int, day: Int, hour: Int = 0): Long =
        Calendar.getInstance().apply { clear(); set(year, month - 1, day, hour, 0) }.timeInMillis

    private val now = day(2026, 11, 20, hour = 12)
    private val earlier = day(2026, 11, 10)

    private fun rank(merchant: String, rupees: Long, spends: List<Spend> = emptyList(), cards: List<Card> = this.cards) =
        Cashback.rank(rupees * 100, merchant, cards, rules, spends, table, now)

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
        // ₹15,000 at Swiggy this cycle already paid the full ₹1,500 cap on Swiggy 10%.
        val spent = listOf(Spend(1, 1, 15_000_00, "SWIGGY", earlier))
        val picks = rank("SWIGGY FOOD", 1000, spent)

        val hdfc = picks.single { it.card == swiggyHdfc }
        assertEquals("Online 5% doesn't match Swiggy, so HDFC falls to its 1% base", 10_00L, hdfc.cashbackPaise)
        assertEquals(swiggy10, hdfc.cappedOut)
        assertEquals("Axis Ace 4% now wins", axisAce, picks.first().card)
    }

    @Test
    fun `partly used cap pays only what is left`() {
        // ₹14,000 at Swiggy used ₹1,400 of the ₹1,500 cap: a ₹2,000 spend gets ₹100, not ₹200.
        val spent = listOf(Spend(1, 1, 14_000_00, "SWIGGY", earlier))
        val hdfc = rank("SWIGGY", 2000, spent).single { it.card == swiggyHdfc }
        assertEquals(100_00L, hdfc.cashbackPaise)
        assertEquals(100_00L, hdfc.capUnits!! - hdfc.capUsedUnits)
    }

    @Test
    fun `cycle replay gives each spend the rule that paid best at the time`() {
        val spends = listOf(
            Spend(1, 1, 10_000_00, "SWIGGY", earlier),     // 10% → ₹1,000
            Spend(2, 1, 10_000_00, "SWIGGY", earlier + 1), // ₹500 cap left on 10%; 1% base = ₹100 → ₹500
            Spend(3, 1, 2_000_00, "AMAZON", earlier + 2),  // Online 5% → ₹100
            Spend(4, 2, 1_000_00, "ZOMATO", earlier + 3),  // other card, not counted
        )
        val (earned, total) = Cashback.replayCycle(swiggyHdfc, rules, spends, table, now)
        assertEquals(1500_00L, earned.byRule[swiggy10.id])
        assertEquals(100_00L, earned.byRule[online5.id])
        assertEquals(22_000_00L, total.spentPaise)
        assertEquals(1600_00L, total.cashbackPaise)
    }

    @Test
    fun `cycle starts the day after the statement day`() {
        // Each case: statement day, today → first day of the current cycle.
        val cases = listOf(
            Triple(14, day(2026, 11, 20, 12), day(2026, 11, 15)),
            Triple(14, day(2026, 11, 14, 23), day(2026, 10, 15)), // statement day itself is still the old cycle
            Triple(14, day(2026, 11, 15), day(2026, 11, 15)),
            Triple(31, day(2026, 11, 20), day(2026, 11, 1)),      // Oct 31 statement
            Triple(31, day(2026, 12, 31), day(2026, 12, 1)),      // Nov has 30 days: statement on the 30th
            Triple(30, day(2027, 3, 5), day(2027, 3, 1)),         // Feb 2027 has 28 days
            Triple(5, day(2027, 1, 3), day(2026, 12, 6)),         // across a year
            Triple(null, day(2026, 11, 20, 12), day(2026, 11, 1)), // not set: calendar month
        )
        for ((statementDay, today, expected) in cases) {
            assertEquals("day $statementDay on $today", expected, Cashback.cycleStart(statementDay, today))
        }
    }

    @Test
    fun `cap resets the day after the statement day`() {
        // Statement on the 14th. ₹15,000 at Swiggy on the 14th used the old cycle's cap;
        // on the 20th the new cycle (from the 15th) pays the full 10% again.
        val hdfc14 = swiggyHdfc.copy(statementDay = 14)
        val onStatementDay = listOf(Spend(1, 1, 15_000_00, "SWIGGY", day(2026, 11, 14, 18)))
        val pick = rank("SWIGGY", 1000, onStatementDay, listOf(hdfc14)).single()
        assertEquals(100_00L, pick.cashbackPaise)
        assertEquals(1500_00L, pick.capUnits!! - pick.capUsedUnits)

        // The same spend on the 15th counts toward this cycle's cap.
        val afterStatement = listOf(Spend(1, 1, 15_000_00, "SWIGGY", day(2026, 11, 15, 9)))
        assertEquals(10_00L, rank("SWIGGY", 1000, afterStatement, listOf(hdfc14)).single().cashbackPaise)
    }

    @Test
    fun `chart puts each spend in one category`() {
        val table = listOf(
            MerchantCategory("zomato", "Online"), MerchantCategory("zomato", "Dining"),
            MerchantCategory("indian oil", "Fuel"), MerchantCategory("oil", "Groceries"),
        )
        // merchant → category: ties go alphabetically, the longer keyword wins, no match is Other.
        val cases = mapOf("ZOMATO LTD" to "Dining", "INDIAN OIL CORP" to "Fuel", "DMART" to "Other")
        for ((merchant, category) in cases) assertEquals(merchant, category, Cashback.mainCategory(merchant, table))
    }

    @Test
    fun `points are compared by their rupee value`() {
        // Regalia Gold: 2.5 points per ₹100 at ₹0.50 = 1.25%, beats a 1% cashback card.
        val regalia = Card(5, "Regalia Gold", "HDFC Bank", "5555", 2.5, rupeesPerPoint = 0.5)
        val plain = Card(6, "Plain 1%", "HDFC Bank", "6666", 1.0)
        val best = Cashback.rank(10_000_00, "DMART", listOf(plain, regalia), emptyList(), emptyList(), table, now).first()
        assertEquals(regalia, best.card)
        assertEquals("250 points", 250_00L, best.rewardUnits)
        assertEquals("worth ₹125", 125_00L, best.cashbackPaise)
    }

    @Test
    fun `rule needs the minimum order`() {
        val ornge = Card(7, "Ornge", "HDFC Bank", "7777", 1.0)
        val swiggy5 = Rule(70, 7, "Swiggy 5%", "swiggy", null, 5.0, null, minAmountPaise = 100_00)
        fun back(rupees: Long) = Cashback.rank(rupees * 100, "SWIGGY", listOf(ornge), listOf(swiggy5), emptyList(), table, now).single().cashbackPaise
        assertEquals("₹99 is under the minimum: 1% base", 99L, back(99))
        assertEquals("₹100 gets 5%", 5_00L, back(100))
    }

    @Test
    fun `a 0 rule means the spend earns nothing, even with a base rate`() {
        val scapia = Card(8, "Scapia", "Federal Bank", "8888", 10.0, rupeesPerPoint = 0.2)
        val fuel = Rule(80, 8, "Fuel", "", "Fuel", 0.0, null)
        val fuelTable = table + MerchantCategory("indian oil", "Fuel")
        val pick = Cashback.rank(2000_00, "INDIAN OIL", listOf(scapia), listOf(fuel), emptyList(), fuelTable, now).single()
        assertEquals(0L, pick.cashbackPaise)
        assertEquals(fuel, pick.rule)
        assertEquals("other spends still earn 2%", 40_00L,
            Cashback.rank(2000_00, "DMART", listOf(scapia), listOf(fuel), emptyList(), fuelTable, now).single().cashbackPaise)
    }

    @Test
    fun `Airtel cap follows the base cashback earned this cycle`() {
        val airtel = Card(9, "Airtel Axis", "Axis Bank", "9999", 1.0)
        val recharge = Rule(90, 9, "Airtel recharges 25%", "airtel", null, 25.0, null, capBaseMultiple = 2.0)
        // ₹20,000 of other spends this cycle → ₹200 base → recharges capped at ₹400.
        val other = listOf(Spend(1, 9, 20_000_00, "DMART", earlier))
        val pick = Cashback.rank(3000_00, "AIRTEL", listOf(airtel), listOf(recharge), other, table, now).single()
        assertEquals("25% of ₹3,000 is ₹750, capped at ₹400", 400_00L, pick.cashbackPaise)
        assertEquals(400_00L, pick.capUnits)
        // With no other spends there is no base yet, so only the 1% base applies.
        assertEquals(30_00L, Cashback.rank(3000_00, "AIRTEL", listOf(airtel), listOf(recharge), emptyList(), table, now).single().cashbackPaise)
    }

    @Test
    fun `debit cards are never suggested`() {
        val debit = Card(10, "HDFC Debit", "HDFC Bank", "1010", 0.0, isDebit = true)
        assertEquals(listOf(swiggyHdfc, axisAce).toSet(), rank("DMART", 1000, cards = cards + debit).map { it.card }.toSet())
    }

    @Test
    fun `points and rupees shown only for cards set to show points`() {
        val regalia = Card(5, "Regalia Gold", "HDFC Bank", "5555", 2.5, rupeesPerPoint = 0.5, showPoints = true)
        val neo = Card(6, "Neo", "Axis Bank", "6666", 0.5, rupeesPerPoint = 0.2)
        assertEquals("250 pts · ₹125", backText(125_00, 250_00, regalia))
        assertEquals("₹10", backText(10_00, 50_00, neo))
        assertEquals("₹50", backText(50_00, 50_00, swiggyHdfc))
    }

    @Test
    fun `international-only card is suggested only for international spends`() {
        val scapia = Card(11, "Scapia", "Federal Bank", "1111", 10.0, rupeesPerPoint = 0.2, internationalOnly = true)
        val withScapia = cards + scapia
        assertEquals(false, rank("DMART", 1000, cards = withScapia).any { it.card == scapia })
        val abroad = Cashback.rank(1000_00, "DMART", withScapia, rules, emptyList(), table, now, international = true)
        assertEquals(scapia, abroad.first().card)
    }

    @Test
    fun `merchant search offers known names, rule merchants and past spends`() {
        val spends = listOf(Spend(1, 1, 100_00, "SWIGGY FOOD", earlier), Spend(2, 1, 100_00, "Zomato Ltd", earlier))
        val names = { q: String -> Cashback.merchantSuggestions(q, table, rules, spends).map { it.name } }
        // Starts-with first, then contains; case doesn't matter; no duplicates.
        assertEquals(listOf("Zomato", "Zomato Ltd"), names("zom"))
        assertEquals(listOf("Swiggy", "SWIGGY FOOD"), names("SWIG"))
        assertEquals(listOf("Ola"), names("ola"))
        // Blank lists the known keywords only.
        assertEquals(listOf("Amazon", "Ola", "Swiggy", "Zomato"), names(""))
        assertEquals(setOf("Online"), Cashback.merchantSuggestions("zomato", table, rules, spends).first().categories)
    }

    @Test
    fun `Airtel bill paid early counts the whole cycle's base`() {
        val airtel = Card(9, "Airtel Axis", "Axis Bank", "9999", 1.0)
        val recharge = Rule(90, 9, "Airtel recharges 25%", "airtel", null, 25.0, null, capBaseMultiple = 2.0)
        // Bill on the 16th, ₹20,000 of other spends on the 18th: the bill still earns 25% (cap ₹400).
        val spends = listOf(Spend(1, 9, 825_00, "AIRTEL", day(2026, 11, 16)), Spend(2, 9, 20_000_00, "DMART", day(2026, 11, 18)))
        val total = Cashback.replayCycle(airtel, listOf(recharge), spends, table, now).second
        assertEquals("₹206.25 on the bill + ₹200 base", 406_25L, total.cashbackPaise)
    }

    @Test
    fun `Best card says how much spend unlocks a base-tied rate`() {
        val airtel = Card(9, "Airtel Axis", "Axis Bank", "9999", 1.0)
        val recharge = Rule(90, 9, "Airtel recharges 25%", "airtel", null, 25.0, null, capBaseMultiple = 2.0)
        val pick = Cashback.rank(825_00, "AIRTEL", listOf(airtel), listOf(recharge), emptyList(), table, now).single()
        assertEquals("today: 1% base only", 8_25L, pick.cashbackPaise)
        val unlock = pick.unlock!!
        assertEquals("25% of ₹825", 206_25L, unlock.valuePaise)
        assertEquals("₹103.13 base needed → ₹10,313 at 1%", 10_313_00L, unlock.moreSpendPaise)
        // Once enough base is in, no hint and the full rate applies.
        val after = Cashback.rank(825_00, "AIRTEL", listOf(airtel), listOf(recharge), listOf(Spend(1, 9, 11_000_00, "DMART", earlier)), table, now).single()
        assertEquals(206_25L, after.cashbackPaise)
        assertEquals(null, after.unlock)
    }

    @Test
    fun `Regalia earns only per full 200 rupees`() {
        val regalia = Card(5, "Regalia Gold", "HDFC Bank", "5555", 2.5, rupeesPerPoint = 0.5, earnBlockRupees = 200)
        // Each case: ₹ spent → points earned (5 per full ₹200).
        val cases = mapOf(199L to 0L, 200L to 5L, 1150L to 25L, 1200L to 30L)
        for ((rupees, points) in cases) {
            val pick = Cashback.rank(rupees * 100, "DMART", listOf(regalia), emptyList(), emptyList(), table, now).single()
            assertEquals("₹$rupees", points * 100, pick.rewardUnits)
        }
    }

    @Test
    fun `jewellery merchants fall in Jewellery`() {
        for (merchant in listOf("_TITAN COMPANY LIM.", "TANISHQ JEWELLERY", "SRI LAKSHMI JEWELLERS", "CARATLANE TRADING"))
            assertEquals(merchant, true, "Jewellery" in Cashback.categoriesOf(merchant, Starter.merchants))
    }

    @Test
    fun `abroad, cards are ranked by reward minus forex fee`() {
        val scapia = Card(11, "Scapia", "Federal Bank", "1111", 10.0, rupeesPerPoint = 0.2, internationalOnly = true,
            forexMarkupPercent = 0.0, rewardsAbroad = false)
        val regalia = Card(12, "Regalia Gold", "HDFC Bank", "2222", 2.5, rupeesPerPoint = 0.5, forexMarkupPercent = 2.0)
        val plain = Card(13, "Plain", "Axis Bank", "3333", 1.0) // 3.5% fee
        val picks = Cashback.rank(1000_00, "HOTEL PARIS", listOf(plain, regalia, scapia), emptyList(), emptyList(), table, now, international = true)
        assertEquals(listOf(scapia, regalia, plain), picks.map { it.card })
        assertEquals("no coins abroad, no fee", 0L, picks[0].cashbackPaise)
        assertEquals("₹12.50 back − ₹20 fee", -7_50L, picks[1].cashbackPaise)
        assertEquals("₹10 back − ₹35 fee", -25_00L, picks[2].cashbackPaise)
        // At home the fee doesn't apply.
        assertEquals(12_50L, Cashback.rank(1000_00, "HOTEL", listOf(regalia), emptyList(), emptyList(), table, now).single().cashbackPaise)
    }

    @Test
    fun `cycle starts the day after the latest statement in the PDF`() {
        // Statement day says 20, but this month's statement was dated the 19th (e.g. a holiday).
        val card = swiggyHdfc.copy(statementDay = 20, lastStatementEpochDay = java.time.LocalDate.of(2026, 11, 19).toEpochDay())
        assertEquals(day(2026, 11, 20), Cashback.cycleStart(card, now))
        // A statement older than 45 days is stale: fall back to the statement day.
        val stale = card.copy(lastStatementEpochDay = java.time.LocalDate.of(2026, 8, 19).toEpochDay())
        assertEquals("statement day 20, today 20 Nov: cycle began 21 Oct", day(2026, 10, 21), Cashback.cycleStart(stale, now))
    }

    @Test
    fun `cheat sheet shows the best card per category, international last`() {
        val ultimo = Card(14, "Ultimo", "HDFC Bank", "4444", 1.0)
        val bills10 = Rule(140, 14, "Bills 10%", "", "Bills", 10.0, 1000_00)
        val scapia = Card(15, "Scapia", "Federal Bank", "5555", 10.0, rupeesPerPoint = 0.2, internationalOnly = true, forexMarkupPercent = 0.0, rewardsAbroad = false)
        val sheet = Cashback.cheatSheet(1000_00, listOf(axisAce, ultimo, scapia), rules + bills10, emptyList(), table, now).toMap()
        assertEquals(Cashback.cheatSheetCategories + Cashback.INTERNATIONAL, sheet.keys.toList())
        assertEquals(ultimo, sheet["Bills"]?.card)
        assertEquals(100_00L, sheet["Bills"]?.cashbackPaise)
        assertEquals("plain dining: Axis Ace's 1.5% base", axisAce, sheet["Dining"]?.card)
        assertEquals(scapia, sheet[Cashback.INTERNATIONAL]?.card)
    }
}
