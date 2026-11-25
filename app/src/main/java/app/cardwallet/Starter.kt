package app.cardwallet

/**
 * Starter cashback rules for popular Indian cards. Banks change terms often and this list
 * works offline, so these can go stale: check your card's current terms and edit them.
 */
object Starter {

    data class RuleTemplate(val label: String, val keywords: String, val category: String?, val ratePercent: Double, val capRupees: Long?)
    data class CardTemplate(val name: String, val bank: String, val baseRatePercent: Double, val rules: List<RuleTemplate>)

    val cards = listOf(
        CardTemplate(
            "Swiggy HDFC", "HDFC Bank", 1.0, listOf(
                RuleTemplate("Swiggy 10%", "swiggy", null, 10.0, 1500),
                RuleTemplate("Online 5%", "", "Online", 5.0, 1500),
            ),
        ),
        CardTemplate(
            "HDFC Millennia", "HDFC Bank", 1.0, listOf(
                RuleTemplate("Partner brands 5%", "amazon, flipkart, myntra, swiggy, zomato, uber, bookmyshow", null, 5.0, 1000),
            ),
        ),
        CardTemplate(
            "SBI Cashback", "State Bank of India", 1.0, listOf(
                RuleTemplate("Online 5%", "", "Online", 5.0, 5000),
            ),
        ),
        CardTemplate(
            "Axis Ace", "Axis Bank", 1.5, listOf(
                RuleTemplate("Swiggy/Zomato/Ola 4%", "swiggy, zomato, ola", null, 4.0, 500),
            ),
        ),
        CardTemplate(
            "Amazon Pay ICICI", "ICICI Bank", 1.0, listOf(
                RuleTemplate("Amazon 5% (Prime)", "amazon", null, 5.0, null),
            ),
        ),
        CardTemplate(
            "Flipkart Axis", "Axis Bank", 1.0, listOf(
                RuleTemplate("Flipkart 5%", "flipkart", null, 5.0, null),
            ),
        ),
    )

    val merchants = listOf(
        "zomato" to "Dining", "swiggy" to "Dining", "eatsure" to "Dining",
        "zomato" to "Online", "swiggy" to "Online", "amazon" to "Online", "flipkart" to "Online",
        "myntra" to "Online", "ajio" to "Online", "nykaa" to "Online", "bookmyshow" to "Online",
        "blinkit" to "Groceries", "zepto" to "Groceries", "bigbasket" to "Groceries", "dmart" to "Groceries",
        "uber" to "Travel", "ola" to "Travel", "irctc" to "Travel", "makemytrip" to "Travel",
        "indian oil" to "Fuel", "iocl" to "Fuel", "hpcl" to "Fuel", "bpcl" to "Fuel",
    ).map { (keyword, category) -> MerchantCategory(keyword, category) }

    suspend fun addCard(dao: WalletDao, template: CardTemplate, last4: String) {
        val cardId = dao.save(Card(name = template.name, bank = template.bank, last4 = last4, baseRatePercent = template.baseRatePercent))
        template.rules.forEach {
            dao.save(Rule(cardId = cardId, label = it.label, keywords = it.keywords, category = it.category, ratePercent = it.ratePercent, monthlyCapPaise = it.capRupees?.times(100)))
        }
    }
}
