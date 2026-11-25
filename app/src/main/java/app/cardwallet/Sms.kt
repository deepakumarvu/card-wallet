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

    fun read(body: String, sender: String, timeMillis: Long, cards: List<Card>): Result {
        val key = md5Hex("$sender|$timeMillis|$body")
        val needsYou = Result.NeedsYou(UnreadSms(sender = sender, body = body, timeMillis = timeMillis, smsKey = key))
        val tx = BankParserFactory.parse(body, sender, timeMillis)
            ?: return if (BankParserFactory.isKnownBankSender(sender) && body.contains("card", ignoreCase = true) &&
                spendWords.containsMatchIn(body) && !otpWords.containsMatchIn(body)
            ) needsYou else Result.Ignore

        val byLast4 = cards.find { tx.accountLast4 != null && it.last4 == tx.accountLast4 }
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

    /** Logs [result]; returns true if it added something new. */
    suspend fun save(dao: WalletDao, result: Result): Boolean = when (result) {
        is Result.CardSpend -> dao.insertFromSms(result.spend) != -1L
        is Result.NeedsYou -> dao.insertUnread(result.sms) != -1L
        Result.Ignore -> false
    }

    /** Reads the SMS inbox from the 1st of last month. Returns how many spends/messages were new. */
    suspend fun importInbox(context: Context): Int {
        val dao = WalletDb.get(context).dao()
        val cards = dao.allCards()
        val since = Calendar.getInstance().apply {
            add(Calendar.MONTH, -1); set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
        }.timeInMillis
        var added = 0
        context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
            "${Telephony.Sms.DATE} >= ?", arrayOf(since.toString()), "${Telephony.Sms.DATE} ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                val sender = c.getString(0) ?: continue
                val body = c.getString(1) ?: continue
                if (save(dao, read(body, sender, c.getLong(2), cards))) added++
            }
        }
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
                SmsSpends.save(dao, SmsSpends.read(body, sender, time, dao.allCards()))
            } finally {
                pending.finish()
            }
        }
    }
}
