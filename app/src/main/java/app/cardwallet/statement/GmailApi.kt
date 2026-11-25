package app.cardwallet.statement

import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A statement PDF found in Gmail. [key] is stable per attachment, so a PDF is fetched once. */
class MailPdf(val key: String, val from: String, val subject: String, val receivedAt: Long, val fileName: String, val bytes: ByteArray)

/** An alert email as plain text. */
class MailText(val msgId: String, val from: String, val subject: String, val receivedAt: Long, val text: String)

/**
 * Reads Gmail through Google's Gmail API with a read-only token (gmail.readonly). Searches run on
 * Gmail's side with its own query syntax; only matching messages and their PDFs are downloaded.
 * (IMAP would need the full-access mail.google.com permission, so it isn't used.)
 */
class GmailApi(private val token: String) {

    private fun get(path: String): JSONObject {
        val conn = URL("https://gmail.googleapis.com/gmail/v1/users/me/$path").openConnection() as HttpURLConnection
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.connectTimeout = 20_000
        conn.readTimeout = 60_000
        try {
            val code = conn.responseCode
            if (code != 200) {
                val why = conn.errorStream?.bufferedReader()?.readText()?.take(600)
                android.util.Log.e("CardWallet", "Gmail API $path → HTTP $code: $why")
                error("Gmail API HTTP $code: ${why?.take(200)}")
            }
            return JSONObject(conn.inputStream.bufferedReader().readText())
        } finally {
            conn.disconnect()
        }
    }

    /** Ids of messages matching a Gmail search, newest first, up to [max]. */
    fun search(query: String, max: Int = 500): List<String> {
        val ids = mutableListOf<String>()
        var page: String? = null
        do {
            val q = URLEncoder.encode(query, "UTF-8")
            val r = get("messages?q=$q&maxResults=100" + (page?.let { "&pageToken=$it" } ?: ""))
            r.optJSONArray("messages")?.let { a -> for (i in 0 until a.length()) ids += a.getJSONObject(i).getString("id") }
            page = r.optString("nextPageToken").takeIf { it.isNotEmpty() }
        } while (page != null && ids.size < max)
        return ids.take(max)
    }

    private fun message(id: String) = get("messages/$id?format=full")

    private fun header(msg: JSONObject, name: String): String {
        val hs = msg.getJSONObject("payload").optJSONArray("headers") ?: return ""
        for (i in 0 until hs.length()) hs.getJSONObject(i).let { if (it.getString("name").equals(name, true)) return it.getString("value") }
        return ""
    }

    private fun parts(part: JSONObject): List<JSONObject> =
        listOf(part) + (part.optJSONArray("parts")?.let { a -> (0 until a.length()).flatMap { parts(a.getJSONObject(it)) } } ?: emptyList())

    private fun decode(data: String): ByteArray = Base64.decode(data, Base64.URL_SAFE)

    /** Ids of statement emails since [since]: from [exact] addresses, or from [domains] with a statement-like subject. */
    fun statementIds(since: Date, exact: List<String>, domains: List<String>): List<String> {
        val after = SimpleDateFormat("yyyy/MM/dd", Locale.US).format(since)
        val base = "has:attachment filename:pdf after:$after"
        val ids = LinkedHashSet<String>()
        if (exact.isNotEmpty()) ids += search("from:(${exact.joinToString(" OR ")}) $base")
        if (domains.isNotEmpty()) ids += search("from:(${domains.joinToString(" OR ")}) (${KEYWORDS.joinToString(" OR ") { "subject:$it" }}) $base")
        return ids.toList()
    }

    /** The PDF attachments of one email, downloaded. */
    fun pdfsOf(id: String): List<MailPdf> {
        val msg = message(id)
        return parts(msg.getJSONObject("payload")).mapNotNull { p ->
            val name = p.optString("filename")
            val attachment = p.optJSONObject("body")?.optString("attachmentId").orEmpty()
            if (!name.endsWith(".pdf", true) || attachment.isEmpty()) return@mapNotNull null
            MailPdf("$id/$name", header(msg, "From"), header(msg, "Subject"), msg.optString("internalDate").toLongOrNull() ?: 0, name,
                decode(get("messages/$id/attachments/$attachment").getString("data")))
        }
    }

    /** Alert emails from [senders] since [since], oldest first, as plain text. */
    fun alerts(since: Date, senders: List<String>, limit: Int = 200, progress: (Int, Int) -> Unit = { _, _ -> }): List<MailText> {
        if (senders.isEmpty()) return emptyList()
        val after = SimpleDateFormat("yyyy/MM/dd", Locale.US).format(since)
        val ids = search("from:(${senders.joinToString(" OR ")}) after:$after", limit)
        return ids.mapIndexed { n, id ->
            progress(n + 1, ids.size)
            val msg = message(id)
            val all = parts(msg.getJSONObject("payload"))
            fun body(type: String) = all.firstOrNull { it.optString("mimeType") == type }?.optJSONObject("body")?.optString("data")
                ?.takeIf { it.isNotEmpty() }?.let { String(decode(it)) }
            val text = body("text/plain") ?: body("text/html")?.let(::htmlToText).orEmpty()
            MailText(id, header(msg, "From"), header(msg, "Subject"), msg.optString("internalDate").toLongOrNull() ?: 0, text)
        }.sortedBy { it.receivedAt }
    }

    companion object {
        /** Subject words that mark a statement email. */
        val KEYWORDS = listOf("statement", "stmt", "e-bill", "ebill", "estatement")
    }
}

// From CardKeep (github.com/santdas36/CardKeep, GPL-3.0), used under AGPL-3.0 (GPLv3 section 13).
private val dropBlocks = Regex("<(style|script|head)[^>]*>.*?</\\1>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val lineTags = Regex("<\\s*(br|/p|/div|/tr|/li|/h\\d|/table)[^>]*>", RegexOption.IGNORE_CASE)
private val cellTags = Regex("<\\s*/t[dh][^>]*>", RegexOption.IGNORE_CASE)
private val anyTag = Regex("<[^>]+>")
private val numericEntity = Regex("&#(x?)([0-9a-fA-F]+);")
private val namedEntities = mapOf("&nbsp;" to " ", "&amp;" to "&", "&lt;" to "<", "&gt;" to ">", "&quot;" to "\"", "&#39;" to "'", "&apos;" to "'", "&rupee;" to "₹")

/** HTML email to plain text: block ends become line breaks, table cells spaces, entities decoded. */
fun htmlToText(html: String): String {
    var s = dropBlocks.replace(html, " ")
    s = lineTags.replace(s, "\n")
    s = cellTags.replace(s, " ")
    s = anyTag.replace(s, "")
    s = numericEntity.replace(s) { m ->
        val code = m.groupValues[2].toIntOrNull(if (m.groupValues[1].isNotEmpty()) 16 else 10)
        code?.let { String(Character.toChars(it)) } ?: m.value
    }
    namedEntities.forEach { (k, v) -> s = s.replace(k, v, ignoreCase = true) }
    return s.lines().map { it.replace(Regex("[ \\t\\u00A0]+"), " ").trim() }.filter { it.isNotEmpty() }.joinToString("\n")
}
