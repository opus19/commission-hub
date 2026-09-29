package kim.opus.hub.model

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

object Wanted {
    val MIN: LocalDateTime = LocalDateTime.of(2000, 1, 1, 0, 0)
    val MAX: LocalDateTime = LocalDateTime.of(2099, 12, 31, 23, 59)

    private val storeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")
    private val shortFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")

    fun parse(raw: String?): LocalDateTime? {
        val text = raw?.trim()?.replace(' ', 'T')
        if (text.isNullOrEmpty()) return null
        return runCatching { LocalDateTime.parse(text).truncatedTo(ChronoUnit.MINUTES) }.getOrNull()
    }

    const val EXAMPLE = "2026-10-01 18:00"

    private val fullInput = Regex("""^(\d{4})-(\d{1,2})-(\d{1,2}) (\d{1,2}):(\d{1,2})(?::(\d{1,2}))?$""")
    private val dateOnlyInput = Regex("""^\d{4}-\d{1,2}-\d{1,2}$""")

    private fun normalizeInput(raw: String): String {
        val sb = StringBuilder()
        for (ch in raw.trim()) {
            when (ch) {
                in '０'..'９' -> sb.append('0' + (ch - '０'))
                '年', '月', '/', '.', '－' -> sb.append('-')
                '日', '号', 'T', 't', '\u3000' -> sb.append(' ')
                '时', '点', '：' -> sb.append(':')
                '分', '秒' -> Unit
                else -> sb.append(ch)
            }
        }
        return sb.toString()
            .replace(Regex("""\s*([-:])\s*"""), "$1")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    fun readInput(raw: String?): Pair<String?, String?> {
        val text = normalizeInput(raw.orEmpty())
        if (text.isEmpty()) return null to null
        if (dateOnlyInput.matches(text)) return null to "要填到几点几分，比如 $EXAMPLE"
        val m = fullInput.matchEntire(text) ?: return null to "格式不对，按 $EXAMPLE 这样填"
        val g = m.groupValues
        if (g[6].isNotEmpty() && g[6].toInt() > 59) return null to "没有这个日期或时间，检查一下"
        val t = runCatching { LocalDateTime.of(g[1].toInt(), g[2].toInt(), g[3].toInt(), g[4].toInt(), g[5].toInt()) }.getOrNull()
            ?: return null to "没有这个日期或时间，检查一下"
        if (!inRange(t)) return null to "要在 2000 年到 2099 年之间"
        return store(t) to null
    }

    fun inRange(t: LocalDateTime): Boolean = !t.isBefore(MIN) && !t.isAfter(MAX)

    fun store(t: LocalDateTime): String = storeFormat.format(t)

    fun full(stored: String?): String? = parse(stored)?.let { stampFormat.format(it) }

    fun short(stored: String?): String? {
        val t = parse(stored) ?: return null
        return if (t.year == LocalDateTime.now(zone).year) shortFormat.format(t) else stampFormat.format(t)
    }

    fun overdue(stored: String?, status: ReqStatus): Boolean {
        if (status == ReqStatus.ARCHIVED) return false
        val t = parse(stored) ?: return false
        return t.isBefore(LocalDateTime.now(zone))
    }
}
