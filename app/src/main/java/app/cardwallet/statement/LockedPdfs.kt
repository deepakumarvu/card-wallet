package app.cardwallet.statement

import android.content.Context
import app.cardwallet.WalletDao
import app.cardwallet.log
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

/**
 * Statement PDFs no known password opened, kept inside the app (never shared) until you type the
 * password once. From that password the app learns the bank's pattern and opens the rest.
 */
object LockedPdfs {
    data class Item(val id: String, val label: String, val received: LocalDate?) {
        /** Scapia sets a new random password for every statement (shown in its app), so there's nothing to learn. */
        val oneOffPassword get() = label.contains("scapia", ignoreCase = true)
    }

    private fun dir(context: Context) = File(context.filesDir, "locked").apply { mkdirs() }

    /** PDFs already opened with a typed password, by fingerprint: a fresh download of the same file isn't kept again. */
    private fun opened(context: Context) = prefs(context).getStringSet("opened_pdfs", emptySet()).orEmpty()
    private fun markOpened(context: Context, id: String) = prefs(context).edit().putStringSet("opened_pdfs", opened(context) + id).apply()

    /** Keeps a locked PDF for later; false when this exact file was already opened before (nothing to do). */
    fun keep(context: Context, bytes: ByteArray, label: String, received: LocalDate?): Boolean {
        val id = Pdf.sha256(bytes).take(16)
        if (id in opened(context)) return false
        File(dir(context), "$id.pdf").writeBytes(bytes)
        File(dir(context), "$id.json").writeText(JSONObject().put("label", label).put("received", received?.toString() ?: "").toString())
        return true
    }

    fun list(context: Context): List<Item> = dir(context).listFiles { f -> f.name.endsWith(".json") }.orEmpty().mapNotNull { f ->
        runCatching {
            val o = JSONObject(f.readText())
            Item(f.nameWithoutExtension, o.optString("label"), o.optString("received").takeIf { it.isNotEmpty() }?.let(LocalDate::parse))
        }.getOrNull()
    }.sortedByDescending { it.received }

    /** Drop a locked PDF without opening it (e.g. one you already opened); it won't be kept again. */
    fun dismiss(context: Context, item: Item) { markOpened(context, item.id); remove(context, item.id) }

    private fun remove(context: Context, id: String) {
        File(dir(context), "$id.pdf").delete()
        File(dir(context), "$id.json").delete()
    }

    fun loadProfile(context: Context) = PdfProfile.fromJson(prefs(context).getString("profile", null))
    private fun prefs(context: Context) = context.getSharedPreferences("statements", Context.MODE_PRIVATE)

    /**
     * Tries [password] on [item]. If it opens: learn its pattern (or remember the password itself),
     * import the statement, then retry every other locked PDF with what was learned.
     * Returns a sentence for the screen.
     */
    suspend fun unlock(context: Context, dao: WalletDao, item: Item, password: String): String {
        val bytes = File(dir(context), "${item.id}.pdf").takeIf { it.exists() }?.readBytes() ?: return "That PDF is gone."
        Pdf.init(context) // PDFBox needs its resources loaded before the first PDF is opened
        if (Pdf.extract(bytes, listOf(password)) !is Pdf.Result.Text) return "That password didn't open it."
        markOpened(context, item.id)

        if (item.oneOffPassword) {
            remove(context, item.id)
            val outcome = StatementImport.import(context, dao, bytes, loadProfile(context), item.received, item.label, password)
            return outcome.message
        }

        var profile = loadProfile(context)
        val digits = dao.allCards().flatMap { listOf(it.last4) + it.oldLast4.split(',') }.map { it.trim() }.filter { it.length == 4 }
        val pattern = Passwords.inferTemplates(password, profile, digits).firstOrNull()
        val learned = if (pattern != null) {
            profile = profile.copy(templates = (profile.templates + pattern).distinct())
            prefs(context).edit().putString("profile", profile.toJson()).apply()
            dao.log("Learned statement password pattern $pattern from ${item.label}")
            "Learned the pattern $pattern."
        } else {
            val worked = prefs(context).getStringSet("passwords_that_worked", emptySet()).orEmpty()
            prefs(context).edit().putStringSet("passwords_that_worked", worked + password).apply()
            dao.log("Remembered a statement password from ${item.label} (no pattern from your details fits)")
            "No pattern from your details fits, so the password itself is remembered."
        }

        var opened = 0
        var added = 0
        for (other in listOf(item) + list(context).filter { it.id != item.id }) {
            val pdf = File(dir(context), "${other.id}.pdf").takeIf { it.exists() }?.readBytes() ?: continue
            remove(context, other.id) // import keeps it again if it's still locked
            val outcome = StatementImport.import(context, dao, pdf, profile, other.received, other.label)
            if (!outcome.locked) { opened++; added += outcome.added }
        }
        return "Opened $opened statement${if (opened == 1) "" else "s"}, $added new spends. $learned"
    }
}
