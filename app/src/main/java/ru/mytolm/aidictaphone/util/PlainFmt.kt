package ru.mytolm.aidictaphone.util

/**
 * Конвертация Markdown саммари в plain text «как в текстовом редакторе»:
 * заголовки — ПРОПИСНЫМИ, списки — «•», таблицы — выровненные колонки,
 * инлайн-разметка (**, *, _, `, ссылки) снята. Сырой md остаётся в БД.
 */
object PlainFmt {

    private val headingLine = Regex("^\\s*(#{1,6})?\\s*\\*\\*(.+?)\\*\\*\\s*[:.]?\\s*$")
    private val hashHeading = Regex("^\\s*#{1,6}\\s+(.+)$")
    private val bullet = Regex("^(\\s*)[-*•]\\s+")
    private val quote = Regex("^\\s*>\\s?")
    private val rule = Regex("^\\s*(-{3,}|\\*{3,}|_{3,})\\s*$")
    private val bold = Regex("\\*\\*(.+?)\\*\\*")
    private val italic = Regex("(?<!\\*)\\*([^*\\s][^*]*?)\\*|(?<![\\w_])_([^_\\s][^_]*?)_(?![\\w_])")
    private val code = Regex("`([^`]*)`")
    private val link = Regex("\\[([^\\]]+)]\\(([^)\\s]+)\\)")

    fun toPlainText(md: String): String {
        val lines = md.lines()
        val out = ArrayList<String>()
        var i = 0
        while (i < lines.size) {
            val table = collectTable(lines, i)
            if (table != null) { out += renderTable(table.first); i = table.second }
            else { out += renderLine(lines[i]); i++ }
        }
        return out.joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim()
    }

    private fun isRow(l: String) = l.trim().startsWith("|")

    /** Блок строк-таблицы с i; null если не таблица. Возвращает строки без separator. */
    private fun collectTable(lines: List<String>, from: Int): Pair<List<List<String>>, Int>? {
        if (!isRow(lines[from])) return null
        var i = from
        val rows = ArrayList<List<String>>()
        while (i < lines.size && isRow(lines[i])) {
            val cells = lines[i].trim().trim('|').split('|')
                .map { inline(it.trim()) }
            val isSeparator = cells.isNotEmpty() &&
                cells.all { it.isBlank() || it.matches(Regex("[:\\-\\s]+")) }
            if (!isSeparator) rows.add(cells)
            i++
        }
        if (rows.isEmpty()) return null
        return rows to i
    }

    private fun renderTable(rows: List<List<String>>): List<String> {
        val cols = rows.maxOf { it.size }
        val grid = rows.map { it + List(cols - it.size) { "" } }
        val w = IntArray(cols) { c -> grid.maxOf { it[c].length }.coerceAtLeast(3) }
        val out = ArrayList<String>()
        grid.forEachIndexed { r, row ->
            out += row.indices.joinToString(" | ") { c -> row[c].padEnd(w[c]) }.trimEnd()
            if (r == 0) out += w.joinToString("-+-") { "-".repeat(it) }
        }
        return out
    }

    private fun renderLine(raw: String): String {
        if (raw.isBlank()) return raw
        if (rule.matches(raw)) return ""
        headingLine.find(raw)?.let { return it.groupValues[2].uppercase() }
        hashHeading.find(raw)?.let { return inline(it.groupValues[1]).uppercase() }
        bullet.find(raw)?.let { m ->
            return m.groupValues[1] + "• " + inline(raw.removeRange(m.range))
        }
        quote.find(raw)?.let { m ->
            return "  " + inline(raw.removeRange(m.range))
        }
        return inline(raw)
    }

    private fun inline(s: String): String =
        s.replace(link) { "${it.groupValues[1]} (${it.groupValues[2]})" }
            .replace(bold) { it.groupValues[1] }
            .replace(italic) { it.groupValues[1].ifBlank { it.groupValues[2] } }
            .replace(code) { it.groupValues[1] }
}
