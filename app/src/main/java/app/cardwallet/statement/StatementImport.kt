package app.cardwallet.statement

import android.content.Context
import app.cardwallet.Card
import app.cardwallet.Spend
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
    data class Outcome(val message: String, val added: Int = 0)

    private const val DAY = 24 * 60 * 60 * 1000L

    /** Which issuer's layout to read the text with, from the bank name printed in it. */
    fun issuerOf(text: String): String? = listOf(
        "HDFC Bank" to "HDFC", "ICICI Bank" to "ICICI", "Axis Bank" to "Axis Bank", "SBI Card" to "SBI Card",
        "Kotak" to "Kotak", "IDFC FIRST" to "IDFC", "Yes Bank" to "YES BANK", "Amex" to "American Express",
    ).firstOrNull { (_, word) -> text.contains(word, ignoreCase = true) }?.first

    /** The card a statement belongs to: by its last 4 (or old digits), else the only card from that bank. */
    fun cardFor(parsed: ParsedStatement, issuer: String?, cards: List<Card>): Card? {
        val credit = cards.filter { !it.isDebit }
        return credit.find { it.hasDigits(parsed.last4) }
            ?: issuer?.let { bank -> credit.filter { it.bank.equals(bank, ignoreCase = true) }.singleOrNull() }
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
    suspend fun import(context: Context, dao: WalletDao, bytes: ByteArray, profile: PdfProfile, received: LocalDate? = null): Outcome {
        Pdf.init(context)
        val cards = dao.allCards()
        val digits = cards.flatMap { listOf(it.last4) + it.oldLast4.split(',') }.map { it.trim() }.filter { it.length == 4 }
        // The password that opened the last statement is tried first; it's usually the same every month.
        val prefs = context.getSharedPreferences("statements", Context.MODE_PRIVATE)
        val worked = prefs.getStringSet("passwords_that_worked", emptySet()).orEmpty()
        val candidates = (worked.toList() + Passwords.candidates(profile, digits)).distinct()
        val text = when (val r = Pdf.extract(bytes, candidates)) {
            is Pdf.Result.Text -> r.text.also { if (r.password.isNotEmpty()) prefs.edit().putStringSet("passwords_that_worked", worked + r.password).apply() }
            Pdf.Result.Locked -> return done(dao, Outcome("Couldn't unlock the PDF. Add your name and date of birth under More → Statements."))
            is Pdf.Result.Failed -> return done(dao, Outcome("Couldn't read the PDF (${r.reason})."))
        }
        val issuer = issuerOf(text)
        val first = Parsers.forIssuer(issuer ?: "").parse(text, received)
        val card = cardFor(first, issuer, cards)
            ?: return done(dao, Outcome("Statement for ${issuer ?: "an unknown bank"} card ending ${first.last4 ?: "?"}: no matching card. Add the card or its digits, then import again."))
        val parsed = if (issuer == null) Parsers.forIssuer(card.bank).parse(text, received) else first

        parsed.statementDate?.dayOfMonth?.takeIf { it != card.statementDay }?.let { day ->
            dao.saveLogged(card.copy(statementDay = day))
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
