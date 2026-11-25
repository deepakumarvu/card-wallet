package app.cardwallet

/**
 * The activity log: every change to cards, rules, spends and categories, plus SMS handling, kept
 * on the phone (newest 2,000 lines) so problems can be traced later. Views: More → Activity log.
 */
suspend fun WalletDao.log(message: String) {
    insertLog(LogEntry(timeMillis = System.currentTimeMillis(), message = message))
    trimLogs(2000)
}

suspend fun WalletDao.saveLogged(card: Card): Long = save(card).also { log("${if (card.id == 0L) "Card added" else "Card saved"}: $card") }
suspend fun WalletDao.saveLogged(rule: Rule): Long = save(rule).also { log("${if (rule.id == 0L) "Rule added" else "Rule saved"}: $rule") }
suspend fun WalletDao.saveLogged(spend: Spend): Long = save(spend).also { log("${if (spend.id == 0L) "Spend added" else "Spend saved"}: $spend") }
suspend fun WalletDao.saveLogged(merchant: MerchantCategory) = save(merchant).also { log("Merchant keyword added: ${merchant.keyword} → ${merchant.category}") }

suspend fun WalletDao.deleteLogged(card: Card) = delete(card).also { log("Card deleted, with its rules and spends: $card") }
suspend fun WalletDao.deleteLogged(rule: Rule) = delete(rule).also { log("Rule deleted: $rule") }
suspend fun WalletDao.deleteLogged(spend: Spend) = delete(spend).also { log("Spend deleted: $spend") }
suspend fun WalletDao.deleteLogged(merchant: MerchantCategory) = delete(merchant).also { log("Merchant keyword removed: ${merchant.keyword} → ${merchant.category}") }
suspend fun WalletDao.deleteLogged(sms: UnreadSms, why: String) = delete(sms).also { log("Waiting message from ${sms.sender} closed: $why") }
