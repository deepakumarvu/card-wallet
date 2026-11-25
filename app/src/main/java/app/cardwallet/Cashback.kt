package app.cardwallet

import java.util.Calendar

/** Works out cashback per card, counting monthly caps already used this month. */
object Cashback {

    /** Cashback for one spend: which rule paid it (null = the card's base rate) and how much. */
    data class Earned(val ruleId: Long?, val paise: Long)

    /** One card's answer for "where should I pay?". */
    data class Pick(
        val card: Card,
        val cashbackPaise: Long,
        /** The rule that pays, or null when only the base rate applies. */
        val rule: Rule?,
        /** Cap left on [rule] before this spend; null when it has no cap. */
        val capLeftPaise: Long?,
        /** A better rule that would have matched but whose cap is used up this month. */
        val cappedOut: Rule?,
    )

    /** Per-card totals for this month. */
    data class MonthTotal(val spentPaise: Long, val cashbackPaise: Long)

    /** True when a word in [merchant] starts with [keyword]: "ola" matches "OLACABS", not "COCA COLA". */
    fun hasKeyword(merchant: String, keyword: String): Boolean =
        keyword.isNotBlank() && Regex("""\b""" + Regex.escape(keyword.trim()), RegexOption.IGNORE_CASE).containsMatchIn(merchant)

    fun categoriesOf(merchant: String, table: List<MerchantCategory>): Set<String> =
        table.filter { hasKeyword(merchant, it.keyword) }.map { it.category }.toSet()

    fun matches(rule: Rule, merchant: String, categories: Set<String>): Boolean =
        rule.keywords.split(',').any { hasKeyword(merchant, it) } ||
            (rule.category != null && rule.category in categories)

    fun percentOf(paise: Long, percent: Double): Long = (paise * percent / 100).toLong()

    /** Best cashback for one spend on one card, given what each capped rule already paid this month. */
    fun best(
        card: Card,
        rules: List<Rule>,
        amountPaise: Long,
        merchant: String,
        categories: Set<String>,
        earnedByRule: Map<Long, Long>,
    ): Earned {
        var best = Earned(null, percentOf(amountPaise, card.baseRatePercent))
        for (rule in rules) {
            if (rule.cardId != card.id || !matches(rule, merchant, categories)) continue
            var paise = percentOf(amountPaise, rule.ratePercent)
            rule.monthlyCapPaise?.let { cap -> paise = minOf(paise, (cap - (earnedByRule[rule.id] ?: 0)).coerceAtLeast(0)) }
            if (paise > best.paise) best = Earned(rule.id, paise)
        }
        return best
    }

    /**
     * Replays this month's spends on [card] in time order, so each spend takes the rule that
     * paid best at that moment. Returns cashback paid per rule, and the month total.
     */
    fun replayMonth(
        card: Card,
        rules: List<Rule>,
        monthSpends: List<Spend>,
        table: List<MerchantCategory>,
    ): Pair<Map<Long, Long>, MonthTotal> {
        val earned = mutableMapOf<Long, Long>()
        var spent = 0L
        var cashback = 0L
        for (s in monthSpends.filter { it.cardId == card.id }.sortedBy { it.timeMillis }) {
            val e = best(card, rules, s.amountPaise, s.merchant, categoriesOf(s.merchant, table), earned)
            e.ruleId?.let { earned[it] = (earned[it] ?: 0) + e.paise }
            spent += s.amountPaise
            cashback += e.paise
        }
        return earned to MonthTotal(spent, cashback)
    }

    /** Every card ranked by cashback for spending [amountPaise] at [merchant] right now. */
    fun rank(
        amountPaise: Long,
        merchant: String,
        cards: List<Card>,
        rules: List<Rule>,
        monthSpends: List<Spend>,
        table: List<MerchantCategory>,
    ): List<Pick> {
        val categories = categoriesOf(merchant, table)
        return cards.map { card ->
            val earned = replayMonth(card, rules, monthSpends, table).first
            val e = best(card, rules, amountPaise, merchant, categories, earned)
            val rule = rules.find { it.id == e.ruleId }
            val appliedRate = rule?.ratePercent ?: card.baseRatePercent
            val cappedOut = rules.filter {
                it.cardId == card.id && it.ratePercent > appliedRate && it.monthlyCapPaise != null &&
                    (earned[it.id] ?: 0) >= it.monthlyCapPaise && matches(it, merchant, categories)
            }.maxByOrNull { it.ratePercent }
            Pick(card, e.paise, rule, rule?.monthlyCapPaise?.let { it - (earned[rule.id] ?: 0) }, cappedOut)
        }.sortedByDescending { it.cashbackPaise }
    }

    /** Spends since the 1st of this calendar month (phone's time zone). */
    fun thisMonth(spends: List<Spend>, now: Long = System.currentTimeMillis()): List<Spend> {
        val start = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        return spends.filter { it.timeMillis >= start }
    }
}
