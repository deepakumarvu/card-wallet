package app.cardwallet.statement

import android.content.Context
import app.cardwallet.Card
import app.cardwallet.Spend
import app.cardwallet.WalletDao
import app.cardwallet.log
import java.time.Instant
import java.time.ZoneId
import java.util.Date
import kotlin.math.abs

/**
 * Reads statement PDFs and card alert emails from Gmail, read-only, with a Google token
 * (gmail.readonly). Mail is read straight from Gmail over IMAP on the phone; nothing goes anywhere else.
 */
object GmailSync {
    const val SCOPE = "https://www.googleapis.com/auth/gmail.readonly"
    private const val DAY = 86_400_000L
    private const val FIRST_LOOK_DAYS = 180L

    /** Where each bank's statements come from: exact addresses first, then any mail from its domains with a statement subject. */
    private val statementFrom = mapOf(
        "HDFC Bank" to listOf("emailstatements.cards@hdfcbank.net", "emailstatements.cards@hdfcbank.bank.in", "emailstatements.cards@hdfc.bank.in"),
        "ICICI Bank" to listOf("credit_cards@icicibank.com", "credit_cards@icici.bank.in"),
        "Axis Bank" to listOf("cc.statements@axisbank.com", "cc.statements@axis.bank.in"),
    )
    private val domains = mapOf(
        "HDFC Bank" to listOf("hdfcbank", "hdfc.bank.in"),
        "ICICI Bank" to listOf("icicibank", "icici.bank.in"),
        "Axis Bank" to listOf("axisbank", "axis.bank.in"),
        "Federal Bank" to listOf("federalbank", "federal.bank.in", "scapia"),
        "Indian Bank" to listOf("indianbank"),
    )

    /** Banks that email card alerts we can read. */
    private val alertSenders = mapOf("HDFC Bank" to listOf("alerts@hdfcbank.bank.in", "alerts@hdfcbank.net"))

    /** A card alert email as a spend on the right card, or null when it isn't one we can place. */
    fun spendFromAlert(alert: CardAlert, msgId: String, cards: List<Card>, existing: List<Spend>): Spend? {
        if (alert.kind != TxnKind.SPEND || alert.isCredit || alert.currency != "INR") return null
        val card = cards.find { it.hasDigits(alert.last4) } ?: return null
        val time = alert.atMillis ?: return null
        // Same card, same amount within a day: already logged (usually from the SMS).
        if (existing.any { it.cardId == card.id && it.amountPaise == alert.amountPaise && abs(it.timeMillis - time) <= DAY }) return null
        return Spend(cardId = card.id, amountPaise = alert.amountPaise, merchant = alert.merchant.ifBlank { "Unknown" }, timeMillis = time,
            smsKey = "mail:" + Pdf.sha256(msgId.toByteArray()))
    }

    /** One run: new statements since the last run (first run: 6 months), then new alert emails. Returns a summary. */
    suspend fun sync(context: Context, dao: WalletDao, email: String, token: String, profile: PdfProfile): String {
        val prefs = context.getSharedPreferences("gmail", Context.MODE_PRIVATE)
        val cards = dao.allCards()
        val banks = cards.filter { !it.isDebit }.map { it.bank }.distinct()
        val now = System.currentTimeMillis()
        val since = prefs.getLong("synced_up_to", 0).takeIf { it > 0 }?.minus(DAY) ?: (now - FIRST_LOOK_DAYS * DAY)
        val seen = prefs.getStringSet("seen_pdfs", emptySet()).orEmpty().toMutableSet()
        val client = MailClient("imap.gmail.com", 993, email, token)

        var statements = 0
        var fromStatements = 0
        val notes = mutableListOf<String>()
        client.fetchStatements(
            Date(since), banks.flatMap { statementFrom[it].orEmpty() }, banks.flatMap { domains[it].orEmpty() },
            skip = { it in seen },
        ) { _, _, pdf, failure ->
            if (failure != null) { notes += "${pdf.fileName}: $failure"; return@fetchStatements }
            val received = Instant.ofEpochMilli(pdf.receivedAt).atZone(ZoneId.systemDefault()).toLocalDate()
            val outcome = StatementImport.import(context, dao, pdf.bytes, profile, received)
            statements++
            fromStatements += outcome.added
            seen += pdf.key
        }

        var fromAlerts = 0
        val alertFrom = banks.flatMap { alertSenders[it].orEmpty() }
        if (alertFrom.isNotEmpty()) {
            for (m in client.fetchAlerts(Date(since), alertFrom)) {
                val alert = AlertParser.parseEmail(m.subject, m.text, m.receivedAt) ?: continue
                val spend = spendFromAlert(alert, m.msgId, cards, dao.allSpends()) ?: continue
                if (dao.insertFromSms(spend) != -1L) fromAlerts++
            }
        }

        prefs.edit().putLong("synced_up_to", now).putStringSet("seen_pdfs", seen).apply()
        val summary = "Gmail: $statements statement${if (statements == 1) "" else "s"} ($fromStatements new spends), " +
            "$fromAlerts new spend${if (fromAlerts == 1) "" else "s"} from alert emails" +
            if (notes.isEmpty()) "." else ". Couldn't read: ${notes.joinToString("; ")}"
        dao.log(summary)
        return summary
    }
}
