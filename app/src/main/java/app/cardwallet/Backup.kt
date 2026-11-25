package app.cardwallet

import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Backup = one zip holding a CSV per table. Opens in any spreadsheet app. */
object Backup {

    data class Content(
        val cards: List<Card>,
        val rules: List<Rule>,
        val spends: List<Spend>,
        val merchants: List<MerchantCategory>,
    )

    fun write(content: Content, out: OutputStream) {
        val files = mapOf(
            "cards.csv" to listOf(listOf("id", "name", "bank", "last4", "base_rate_percent", "statement_day", "rupees_per_point", "is_debit", "sort_order", "show_points", "network", "color_index", "international_only", "earn_block_rupees")) +
                content.cards.map { listOf(it.id, it.name, it.bank, it.last4, it.baseRatePercent, it.statementDay ?: "", it.rupeesPerPoint, it.isDebit, it.sortOrder, it.showPoints, it.network ?: "", it.colorIndex ?: "", it.internationalOnly, it.earnBlockRupees ?: "").map(Any::toString) },
            "rules.csv" to listOf(listOf("id", "card_id", "label", "keywords", "category", "rate_percent", "monthly_cap_paise", "min_amount_paise", "cap_base_multiple")) +
                content.rules.map { listOf(it.id, it.cardId, it.label, it.keywords, it.category ?: "", it.ratePercent, it.monthlyCapPaise ?: "", it.minAmountPaise ?: "", it.capBaseMultiple ?: "").map(Any::toString) },
            "spends.csv" to listOf(listOf("id", "card_id", "amount_paise", "merchant", "time_millis", "sms_key")) +
                content.spends.map { listOf(it.id, it.cardId, it.amountPaise, it.merchant, it.timeMillis, it.smsKey ?: "").map(Any::toString) },
            "merchant_categories.csv" to listOf(listOf("keyword", "category")) +
                content.merchants.map { listOf(it.keyword, it.category) },
        )
        ZipOutputStream(out).use { zip ->
            for ((name, rows) in files) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(rows.joinToString("") { row -> row.joinToString(",", transform = ::quote) + "\n" }.toByteArray())
                zip.closeEntry()
            }
        }
    }

    fun read(input: InputStream): Content {
        val files = mutableMapOf<String, List<List<String>>>()
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                files[entry.name] = parseCsv(zip.readBytes().decodeToString()).drop(1) // drop header
            }
        }
        fun rows(name: String) = files[name] ?: error("Backup is missing $name")
        return Content(
            // Columns added later are optional, so older backups still restore.
            cards = rows("cards.csv").map {
                Card(it[0].toLong(), it[1], it[2], it[3], it[4].toDouble(), it[5].ifEmpty { null }?.toInt(),
                    it.getOrNull(6)?.ifEmpty { null }?.toDouble() ?: 1.0, it.getOrNull(7) == "true",
                    it.getOrNull(8)?.ifEmpty { null }?.toInt() ?: 0, it.getOrNull(9) == "true",
                    it.getOrNull(10)?.ifEmpty { null }, it.getOrNull(11)?.ifEmpty { null }?.toInt(),
                    it.getOrNull(12) == "true", it.getOrNull(13)?.ifEmpty { null }?.toInt())
            },
            rules = rows("rules.csv").map {
                Rule(it[0].toLong(), it[1].toLong(), it[2], it[3], it[4].ifEmpty { null }, it[5].toDouble(), it[6].ifEmpty { null }?.toLong(),
                    it.getOrNull(7)?.ifEmpty { null }?.toLong(), it.getOrNull(8)?.ifEmpty { null }?.toDouble())
            },
            spends = rows("spends.csv").map { Spend(it[0].toLong(), it[1].toLong(), it[2].toLong(), it[3], it[4].toLong(), it[5].ifEmpty { null }) },
            merchants = rows("merchant_categories.csv").map { MerchantCategory(it[0], it[1]) },
        )
    }

    private fun quote(field: String): String =
        if (field.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + field.replace("\"", "\"\"") + "\"" else field

    /** Reads CSV with quoted fields ("a, b", "say ""hi""", line breaks inside quotes). */
    fun parseCsv(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                inQuotes && c == '"' && text.getOrNull(i + 1) == '"' -> { field.append('"'); i++ }
                c == '"' -> inQuotes = !inQuotes
                inQuotes -> field.append(c)
                c == ',' -> { row.add(field.toString()); field.clear() }
                c == '\n' -> { row.add(field.toString()); field.clear(); rows.add(row); row = mutableListOf() }
                c != '\r' -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) { row.add(field.toString()); rows.add(row) }
        return rows
    }
}
