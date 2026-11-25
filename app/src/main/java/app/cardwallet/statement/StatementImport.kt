package app.cardwallet.statement

import android.content.Context
import app.cardwallet.Card
import app.cardwallet.Spend
import app.cardwallet.StatementRecord
import app.cardwallet.WalletDao
import app.cardwallet.log
import app.cardwallet.rupees
import app.cardwallet.saveLogged
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

/**
 * Card statement PDF → spends on the right card. Runs entirely on the phone: the PDF is unlocked
 * and read in memory, never written to disk.
 */
object StatementImport {

    /** What happened to one PDF, in words for the screen and the activity log. */
    data class Outcome(val message: String, val added: Int = 0, val locked: Boolean = false)

    private const val DAY = 24 * 60 * 60 * 1000L

    /** Which issuer's layout to read the text with, from the bank name printed in it. */
    fun issuerOf(text: String): String? = listOf(
        "HDFC Bank" to "HDFC", "ICICI Bank" to "ICICI", "Axis Bank" to "Axis Bank", "SBI Card" to "SBI Card",
        "Kotak" to "Kotak", "IDFC FIRST" to "IDFC", "Yes Bank" to "YES BANK", "Amex" to "American Express",
    ).firstOrNull { (_, word) -> text.contains(word, ignoreCase = true) }?.first

    /**
     * The card a statement belongs to: by its last 4 (or old digits); else, among that bank's cards,
     * the one whose own name appears in the statement ("Regalia", "Ultimo"); else the bank's only card.
     */
    fun cardFor(parsed: ParsedStatement, issuer: String?, cards: List<Card>, text: String = ""): Card? {
        val credit = cards.filter { !it.isDebit }
        credit.find { it.hasDigits(parsed.last4) }?.let { return it }
        val sameBank = credit.filter { issuer != null && it.bank.equals(issuer, ignoreCase = true) }
        val bankWords = issuer.orEmpty().lowercase().split(' ').toSet() + setOf("bank", "card", "credit", "the")
        // Words that tell this card apart from the bank's others, e.g. "regalia", "gold" / "phonepe", "ultimo".
        fun ownWords(c: Card) = c.name.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length >= 4 && it !in bankWords }
        val byName = sameBank.filter { c -> ownWords(c).any { w -> text.contains(w, ignoreCase = true) } }
        return byName.singleOrNull() ?: sameBank.singleOrNull()
    }

    /**
     * The statement's purchases as spends on [card], leaving out bill payments, refunds, cashback and
     * fees, and any purchase already logged (same card, same amount, within 3 days, e.g. from SMS).
     * Each line gets a fixed key, so importing the same statement twice adds nothing.
     */
    fun newSpends(parsed: ParsedStatement, card: Card, existing: List<Spend>, zone: ZoneId = ZoneId.systemDefault()): List<Spend> {
        val onCard = existing.filter { it.cardId == card.id }
        return parsed.lines
            .filter { !it.isCredit && TxnKind.classify(it.description, false) == TxnKind.SPEND }
            .map { line ->
                val time = line.date.atStartOfDay(zone).toInstant().toEpochMilli() + 12 * 60 * 60 * 1000L // midday: safe across time zones
                Spend(cardId = card.id, amountPaise = line.amountPaise, merchant = line.description.trim(), timeMillis = time,
                    smsKey = "stmt:" + Pdf.sha256("${card.id}|${line.date}|${line.amountPaise}|${line.description}|${line.reference}".toByteArray()))
            }
            .filter { s -> onCard.none { it.amountPaise == s.amountPaise && abs(it.timeMillis - s.timeMillis) <= 3 * DAY } }
    }

    /** Reads one statement PDF into the wallet. [received] is the email's date, for layouts that print no year. */
    /** [label]: where the PDF came from (sender and file name), so failures say which one. */
    /** [password]: one typed for this PDF only (tried first, not remembered). */
    suspend fun import(
        context: Context, dao: WalletDao, bytes: ByteArray, profile: PdfProfile, received: LocalDate? = null, label: String = "", password: String? = null,
    ): Outcome {
        val from = if (label.isBlank()) "" else " ($label)"
        Pdf.init(context)
        val cards = dao.allCards()
        val digits = cards.flatMap { listOf(it.last4) + it.oldLast4.split(',') }.map { it.trim() }.filter { it.length == 4 }
        // The password that opened the last statement is tried first; it's usually the same every month.
        val prefs = context.getSharedPreferences("statements", Context.MODE_PRIVATE)
        val worked = prefs.getStringSet("passwords_that_worked", emptySet()).orEmpty()
        val candidates = (listOfNotNull(password) + worked.toList() + Passwords.candidates(profile, digits)).distinct()
        val text = when (val r = Pdf.extract(bytes, candidates)) {
            is Pdf.Result.Text -> r.text.also {
                if (r.password.isNotEmpty() && r.password != password) prefs.edit().putStringSet("passwords_that_worked", worked + r.password).apply()
            }
            Pdf.Result.Locked -> {
                if (!LockedPdfs.keep(context, bytes, label, received)) return done(dao, Outcome("Already opened this PDF$from before; skipped."))
                return done(dao, Outcome("Couldn't unlock the PDF$from. It's kept under More → Statements → Locked PDFs: type its password there once.", locked = true))
            }
            is Pdf.Result.Failed -> return done(dao, Outcome("Couldn't read the PDF$from (${r.reason})."))
        }
        val issuer = issuerOf(text)
        val first = Parsers.forIssuer(issuer ?: "").parse(text, received)
        val card = cardFor(first, issuer, cards, text)
            ?: return done(dao, Outcome("Statement$from for ${issuer ?: "an unknown bank"} card ending ${first.last4 ?: "?"}: no matching card. Add the card or its digits, then import again."))
        val parsed = if (issuer == null) Parsers.forIssuer(card.bank).parse(text, received) else first

        // The PDF's statement date sets the card's cycle; the bill is kept for the screens.
        parsed.statementDate?.let { date ->
            val day = date.toEpochDay()
            if (card.lastStatementEpochDay == null || day > card.lastStatementEpochDay) {
                dao.saveLogged(card.copy(statementDay = date.dayOfMonth, lastStatementEpochDay = day))
            }
            dao.saveStatement(StatementRecord(cardId = card.id, statementDate = day, periodStart = parsed.periodStart?.toEpochDay(),
                dueDate = parsed.dueDate?.toEpochDay(), totalDuePaise = parsed.totalDuePaise, minDuePaise = parsed.minDuePaise))
        }
        val spends = newSpends(parsed, card, dao.allSpends())
        var added = 0
        spends.forEach { if (dao.insertFromSms(it) != -1L) added++ }
        val check = Parsers.validate(parsed)
        val due = parsed.dueDate?.let { " Due ${it.dayOfMonth} ${it.month.name.lowercase().replaceFirstChar(Char::uppercase).take(3)}" }.orEmpty()
        val total = parsed.totalDuePaise?.let { ", total ${rupees(it)}" }.orEmpty()
        return done(dao, Outcome(
            "${card.name} statement${parsed.statementDate?.let { " of $it" }.orEmpty()}: $added new spend${if (added == 1) "" else "s"}$total.$due" +
                if (check.ok) "" else " (Check: ${check.note})",
            added,
        ))
    }

    private suspend fun done(dao: WalletDao, outcome: Outcome): Outcome = outcome.also { dao.log("Statement import: ${it.message}") }
}
