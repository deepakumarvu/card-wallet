package app.cardwallet

import java.util.Calendar

/** Works out cashback per card, counting monthly caps already used this month. */
object Cashback {

    /**
     * Reward for one spend, in the card's unit ×100 (paise for cashback, 1/100 point for points):
     * which rule paid it (null = the card's base rate) and how much.
     */
    data class Earned(val ruleId: Long?, val units: Long)

    /** One card's answer for "where should I pay?". */
    data class Pick(
        val card: Card,
        /** Reward turned into ₹ (paise), so cards with points and cashback compare fairly. */
        val cashbackPaise: Long,
        /** Reward in the card's own unit ×100. */
        val rewardUnits: Long,
        /** The rule that pays, or null when only the base rate applies. */
        val rule: Rule?,
        /** Cap and cap used on [rule] this cycle before this spend (card units ×100); null when it has no cap. */
        val capUnits: Long?,
        val capUsedUnits: Long,
        /** A better rule that would have matched but whose cap is used up this cycle. */
        val cappedOut: Rule?,
        /** A base-tied rule that would pay more once more base is earned, and what unlocks it. */
        val unlock: Unlock? = null,
    )

    /** [rule] would pay [valuePaise] once [moreSpendPaise] more of other spends go on this card this cycle. */
    data class Unlock(val rule: Rule, val valuePaise: Long, val moreSpendPaise: Long)

    /** Per-card totals for the current statement cycle; cashback in ₹ (paise). */
    data class CycleTotal(val spentPaise: Long, val cashbackPaise: Long, val rewardUnits: Long = 0)

    /** Earnings so far this cycle: per capped rule, and from the base rate (card units ×100). */
    data class CycleEarned(val byRule: Map<Long, Long>, val base: Long)

    /** True when a word in [merchant] starts with [keyword]: "ola" matches "OLACABS", not "COCA COLA". */
    fun hasKeyword(merchant: String, keyword: String): Boolean =
        keyword.isNotBlank() && keywordPattern(keyword.trim()).containsMatchIn(merchant)

    // Patterns are built once per keyword: matching runs for every spend × keyword × card on each screen.
    private val patterns = java.util.concurrent.ConcurrentHashMap<String, Regex>()

    // Word start = after any non-letter/digit, so "_TITAN" and "RSP*SWIGGY" match too.
    private fun keywordPattern(keyword: String): Regex = patterns.getOrPut(keyword) {
        Regex("""(?<![\p{L}\p{N}])""" + Regex.escape(keyword), RegexOption.IGNORE_CASE)
    }

    fun categoriesOf(merchant: String, table: List<MerchantCategory>): Set<String> =
        table.filter { hasKeyword(merchant, it.keyword) }.map { it.category }.toSet()

    /**
     * One category per spend, for the chart: the longest matching keyword wins, ties go
     * alphabetically ("zomato" is Dining and Online → Dining). No match → "Other".
     */
    fun mainCategory(merchant: String, table: List<MerchantCategory>): String =
        table.filter { hasKeyword(merchant, it.keyword) }
            .sortedWith(compareByDescending<MerchantCategory> { it.keyword.length }.thenBy { it.category })
            .firstOrNull()?.category ?: "Other"

    /** A merchant to offer in the search dropdown, with the categories it falls in. */
    data class Suggestion(val name: String, val categories: Set<String>)

    /**
     * Merchants matching [query] for the search dropdown: known keywords, merchants named in card
     * rules, and merchants from past spends. Names starting with the query come first; a blank
     * query lists every known keyword.
     */
    fun merchantSuggestions(query: String, table: List<MerchantCategory>, rules: List<Rule>, spends: List<Spend>): List<Suggestion> {
        fun titleCase(s: String) = s.trim().split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
        val known = (table.map { it.keyword } + rules.flatMap { it.keywords.split(',') }).filter { it.isNotBlank() }.map(::titleCase)
        val all = (known + spends.map { it.merchant.trim() }).distinctBy { it.lowercase() }
        val q = query.trim()
        val hits = if (q.isEmpty()) known.distinctBy { it.lowercase() } else all.filter { it.contains(q, ignoreCase = true) }
        return hits.sortedWith(compareBy<String> { !it.startsWith(q, ignoreCase = true) }.thenBy { it.lowercase() })
            .map { Suggestion(it, categoriesOf(it, table)) }
    }

    fun matches(rule: Rule, merchant: String, categories: Set<String>): Boolean =
        rule.keywords.split(',').any { hasKeyword(merchant, it) } ||
            (rule.category != null && rule.category in categories)

    fun percentOf(paise: Long, percent: Double): Long = (paise * percent / 100).toLong()

    /** The part of a spend that earns: whole blocks only for cards that pay per full block (Regalia: ₹1,150 → ₹1,000). */
    fun earning(amountPaise: Long, card: Card): Long =
        card.earnBlockRupees?.takeIf { it > 0 }?.let { block -> amountPaise / (block * 100L) * (block * 100L) } ?: amountPaise

    /** Card units ×100 → ₹ in paise. */
    fun toPaise(units: Long, card: Card): Long = (units * card.rupeesPerPoint).toLong()

    /** Value back as a % of the spend: 2.5 points per ₹100 at ₹0.50 → 1.25. */
    fun valuePercent(rate: Double, card: Card): Double = rate * card.rupeesPerPoint

    /** This cycle's cap for [rule]: the fixed cap, the base-tied cap, or the lower of both. Null = no cap. */
    fun capFor(rule: Rule, baseEarned: Long): Long? {
        val tied = rule.capBaseMultiple?.let { (baseEarned * it).toLong() }
        return listOfNotNull(rule.monthlyCapPaise, tied).minOrNull()
    }

    private fun applies(rule: Rule, card: Card, amountPaise: Long, merchant: String, categories: Set<String>) =
        rule.cardId == card.id && amountPaise >= (rule.minAmountPaise ?: 0) && matches(rule, merchant, categories)

    /**
     * What a spend adds to the card's base earnings: the base rate, unless a rule covers it
     * (a higher-rate rule, or a 0 rule). Base-tied caps grow with this.
     */
    fun baseEarning(card: Card, rules: List<Rule>, amountPaise: Long, merchant: String, categories: Set<String>): Long =
        if (rules.any { applies(it, card, amountPaise, merchant, categories) && (it.ratePercent > card.baseRatePercent || it.ratePercent == 0.0) }) 0
        else percentOf(earning(amountPaise, card), card.baseRatePercent)

    /** Best reward for one spend on one card, given what was earned earlier this cycle. */
    fun best(card: Card, rules: List<Rule>, amountPaise: Long, merchant: String, categories: Set<String>, earned: CycleEarned): Earned {
        val matching = rules.filter { applies(it, card, amountPaise, merchant, categories) }
        // A 0 rule means "earns nothing here" (e.g. rent, fuel), and beats the base rate.
        matching.firstOrNull { it.ratePercent == 0.0 }?.let { return Earned(it.id, 0) }
        var best = Earned(null, percentOf(earning(amountPaise, card), card.baseRatePercent))
        for (rule in matching) {
            var units = percentOf(earning(amountPaise, card), rule.ratePercent)
            capFor(rule, earned.base)?.let { cap -> units = minOf(units, (cap - (earned.byRule[rule.id] ?: 0)).coerceAtLeast(0)) }
            if (units > best.units) best = Earned(rule.id, units)
        }
        return best
    }

    /**
     * Replays the spends on [card] in its current cycle in time order, so each spend takes the
     * rule that paid best at that moment. Returns what was earned, and the cycle total.
     */
    fun replayCycle(
        card: Card,
        rules: List<Rule>,
        spends: List<Spend>,
        table: List<MerchantCategory>,
        now: Long = System.currentTimeMillis(),
    ): Pair<CycleEarned, CycleTotal> {
        val start = cycleStart(card.statementDay, now)
        val cycleSpends = spends.filter { it.cardId == card.id && it.timeMillis >= start }.sortedBy { it.timeMillis }
        // Base-tied caps follow the whole cycle's base, as the bank works it out at statement time:
        // a bill paid on day 1 still gets its rate once enough other spends land later.
        val base = cycleSpends.sumOf { baseEarning(card, rules, it.amountPaise, it.merchant, categoriesOf(it.merchant, table)) }
        val byRule = mutableMapOf<Long, Long>()
        var spent = 0L
        var units = 0L
        for (s in cycleSpends) {
            val e = best(card, rules, s.amountPaise, s.merchant, categoriesOf(s.merchant, table), CycleEarned(byRule, base))
            if (e.ruleId != null) byRule[e.ruleId] = (byRule[e.ruleId] ?: 0) + e.units
            spent += s.amountPaise
            units += e.units
        }
        return CycleEarned(byRule, base) to CycleTotal(spent, toPaise(units, card), units)
    }

    /**
     * For a base-tied rule held back by its cap: the full reward it would pay, and how much more
     * spend at the base rate this cycle would make room for it. Null when nothing is held back.
     */
    private fun unlockFor(
        card: Card, rules: List<Rule>, amountPaise: Long, merchant: String, categories: Set<String>, earned: CycleEarned, now: Earned,
    ): Unlock? {
        if (card.baseRatePercent <= 0) return null
        return rules.filter { it.capBaseMultiple != null && it.ratePercent > 0 && applies(it, card, amountPaise, merchant, categories) }
            .mapNotNull { rule ->
                var full = percentOf(earning(amountPaise, card), rule.ratePercent)
                rule.monthlyCapPaise?.let { full = minOf(full, (it - (earned.byRule[rule.id] ?: 0)).coerceAtLeast(0)) }
                if (full <= now.units) return@mapNotNull null
                val baseNeeded = ((earned.byRule[rule.id] ?: 0) + full) / rule.capBaseMultiple!! - earned.base
                // Rounded up to the whole rupee.
                val spendNeeded = Math.ceil(baseNeeded / card.baseRatePercent).toLong() * 100
                Unlock(rule, toPaise(full, card), spendNeeded.coerceAtLeast(0))
            }.maxByOrNull { it.valuePaise }
    }

    /**
     * Every credit card ranked by value back for spending [amountPaise] at [merchant] right now.
     * Debit cards are left out, and so are international-only cards unless [international] is set.
     */
    fun rank(
        amountPaise: Long,
        merchant: String,
        cards: List<Card>,
        rules: List<Rule>,
        spends: List<Spend>,
        table: List<MerchantCategory>,
        now: Long = System.currentTimeMillis(),
        international: Boolean = false,
    ): List<Pick> {
        val categories = categoriesOf(merchant, table)
        return cards.filter { !it.isDebit && (international || !it.internationalOnly) }.map { card ->
            val earned = replayCycle(card, rules, spends, table, now).first
            val e = best(card, rules, amountPaise, merchant, categories, earned)
            val rule = rules.find { it.id == e.ruleId }
            val appliedRate = rule?.ratePercent ?: card.baseRatePercent
            val cappedOut = rules.filter {
                val cap = capFor(it, earned.base)
                it.ratePercent > appliedRate && cap != null && (earned.byRule[it.id] ?: 0) >= cap &&
                    applies(it, card, amountPaise, merchant, categories)
            }.maxByOrNull { it.ratePercent }
            Pick(card, toPaise(e.units, card), e.units, rule, rule?.let { capFor(it, earned.base) },
                rule?.let { earned.byRule[it.id] } ?: 0, cappedOut, unlockFor(card, rules, amountPaise, merchant, categories, earned, e))
        }.sortedByDescending { it.cashbackPaise }
    }

    /**
     * Start of the current cycle (phone's time zone): the day after the statement day, or the 1st
     * when [statementDay] is null. A day past the month's end means its last day: 31 → 30 Nov.
     */
    fun cycleStart(statementDay: Int?, now: Long = System.currentTimeMillis()): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        if (statementDay == null) return cal.apply { set(Calendar.DAY_OF_MONTH, 1) }.timeInMillis
        fun statementIn(c: Calendar) = minOf(statementDay, c.getActualMaximum(Calendar.DAY_OF_MONTH))
        if (cal.get(Calendar.DAY_OF_MONTH) <= statementIn(cal)) {
            cal.set(Calendar.DAY_OF_MONTH, 1)
            cal.add(Calendar.MONTH, -1)
        }
        cal.set(Calendar.DAY_OF_MONTH, statementIn(cal))
        cal.add(Calendar.DAY_OF_MONTH, 1)
        return cal.timeInMillis
    }

    /** First day of the next cycle, when caps reset. */
    fun nextCycleStart(statementDay: Int?, now: Long = System.currentTimeMillis()): Long {
        val start = cycleStart(statementDay, now)
        val day = Calendar.getInstance().apply { timeInMillis = start }
        while (true) {
            day.add(Calendar.DAY_OF_MONTH, 1)
            val next = cycleStart(statementDay, day.timeInMillis)
            if (next > start) return next
        }
    }
}
