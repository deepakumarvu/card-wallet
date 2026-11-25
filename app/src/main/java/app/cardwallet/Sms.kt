package app.cardwallet

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.pennywiseai.parser.core.TransactionType
import com.pennywiseai.parser.core.bank.BankParserFactory
import com.pennywiseai.parser.core.md5Hex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Calendar

/** Turns one bank SMS into a card spend. All parsing happens on the phone. */
object SmsSpends {

    sealed interface Result {
        data class CardSpend(val spend: Spend) : Result
        /** Looks like a card spend, but needs you: unknown card, foreign currency, or a format the parser can't read. */
        data class NeedsYou(val sms: UnreadSms) : Result
        /** Not a card spend (OTP, bank account debit, offer...). */
        data object Ignore : Result
    }

    private val spendWords = Regex("""\b(spent|debited|used|purchase|txn)\b""", RegexOption.IGNORE_CASE)
    private val otpWords = Regex("""\b(otp|one time password|verification code)\b""", RegexOption.IGNORE_CASE)
    /** A card was used but no money moved: declined, failed or unconfirmed. */
    private val noSpendWords = Regex("""\b(declined|unable to confirm|failed|reversed|reversal)\b""", RegexOption.IGNORE_CASE)

    /** The bank part of an SMS sender: "AD-AXISBK-S" → "AXISBK". */
    fun senderKey(sender: String): String =
        Regex("""^[A-Za-z]{2}-([A-Za-z0-9]+)(?:-[A-Za-z])?$""").find(sender.trim())?.groupValues?.get(1)?.uppercase() ?: sender.trim().uppercase()

    /** A message with every number masked and spacing evened out, so the same kind of message matches. */
    fun shapeOf(body: String): String = body.replace(Regex("""\d[\d,.]*"""), "#").replace(Regex("""\s+"""), " ").trim().lowercase()

    /** Key for [ignored] lookups. */
    fun shapeKey(sender: String, body: String) = senderKey(sender) + "|" + shapeOf(body)

    /** True when shape [key] fits [pattern]: same bank, same words, "*" in the pattern matching any one word. */
    fun fits(pattern: String, key: String): Boolean {
        val (pSender, pShape) = pattern.split('|', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        val (kSender, kShape) = key.split('|', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        if (pSender != kSender) return false
        val p = pShape.split(' ')
        val k = kShape.split(' ')
        return p.size == k.size && p.indices.all { p[it] == "*" || p[it] == k[it] }
    }

    /**
     * A shape covering both [a] and [b] when they differ in 1 or 2 words ("to swiggy" / "to zomato" → "to *"),
     * else null. Keeps the ignored list short.
     */
    fun merge(a: String, b: String): String? {
        val x = a.split(' ')
        val y = b.split(' ')
        if (x.size != y.size || x.size < 4) return null
        val differ = x.indices.filter { x[it] != y[it] && x[it] != "*" }
        if (differ.isEmpty() || differ.size > 2) return null
        return x.indices.joinToString(" ") { if (it in differ) "*" else x[it] }
    }

    /** [ignored]: shape patterns you marked "Not a spend" (see [shapeKey], [fits]); those messages are skipped. */
    fun read(body: String, sender: String, timeMillis: Long, cards: List<Card>, ignored: Set<String> = emptySet()): Result {
        if (ignored.isNotEmpty() && shapeKey(sender, body).let { key -> ignored.any { fits(it, key) } }) return Result.Ignore
        val key = md5Hex("$sender|$timeMillis|$body")
        val needsYou = Result.NeedsYou(UnreadSms(sender = sender, body = body, timeMillis = timeMillis, smsKey = key))
        val tx = BankParserFactory.parse(body, sender, timeMillis)
            ?: return if (BankParserFactory.isKnownBankSender(sender) && body.contains("card", ignoreCase = true) &&
                spendWords.containsMatchIn(body) && !otpWords.containsMatchIn(body) && !noSpendWords.containsMatchIn(body)
            ) needsYou else Result.Ignore

        val byLast4 = cards.find { it.hasDigits(tx.accountLast4) }
        val card = when (tx.type) {
            // The parser marks credit card spends as CREDIT.
            TransactionType.CREDIT -> byLast4
                ?: cards.filter { !it.isDebit && it.bank.equals(tx.bankName, ignoreCase = true) }.singleOrNull().takeIf { tx.accountLast4 == null }
                ?: return needsYou
            // Debit card spends come as EXPENSE; only log them when the last 4 digits match a card,
            // so bank-account debits (UPI, transfers) stay out.
            TransactionType.EXPENSE -> byLast4 ?: return Result.Ignore
            else -> return Result.Ignore
        }
        if (tx.currency != "INR") return needsYou

        val spend = Spend(
            cardId = card.id,
            amountPaise = tx.amount.movePointRight(2).toLong(),
            merchant = tx.merchant ?: "Unknown",
            timeMillis = timeMillis,
            smsKey = key,
        )
        return Result.CardSpend(spend)
    }

    /**
     * A waiting message turned into a ready-to-save spend: amount, merchant and card filled in from
     * the message where it can be read, so the review only needs a check and Save.
     */
    fun draft(sms: UnreadSms, cards: List<Card>): Spend {
        val tx = BankParserFactory.parse(sms.body, sms.sender, sms.timeMillis)
        val card = cards.firstOrNull { it.hasDigits(tx?.accountLast4) }
            ?: cards.firstOrNull { it.last4.isNotBlank() && sms.body.contains(it.last4) }
            ?: cards.filter { tx != null && it.bank.equals(tx.bankName, ignoreCase = true) }.singleOrNull()
            ?: cards.firstOrNull()
        return Spend(
            cardId = card?.id ?: 0,
            amountPaise = if (tx != null && tx.currency == "INR") tx.amount.movePointRight(2).toLong() else 0,
            merchant = tx?.merchant.orEmpty(),
            timeMillis = sms.timeMillis,
            smsKey = sms.smsKey,
        )
    }

    /** Why a waiting message couldn't be logged on its own, in words, without amounts. */
    fun whyWaiting(body: String, sender: String, cards: List<Card>): String {
        val tx = BankParserFactory.parse(body, sender, 0) ?: return "Couldn't read this bank's message format"
        if (tx.currency != "INR") return "Foreign currency (${tx.currency}): enter the ₹ amount"
        val last4 = tx.accountLast4
        if (last4 != null) return if (cards.none { it.hasDigits(last4) }) "Card ending $last4 isn't in the app, or its digits aren't set" else "Matched a card; open the app again to log it"
        val sameBank = cards.count { !it.isDebit && it.bank.equals(tx.bankName, ignoreCase = true) }
        return if (sameBank == 0) "${tx.bankName} card, but no ${tx.bankName} card is in the app"
        else "${tx.bankName} didn't say which card, and you have $sameBank of its cards"
    }

    /**
     * True when a statement or Gmail alert already logged this spend: same card, same amount, within
     * 3 days. SMS-to-SMS repeats are told apart by their own keys, so two real ₹199 charges both count.
     */
    fun alreadyLogged(spend: Spend, existing: List<Spend>): Boolean = existing.any {
        it.cardId == spend.cardId && it.amountPaise == spend.amountPaise &&
            (it.smsKey?.startsWith("stmt:") == true || it.smsKey?.startsWith("mail:") == true) &&
            kotlin.math.abs(it.timeMillis - spend.timeMillis) <= 3 * 86_400_000L
    }

    /** Logs [result]; returns true if it added something new. */
    suspend fun save(dao: WalletDao, result: Result, cards: List<Card>): Boolean = when (result) {
        is Result.CardSpend -> (!alreadyLogged(result.spend, dao.allSpends()) && dao.insertFromSms(result.spend) != -1L).also { new ->
            if (new) dao.log("SMS logged on ${cards.find { it.id == result.spend.cardId }?.name}: ${rupees(result.spend.amountPaise)} at ${result.spend.merchant}")
        }
        is Result.NeedsYou -> (dao.insertUnread(result.sms) != -1L).also { new ->
            if (new) dao.log("SMS from ${result.sms.sender} waiting: ${whyWaiting(result.sms.body, result.sms.sender, cards)}")
        }
        Result.Ignore -> false
    }

    /**
     * Re-reads waiting messages against the current cards, e.g. after you add a card's last 4
     * digits: what now matches a card is logged, what isn't a spend is dropped, the rest keep
     * waiting. Returns how many were logged.
     */
    suspend fun retryUnread(dao: WalletDao): Int {
        val cards = dao.allCards()
        val ignored = ignoredKeys(dao)
        var logged = 0
        var dropped = 0
        var waiting = 0
        for (sms in dao.allUnread()) {
            val result = read(sms.body, sms.sender, sms.timeMillis, cards, ignored)
            when (result) {
                is Result.CardSpend -> {
                    dao.insertFromSms(result.spend); dao.delete(sms); logged++
                    dao.log("Waiting SMS logged on ${cards.find { it.id == result.spend.cardId }?.name}: ${rupees(result.spend.amountPaise)} at ${result.spend.merchant}")
                }
                Result.Ignore -> { dao.deleteLogged(sms, "not a spend (e.g. declined)"); dropped++ }
                is Result.NeedsYou -> waiting++
            }
        }
        if (logged + dropped > 0) dao.log("Re-checked waiting messages: logged $logged, dropped $dropped, still waiting $waiting")
        return logged
    }

    suspend fun ignoredKeys(dao: WalletDao): Set<String> = dao.allIgnoredShapes().map { it.senderKey + "|" + it.shape }.toSet()

    /**
     * "Not a spend" on a waiting message: remember its shape, drop it, and drop other waiting
     * messages of the same shape. Returns how many were dropped in all.
     */
    suspend fun markNotSpend(dao: WalletDao, sms: UnreadSms): Int {
        val sender = senderKey(sms.sender)
        var shape = shapeOf(sms.body)
        // Fold into a near-identical entry from the same bank, so the list doesn't grow per merchant or name.
        dao.allIgnoredShapes().filter { it.senderKey == sender }.firstNotNullOfOrNull { old -> merge(old.shape, shape)?.let { old to it } }
            ?.let { (old, merged) -> dao.delete(old); shape = merged }
        if (dao.insertIgnoredShape(IgnoredShape(senderKey = sender, shape = shape, createdMillis = System.currentTimeMillis())) != -1L) {
            dao.log("Learned: ignore $sender messages like \"${shape.take(80)}…\"")
        }
        val pattern = "$sender|$shape"
        val same = dao.allUnread().filter { fits(pattern, shapeKey(it.sender, it.body)) }
        same.forEach { dao.deleteLogged(it, "marked not a spend") }
        return same.size
    }

    /** Reads the SMS inbox for the last [months] months. Returns how many spends/messages were new. */
    suspend fun importInbox(context: Context, months: Int = 6): Int {
        val dao = WalletDb.get(context).dao()
        val cards = dao.allCards()
        val since = Calendar.getInstance().apply {
            add(Calendar.MONTH, -months); set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
        }.timeInMillis
        val ignored = ignoredKeys(dao)
        var added = 0
        context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
            "${Telephony.Sms.DATE} >= ?", arrayOf(since.toString()), "${Telephony.Sms.DATE} ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                val sender = c.getString(0) ?: continue
                val body = c.getString(1) ?: continue
                if (save(dao, read(body, sender, c.getLong(2), cards, ignored), cards)) added++
            }
        }
        dao.log("Read SMS inbox: $added new")
        return added
    }
}

/** Logs card spends from new bank SMS as they arrive. */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (parts.isEmpty()) return
        val sender = parts[0].originatingAddress ?: return
        val body = parts.joinToString("") { it.messageBody ?: "" }
        val time = parts[0].timestampMillis
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dao = WalletDb.get(context).dao()
                val cards = dao.allCards()
                SmsSpends.save(dao, SmsSpends.read(body, sender, time, cards, SmsSpends.ignoredKeys(dao)), cards)
            } finally {
                pending.finish()
            }
        }
    }
}
