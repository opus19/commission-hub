package kim.opus.hub.model

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object Wanted {
    const val PLACEHOLDER = "如 2026-10-01 或 2026-10-01 18:00"

    private const val MIN_YEAR = 2000
    private const val MAX_YEAR = 2099

    private enum class Precision { YEAR, MONTH, DAY, MINUTE }

    private class Point(val start: LocalDateTime, val precision: Precision) {
        val end: LocalDateTime
            get() = when (precision) {
                Precision.YEAR -> start.plusYears(1)
                Precision.MONTH -> start.plusMonths(1)
                Precision.DAY -> start.plusDays(1)
                Precision.MINUTE -> start
            }
    }

    private val monthFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM")
    private val dayFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val dayShortFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd")
    private val minuteStoreFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")
    private val minuteShortFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")

    private val storedPattern = Regex("""^(\d{4})(?:-(\d{2})(?:-(\d{2})(?:[T ](\d{2}):(\d{2})(?::\d{2})?)?)?)?$""")
    private val inputPattern = Regex("""^(\d{4})(?:-(\d{1,2})(?:-(\d{1,2})(?: (\d{1,2})(?::(\d{1,2})(?::(\d{1,2}))?)?)?)?)?$""")

    private fun point(year: Int, month: String, day: String, hour: String, minute: String): Point? = runCatching {
        when {
            month.isEmpty() -> Point(LocalDateTime.of(year, 1, 1, 0, 0), Precision.YEAR)
            day.isEmpty() -> Point(LocalDateTime.of(year, month.toInt(), 1, 0, 0), Precision.MONTH)
            hour.isEmpty() -> Point(LocalDateTime.of(year, month.toInt(), day.toInt(), 0, 0), Precision.DAY)
            else -> Point(LocalDateTime.of(year, month.toInt(), day.toInt(), hour.toInt(), minute.ifEmpty { "0" }.toInt()), Precision.MINUTE)
        }
    }.getOrNull()

    private fun parse(stored: String?): Point? {
        val g = storedPattern.matchEntire(stored?.trim().orEmpty())?.groupValues ?: return null
        return point(g[1].toInt(), g[2], g[3], g[4], g[5])
    }

    private fun store(p: Point): String = when (p.precision) {
        Precision.MINUTE -> minuteStoreFormat.format(p.start)
        else -> text(p)
    }

    private fun text(p: Point): String = when (p.precision) {
        Precision.YEAR -> p.start.year.toString()
        Precision.MONTH -> monthFormat.format(p.start)
        Precision.DAY -> dayFormat.format(p.start)
        Precision.MINUTE -> stampFormat.format(p.start)
    }

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
            .trimEnd('-', ':', ' ')
    }

    fun readInput(raw: String?): Pair<String?, String?> {
        val text = normalizeInput(raw.orEmpty())
        if (text.isEmpty()) return null to null
        val g = inputPattern.matchEntire(text)?.groupValues
            ?: return null to "格式不对，按 2026-10-01 或 2026-10-01 18:00 这样填"
        val year = g[1].toInt()
        if (year !in MIN_YEAR..MAX_YEAR) return null to "要在 2000 年到 2099 年之间"
        if (g[6].isNotEmpty() && g[6].toInt() > 59) return null to "没有这个日期或时间，检查一下"
        val p = point(year, g[2], g[3], g[4], g[5]) ?: return null to "没有这个日期或时间，检查一下"
        return store(p) to null
    }

    fun full(stored: String?): String? = parse(stored)?.let { text(it) }

    fun short(stored: String?): String? {
        val p = parse(stored) ?: return null
        if (p.start.year != LocalDateTime.now(zone).year) return text(p)
        return when (p.precision) {
            Precision.DAY -> dayShortFormat.format(p.start)
            Precision.MINUTE -> minuteShortFormat.format(p.start)
            else -> text(p)
        }
    }

    fun overdue(stored: String?, status: ReqStatus): Boolean {
        if (status == ReqStatus.ARCHIVED) return false
        val p = parse(stored) ?: return false
        return p.end.isBefore(LocalDateTime.now(zone))
    }
}
