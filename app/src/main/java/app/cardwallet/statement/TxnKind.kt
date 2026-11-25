// From CardKeep (github.com/santdas36/CardKeep, GPL-3.0), used under AGPL-3.0 (GPLv3 section 13).
package app.cardwallet.statement

/** What a statement line is: a purchase, a bill payment, a refund, cashback or a fee. Only purchases become spends. */
object TxnKind {
    const val SPEND = "spend"
    const val PAYMENT = "payment"
    const val REFUND = "refund"
    const val CASHBACK = "cashback"
    const val FEE = "fee"

    private val payment = Regex(
        "payment|payzapp|bppy|thank you|autopay|auto debit|repayment|neft|imps|rtgs|bbps|bill ?desk|received|^upi[- ]|cc pay",
        RegexOption.IGNORE_CASE,
    )
    private val refund = Regex("refund|reversal|reversed|cancel", RegexOption.IGNORE_CASE)
    private val cashback = Regex("cash ?back|reward|\\bmonies\\b", RegexOption.IGNORE_CASE)
    private val fee = Regex(
        "^fee:|\\bfee\\b|\\bcharges?\\b|\\bgst\\b|igst|cgst|sgst|interest|finance charge|late pay|surcharge|mark-?up|overlimit|\\bemi\\b.*\\b(interest|int|processing|proc)\\b",
        RegexOption.IGNORE_CASE,
    )

    fun classify(description: String, isCredit: Boolean): String = when {
        cashback.containsMatchIn(description) -> CASHBACK // reversals are debits of the same kind
        isCredit && refund.containsMatchIn(description) -> REFUND
        isCredit && payment.containsMatchIn(description) -> PAYMENT
        isCredit -> REFUND
        fee.containsMatchIn(description) -> FEE
        else -> SPEND
    }

    fun label(kind: String) = when (kind) {
        PAYMENT -> "Payment"; REFUND -> "Refund"; CASHBACK -> "Cashback"; FEE -> "Fee"; else -> "Spend"
    }

    /** Counts toward "spent": purchases and fees, less refunds. Payments and cashback are separate. */
    fun spendSign(kind: String, isCredit: Boolean): Int = when (kind) {
        SPEND, FEE -> if (isCredit) -1 else 1
        REFUND -> -1
        else -> 0
    }
}
