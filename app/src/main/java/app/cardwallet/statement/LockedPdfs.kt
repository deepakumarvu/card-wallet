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
    data class Item(val id: String, val label: String, val received: LocalDate?)

    private fun dir(context: Context) = File(context.filesDir, "locked").apply { mkdirs() }

    fun keep(context: Context, bytes: ByteArray, label: String, received: LocalDate?) {
        val id = Pdf.sha256(bytes).take(16)
        File(dir(context), "$id.pdf").writeBytes(bytes)
        File(dir(context), "$id.json").writeText(JSONObject().put("label", label).put("received", received?.toString() ?: "").toString())
    }

    fun list(context: Context): List<Item> = dir(context).listFiles { f -> f.name.endsWith(".json") }.orEmpty().mapNotNull { f ->
        runCatching {
            val o = JSONObject(f.readText())
            Item(f.nameWithoutExtension, o.optString("label"), o.optString("received").takeIf { it.isNotEmpty() }?.let(LocalDate::parse))
        }.getOrNull()
    }.sortedByDescending { it.received }

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
        if (Pdf.extract(bytes, listOf(password)) !is Pdf.Result.Text) return "That password didn't open it."

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
