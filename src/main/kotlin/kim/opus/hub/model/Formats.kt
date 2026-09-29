package kim.opus.hub.model

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal val zone: ZoneId = ZoneId.systemDefault()
internal val stampFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
private val dayFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")
private val dateOnlyFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

fun nowIso(): String = Instant.now().toString()

fun formatStamp(iso: String?): String {
    if (iso.isNullOrBlank()) return "-"
    return runCatching { stampFormat.format(Instant.parse(iso).atZone(zone)) }.getOrDefault(iso)
}

fun formatWhen(iso: String?): String {
    if (iso.isNullOrBlank()) return "-"
    val t = runCatching { Instant.parse(iso).atZone(zone) }.getOrNull() ?: return iso
    return if (t.year == LocalDateTime.now(zone).year) dayFormat.format(t) else stampFormat.format(t)
}

fun formatShort(iso: String?): String {
    if (iso.isNullOrBlank()) return "-"
    return runCatching { dayFormat.format(Instant.parse(iso).atZone(zone)) }.getOrDefault(iso)
}

fun formatRelative(iso: String?): String {
    if (iso.isNullOrBlank()) return "-"
    val then = runCatching { Instant.parse(iso) }.getOrNull() ?: return iso
    val seconds = Instant.now().epochSecond - then.epochSecond
    return when {
        seconds < 60 -> "刚刚"
        seconds < 3600 -> "${seconds / 60} 分钟前"
        seconds < 86400 -> "${seconds / 3600} 小时前"
        seconds < 86400 * 30 -> "${seconds / 86400} 天前"
        else -> formatStamp(iso)
    }
}

fun formatCreated(iso: String?): String {
    if (iso.isNullOrBlank()) return "-"
    val then = runCatching { Instant.parse(iso) }.getOrNull() ?: return iso
    val seconds = Instant.now().epochSecond - then.epochSecond
    return if (seconds < 86400) formatRelative(iso) else dateOnlyFormat.format(then.atZone(zone))
}

fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
}
