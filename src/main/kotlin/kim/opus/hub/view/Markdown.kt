package kim.opus.hub.view

import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.AbstractVisitor
import org.commonmark.node.Image
import org.commonmark.node.Link
import org.commonmark.node.Node
import org.commonmark.node.Text
import org.commonmark.parser.Parser
import org.commonmark.parser.PostProcessor
import org.commonmark.renderer.html.AttributeProvider
import org.commonmark.renderer.html.DefaultUrlSanitizer
import org.commonmark.renderer.html.HtmlRenderer

object Markdown {
    private val extensions = listOf(TablesExtension.create(), StrikethroughExtension.create())

    private val parser: Parser = Parser.builder()
        .extensions(extensions)
        .postProcessor(UrlLinker)
        .build()

    private val renderer: HtmlRenderer = HtmlRenderer.builder()
        .extensions(extensions)
        .escapeHtml(true)
        .sanitizeUrls(true)
        .urlSanitizer(DefaultUrlSanitizer(listOf("http", "https", "mailto")))
        .softbreak("<br>\n")
        .attributeProviderFactory { LinkAttributes }
        .build()

    fun render(text: String?): String {
        if (text.isNullOrBlank()) return ""
        return renderer.render(parser.parse(text.replace("\r\n", "\n")))
    }

    private object LinkAttributes : AttributeProvider {
        override fun setAttributes(node: Node, tagName: String, attributes: MutableMap<String, String>) {
            when (node) {
                is Link -> {
                    val href = attributes["href"].orEmpty().lowercase()
                    if (href.startsWith("http://") || href.startsWith("https://") || href.startsWith("//")) {
                        attributes["target"] = "_blank"
                        attributes["rel"] = "noopener noreferrer nofollow"
                    }
                }
                is Image -> attributes["loading"] = "lazy"
            }
        }
    }

    private object UrlLinker : PostProcessor {
        private val pattern = Regex("""https?://[A-Za-z0-9\-._~:/?#\[\]@!$&'()*+,;=%]+""")
        private const val TRAILING = ".,;:!?)'"

        override fun process(node: Node): Node {
            val texts = ArrayList<Text>()
            node.accept(object : AbstractVisitor() {
                override fun visit(link: Link) {}
                override fun visit(image: Image) {}
                override fun visit(text: Text) {
                    texts.add(text)
                }
            })
            texts.forEach(::linkify)
            return node
        }

        private fun linkify(node: Text) {
            val literal = node.literal
            var last = 0
            for (m in pattern.findAll(literal)) {
                if (m.range.first < last) continue
                var url = m.value
                while (url.length > 8 && url.last() in TRAILING) url = url.dropLast(1)
                if (url.substringAfter("://").isEmpty()) continue
                if (m.range.first > last) node.insertBefore(Text(literal.substring(last, m.range.first)))
                val link = Link(url, null)
                link.appendChild(Text(url))
                node.insertBefore(link)
                last = m.range.first + url.length
            }
            if (last == 0) return
            if (last < literal.length) node.insertBefore(Text(literal.substring(last)))
            node.unlink()
        }
    }
}
