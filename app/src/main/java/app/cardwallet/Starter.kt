package app.cardwallet

/**
 * Starter rules for the cards in this wallet, as of Nov 2026. Banks change terms often and this
 * list works offline, so these can go stale: check your card's current terms and edit them.
 * Rates are per ₹100 in the card's unit (₹ for cashback cards, points for points cards).
 */
object Starter {

    data class RuleTemplate(
        val label: String,
        val keywords: String = "",
        val category: String? = null,
        val rate: Double,
        /** Cap per cycle in the card's unit (₹ or points). */
        val cap: Long? = null,
        val minRupees: Long? = null,
        val capBaseMultiple: Double? = null,
    )

    data class CardTemplate(
        val name: String,
        val bank: String,
        val baseRate: Double,
        val rules: List<RuleTemplate> = emptyList(),
        val rupeesPerPoint: Double = 1.0,
        val isDebit: Boolean = false,
        val showPoints: Boolean = false,
        val network: String? = null,
        /** Index into [cardColors]. */
        val color: Int? = null,
        val internationalOnly: Boolean = false,
        val earnBlockRupees: Int? = null,
    )

    val cards = listOf(
        CardTemplate("Swiggy HDFC Ornge", "HDFC Bank", 1.0, color = 12, rules = listOf(
            RuleTemplate("Swiggy & online 5%", keywords = "swiggy", category = "Online", rate = 5.0, cap = 1500, minRupees = 100),
        )),
        // 5 points per ₹200; ₹0.50 a point on flights.
        CardTemplate("HDFC Regalia Gold", "HDFC Bank", 2.5, rupeesPerPoint = 0.5, showPoints = true, network = "Mastercard", color = 13, earnBlockRupees = 200),
        CardTemplate("HDFC PhonePe Ultimo", "HDFC Bank", 1.0, network = "RuPay", color = 14, rules = listOf(
            RuleTemplate("Bills & recharges 10%", category = "Bills", rate = 10.0, cap = 1000),
            RuleTemplate("Online shopping 5%", category = "Online", rate = 5.0, cap = 500),
        )),
        // Axis EDGE points at ₹0.20: Neo 1, My Zone 4, Privilege 10 per ₹200.
        CardTemplate("Axis Neo", "Axis Bank", 0.5, rupeesPerPoint = 0.2, network = "Visa", color = 15),
        CardTemplate("Axis My Zone", "Axis Bank", 2.0, rupeesPerPoint = 0.2, network = "Visa", color = 15),
        CardTemplate("Axis Privilege (Amex)", "Axis Bank", 5.0, rupeesPerPoint = 0.2, network = "Amex", color = 2),
        CardTemplate("Airtel Axis", "Axis Bank", 1.0, network = "RuPay", color = 16, rules = listOf(
            RuleTemplate("Airtel recharges 25%", keywords = "airtel", rate = 25.0, capBaseMultiple = 2.0),
            RuleTemplate("Electricity & other bills 10%", category = "Bills", rate = 10.0, capBaseMultiple = 1.0),
        )),
        // Scapia coins: 5 coins = ₹1. Visa card: 10 coins per ₹100.
        CardTemplate("Scapia", "Federal Bank", 10.0, network = "Visa", color = 18, internationalOnly = true, rules = listOf(
            RuleTemplate("Travel in Scapia app 4%", keywords = "scapia", rate = 20.0),
            RuleTemplate("Utility bills 2%", category = "Bills", rate = 10.0, cap = 2000), // first ₹20,000 a month
            RuleTemplate("Fuel", category = "Fuel", rate = 0.0),
            RuleTemplate("Rent", category = "Rent", rate = 0.0),
            RuleTemplate("Insurance", category = "Insurance", rate = 0.0),
        ), rupeesPerPoint = 0.2),
        CardTemplate("Amazon Pay ICICI", "ICICI Bank", 1.0, network = "Visa", color = 9, rules = listOf(
            RuleTemplate("Amazon 5% (Prime)", keywords = "amazon", rate = 5.0),
            RuleTemplate("Partners 2%", keywords = "swiggy, zomato, uber, bookmyshow", rate = 2.0),
        )),
        CardTemplate("Indian Bank RuPay Platinum", "Indian Bank", 0.0, isDebit = true, network = "RuPay", color = 1),
        CardTemplate("HDFC Platinum Debit", "HDFC Bank", 0.0, isDebit = true, color = 17),
    )

    /** Added after the first list; existing installs get these once (see MainActivity). */
    val jewellery = listOf(
        "jewel", "tanishq", "titan", "caratlane", "malabar gold", "kalyan jewellers", "joyalukkas", "bluestone", "senco", "grt jewellers", "pc jeweller",
    ).map { MerchantCategory(it, "Jewellery") }

    val merchants = listOf(
        "zomato" to "Dining", "swiggy" to "Dining", "eatsure" to "Dining",
        "zomato" to "Online", "swiggy" to "Online", "amazon" to "Online", "flipkart" to "Online",
        "myntra" to "Online", "ajio" to "Online", "nykaa" to "Online", "bookmyshow" to "Online",
        "blinkit" to "Groceries", "zepto" to "Groceries", "bigbasket" to "Groceries", "dmart" to "Groceries", "instamart" to "Groceries",
        "uber" to "Travel", "ola" to "Travel", "irctc" to "Travel", "makemytrip" to "Travel", "goibibo" to "Travel",
        "cleartrip" to "Travel", "indigo" to "Travel", "air india" to "Travel", "redbus" to "Travel",
        "indian oil" to "Fuel", "iocl" to "Fuel", "hpcl" to "Fuel", "bpcl" to "Fuel", "shell" to "Fuel", "nayara" to "Fuel",
        "electricity" to "Bills", "bescom" to "Bills", "tata power" to "Bills", "adani electricity" to "Bills", "msedcl" to "Bills",
        "tneb" to "Bills", "bses" to "Bills", "airtel" to "Bills", "jio" to "Bills", "act fibernet" to "Bills", "hathway" to "Bills",
        "rent" to "Rent", "nobroker" to "Rent",
        "insurance" to "Insurance", "lic" to "Insurance", "policybazaar" to "Insurance", "acko" to "Insurance",
    ).map { (keyword, category) -> MerchantCategory(keyword, category) } + jewellery


    /**
     * One-time fill for cards added before colours and networks existed: a card named like a
     * template gets that template's colour, network and points display, only where still unset.
     */
    suspend fun fillLooks(dao: WalletDao) {
        dao.allCards().forEach { card -> withLooks(card).takeIf { it != card }?.let { dao.saveLogged(it) } }
    }

    /** Colours the starter list used to give, now changed: name → (old, new). Applied once, only if still the old one. */
    private val recoloured = mapOf("Scapia" to (6 to 18))

    suspend fun applyRecolours(dao: WalletDao) {
        dao.allCards().forEach { card -> recolour(card).takeIf { it != card }?.let { dao.saveLogged(it) } }
    }

    fun recolour(card: Card): Card {
        val (old, new) = recoloured[card.name] ?: return card
        return if (card.colorIndex == old) card.copy(colorIndex = new) else card
    }

    fun withLooks(card: Card): Card {
        val t = cards.find { it.name == card.name } ?: return card
        return card.copy(colorIndex = card.colorIndex ?: t.color, network = card.network ?: t.network, showPoints = card.showPoints || t.showPoints,
            internationalOnly = card.internationalOnly || t.internationalOnly, earnBlockRupees = card.earnBlockRupees ?: t.earnBlockRupees)
    }

    /** Rules from the card's starter template that it no longer has (matched by name). */
    fun missingRules(card: Card, rules: List<Rule>): List<RuleTemplate> {
        val t = cards.find { it.name == card.name } ?: return emptyList()
        val have = rules.filter { it.cardId == card.id }.map { it.label }.toSet()
        return t.rules.filter { it.label !in have }
    }

    suspend fun restoreRules(dao: WalletDao, card: Card, missing: List<RuleTemplate>) = missing.forEach {
        dao.saveLogged(Rule(cardId = card.id, label = it.label, keywords = it.keywords, category = it.category, ratePercent = it.rate,
            monthlyCapPaise = it.cap?.times(100), minAmountPaise = it.minRupees?.times(100), capBaseMultiple = it.capBaseMultiple))
    }

    suspend fun addCard(dao: WalletDao, template: CardTemplate, last4: String, statementDay: Int?) {
        val cardId = dao.saveLogged(
            Card(name = template.name, bank = template.bank, last4 = last4, baseRatePercent = template.baseRate,
                statementDay = statementDay.takeIf { !template.isDebit }, rupeesPerPoint = template.rupeesPerPoint, isDebit = template.isDebit,
                sortOrder = dao.nextSortOrder(), showPoints = template.showPoints,
                network = template.network, colorIndex = template.color, internationalOnly = template.internationalOnly,
                earnBlockRupees = template.earnBlockRupees),
        )
        template.rules.forEach {
            dao.saveLogged(Rule(cardId = cardId, label = it.label, keywords = it.keywords, category = it.category, ratePercent = it.rate,
                monthlyCapPaise = it.cap?.times(100), minAmountPaise = it.minRupees?.times(100), capBaseMultiple = it.capBaseMultiple))
        }
    }
}
