// From CardKeep (github.com/santdas36/CardKeep, GPL-3.0), used under AGPL-3.0 (GPLv3 section 13).
package app.cardwallet.statement

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** What Indian issuers build statement passwords from. Stored encrypted; never leaves the phone. */
data class PdfProfile(
    val name: String = "",
    val dob: LocalDate? = null,
    val pan: String = "",
    val mobile: String = "",
    val templates: List<String> = emptyList(),
) {
    fun toJson(): String = JSONObject()
        .put("name", name).put("dob", dob?.toString() ?: "").put("pan", pan).put("mobile", mobile)
        .put("templates", JSONArray(templates)).toString()

    companion object {
        fun fromJson(s: String?): PdfProfile {
            if (s.isNullOrBlank()) return PdfProfile()
            val o = JSONObject(s)
            val t = o.optJSONArray("templates")
            return PdfProfile(
                name = o.optString("name"),
                dob = o.optString("dob").takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
                pan = o.optString("pan"),
                mobile = o.optString("mobile"),
                templates = if (t == null) emptyList() else List(t.length()) { t.getString(it) },
            )
        }
    }
}

object Passwords {
    /** Tokens users can put in their own templates. */
    val TOKENS = listOf(
        "{NAME4}" to "first 4 letters of name, caps",
        "{name4}" to "first 4 letters of name, lowercase",
        "{DDMM}" to "birth day and month",
        "{DDMMYY}" to "date of birth, 2-digit year",
        "{DDMMYYYY}" to "date of birth",
        "{LAST4}" to "card's last 4 digits",
        "{PAN}" to "PAN, caps",
        "{MOBILE4}" to "last 4 digits of mobile",
    )

    /**
     * Patterns Indian issuers commonly use. Every one is tried, so it does not matter which bank
     * uses which; a wrong guess costs a few milliseconds.
     */
    private val BUILT_IN = listOf(
        "{NAME4}{DDMM}", "{name4}{DDMM}", "{Name4}{DDMM}",
        "{DDMMYYYY}{LAST4}", "{DDMMYY}{LAST4}", "{LAST4}{DDMMYYYY}",
        "{NAME4}{DDMMYY}", "{name4}{DDMMYY}", "{NAME4}{LAST4}", "{name4}{LAST4}",
        "{DDMMYYYY}", "{DDMMYY}", "{DDMM}{LAST4}",
        "{PAN}", "{pan}", "{name4}{MOBILE4}", "{NAME4}{MOBILE4}", "{MOBILE}",
    )

    /**
     * Works out how a password that opened a statement is built from your details: the token
     * patterns (1 to 3 tokens) that produce exactly [password]. Empty when none fits.
     * Added for Card Wallet: learn a bank's pattern from one password you type.
     */
    fun inferTemplates(password: String, profile: PdfProfile, last4s: Collection<String>): List<String> {
        val tokens = (tokenValues(profile) + last4s.map { "{LAST4}" to it }).filter { it.second.isNotEmpty() }
        val found = LinkedHashSet<String>()
        fun walk(rest: String, template: String, depth: Int) {
            if (rest.isEmpty()) { found += template; return }
            if (depth == 3) return
            for ((t, v) in tokens) if (rest.startsWith(v)) walk(rest.removePrefix(v), template + t, depth + 1)
        }
        walk(password, "", 0)
        return found.sortedBy { it.length }
    }

    private fun tokenValues(profile: PdfProfile): List<Pair<String, String>> {
        val first = profile.name.trim().split(Regex("\\s+")).firstOrNull().orEmpty().filter(Char::isLetter)
        val n4 = first.take(4)
        val dob = profile.dob
        return listOf(
            "{NAME4}" to n4.uppercase(), "{name4}" to n4.lowercase(), "{Name4}" to n4.lowercase().replaceFirstChar { it.uppercase() },
            "{DDMM}" to (dob?.format(DateTimeFormatter.ofPattern("ddMM")) ?: ""),
            "{DDMMYY}" to (dob?.format(DateTimeFormatter.ofPattern("ddMMyy")) ?: ""),
            "{DDMMYYYY}" to (dob?.format(DateTimeFormatter.ofPattern("ddMMyyyy")) ?: ""),
            "{PAN}" to profile.pan.trim().uppercase(), "{pan}" to profile.pan.trim().lowercase(),
            "{MOBILE4}" to profile.mobile.filter(Char::isDigit).takeLast(4), "{MOBILE}" to profile.mobile.filter(Char::isDigit).takeLast(10),
        )
    }

    fun candidates(profile: PdfProfile, last4s: Collection<String>): List<String> {
        val first = profile.name.trim().split(Regex("\\s+")).firstOrNull().orEmpty().filter(Char::isLetter)
        val n4 = first.take(4)
        val dob = profile.dob
        val base = mapOf(
            "{NAME4}" to n4.uppercase(),
            "{name4}" to n4.lowercase(),
            "{Name4}" to n4.lowercase().replaceFirstChar { it.uppercase() },
            "{DDMM}" to (dob?.format(DateTimeFormatter.ofPattern("ddMM")) ?: ""),
            "{DDMMYY}" to (dob?.format(DateTimeFormatter.ofPattern("ddMMyy")) ?: ""),
            "{DDMMYYYY}" to (dob?.format(DateTimeFormatter.ofPattern("ddMMyyyy")) ?: ""),
            "{PAN}" to profile.pan.trim().uppercase(),
            "{pan}" to profile.pan.trim().lowercase(),
            "{MOBILE4}" to profile.mobile.filter(Char::isDigit).takeLast(4),
            "{MOBILE}" to profile.mobile.filter(Char::isDigit).takeLast(10),
        )
        val out = LinkedHashSet<String>()
        val lasts = last4s.ifEmpty { listOf("") }
        for (t in profile.templates + BUILT_IN) {
            for (l4 in lasts) {
                val values = base + ("{LAST4}" to l4)
                // Skip a template if any token it uses has no value.
                if (values.any { (k, v) -> t.contains(k) && v.isEmpty() }) continue
                var s = t
                values.forEach { (k, v) -> s = s.replace(k, v) }
                if (s.isNotBlank() && !s.contains('{')) out += s
            }
        }
        return out.toList()
    }
}
