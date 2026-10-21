package com.lita.app.i18n

/** A language the user can switch to. English is the default; add a language by adding an entry and a string table. */
data class AppLanguage(val code: String, val nativeName: String, val rtl: Boolean)

object AppLanguages {
    val all = listOf(
        AppLanguage("en", "English", false),
        AppLanguage("fa", "فارسی", true),
    )
    fun nativeName(code: String) = all.firstOrNull { it.code == code }?.nativeName ?: "English"
    fun isRtl(code: String) = all.firstOrNull { it.code == code }?.rtl ?: false
    fun isKnown(code: String) = all.any { it.code == code }
}

/**
 * A translation pattern. English side placeholders: `{}` = any text, `{#}` = a number.
 * Output side placeholders: `{1}`, `{2}` ... (1-based, in order of appearance).
 */
class Template(english: String, val out: String) {
    private val regex: Regex
    private val numeric: List<Boolean>

    init {
        val sb = StringBuilder("^")
        val kinds = ArrayList<Boolean>()
        var i = 0
        while (i < english.length) {
            if (english.startsWith("{#}", i)) { sb.append("(\\d+)"); kinds.add(true); i += 3 }
            else if (english.startsWith("{}", i)) { sb.append("(.+?)"); kinds.add(false); i += 2 }
            else {
                var j = i
                while (j < english.length && !english.startsWith("{#}", j) && !english.startsWith("{}", j)) j++
                sb.append(Regex.escape(english.substring(i, j)))
                i = j
            }
        }
        sb.append("$")
        regex = Regex(sb.toString(), RegexOption.DOT_MATCHES_ALL)
        numeric = kinds
    }

    val placeholderCount: Int get() = numeric.size

    fun apply(s: String, inner: (String) -> String): String? {
        val m = regex.matchEntire(s) ?: return null
        return INDEX.replace(out) { r ->
            val idx = r.groupValues[1].toInt()
            val g = m.groupValues.getOrNull(idx) ?: ""
            if (numeric.getOrNull(idx - 1) == true) g else inner(g)
        }
    }

    private companion object { val INDEX = Regex("\\{(\\d+)\\}") }
}

class LangData(val exact: Map<String, String>, val templates: List<Template>)

/**
 * Display-time translation. The code base keeps its English strings; the UI's Text passes them through [tr].
 * Order: exact match, then pattern match, then sentence by sentence. Unknown text (file names, numbers) stays unchanged.
 */
object I18n {
    @Volatile var language: String = "en"

    private val tables: Map<String, LangData> = mapOf("fa" to FaStrings.data)
    private val SPLIT = Regex("(?<=[.!?…])\\s+(?=[A-Z0-9“\"])")

    fun tr(s: String): String = tr(s, language)

    fun tr(s: String, lang: String): String {
        if (s.isEmpty() || lang == "en") return s
        val data = tables[lang] ?: return s
        return translate(s, data, 0)
    }

    private fun translate(s: String, data: LangData, depth: Int): String {
        if (depth > 3) return s
        val core = s.trim()
        if (core.isEmpty()) return s
        val lead = s.takeWhile { it.isWhitespace() }
        val trail = s.takeLastWhile { it.isWhitespace() }
        data.exact[core]?.let { return lead + it + trail }
        // Several sentences (messages are often concatenated): translate each sentence on its own.
        val parts = SPLIT.split(core)
        if (parts.size > 1) {
            var any = false
            val out = parts.map { p -> (data.exact[p] ?: template(p, data, depth))?.also { any = true } ?: p }
            if (any) return lead + out.joinToString(" ") + trail
        }
        template(core, data, depth)?.let { return lead + it + trail }
        return s
    }

    private fun template(s: String, data: LangData, depth: Int): String? =
        data.templates.firstNotNullOfOrNull { t -> t.apply(s) { inner -> translate(inner, data, depth + 1) } }
}
