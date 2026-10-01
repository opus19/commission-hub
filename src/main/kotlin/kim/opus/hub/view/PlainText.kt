package kim.opus.hub.view

object PlainText {
    private val url = Regex("""https?://[A-Za-z0-9\-._~:/?#\[\]@!$&'()*+,;=%]+""")
    private const val TRAILING = ".,;:!?)'"

    fun render(text: String?): String {
        if (text.isNullOrBlank()) return ""
        val src = text.replace("\r\n", "\n")
        val out = StringBuilder(src.length + 32)
        var last = 0
        for (m in url.findAll(src)) {
            var link = m.value
            while (link.length > 8 && link.last() in TRAILING) link = link.dropLast(1)
            if (link.substringAfter("://").isEmpty()) continue
            out.append(e(src.substring(last, m.range.first)))
            out.append("""<a href="${e(link)}" target="_blank" rel="noopener noreferrer nofollow">${e(link)}</a>""")
            last = m.range.first + link.length
        }
        out.append(e(src.substring(last)))
        return out.toString()
    }
}
