package scraper

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

class AnnotatedMarkdownConverter {

    fun convert(document: Document, transformUrl: (String) -> String = { it }): String {
        val sb = StringBuilder()
        convertChildren(document.body(), sb, transformUrl)
        return sb.toString().replace(Regex("\n{3,}"), "\n\n").trim()
    }

    private fun convertChildren(element: Element, sb: StringBuilder, transformUrl: (String) -> String) {
        for (child in element.childNodes()) {
            convertNode(child, sb, transformUrl)
        }
    }

    private fun convertNode(node: Node, sb: StringBuilder, transformUrl: (String) -> String) {
        when (node) {
            is TextNode -> {
                val text = node.text().let { if (node.parent()?.nodeName() == "pre") node.wholeText else it }
                if (text.isNotBlank()) {
                    sb.append(text)
                }
            }
            is Element -> convertElement(node, sb, transformUrl)
        }
    }

    private fun convertElement(element: Element, sb: StringBuilder, transformUrl: (String) -> String) {
        when (element.tagName()) {
            "h1" -> appendHeading(element, sb, "#", transformUrl)
            "h2" -> appendHeading(element, sb, "##", transformUrl)
            "h3" -> appendHeading(element, sb, "###", transformUrl)
            "h4" -> appendHeading(element, sb, "####", transformUrl)
            "h5" -> appendHeading(element, sb, "#####", transformUrl)
            "h6" -> appendHeading(element, sb, "######", transformUrl)
            "p" -> appendBlock(element, sb, transformUrl)
            "br" -> sb.append("\n")
            "a" -> appendLink(element, sb, transformUrl)
            "ul" -> appendList(element, sb, ordered = false, transformUrl)
            "ol" -> appendList(element, sb, ordered = true, transformUrl)
            "table" -> appendTable(element, sb)
            "blockquote" -> appendBlockquote(element, sb, transformUrl)
            "pre" -> appendPreformatted(element, sb)
            "strong", "b" -> {
                sb.append("**")
                convertChildren(element, sb, transformUrl)
                sb.append("**")
            }
            "em", "i" -> {
                sb.append("*")
                convertChildren(element, sb, transformUrl)
                sb.append("*")
            }
            "code" -> {
                sb.append("`")
                convertChildren(element, sb, transformUrl)
                sb.append("`")
            }
            "hr" -> sb.append("\n\n---\n\n")
            else -> convertChildren(element, sb, transformUrl)
        }
    }

    private fun appendHeading(element: Element, sb: StringBuilder, prefix: String, transformUrl: (String) -> String) {
        sb.append("\n\n")
        sb.append(prefix)
        sb.append(" ")
        convertChildren(element, sb, transformUrl)
        sb.append("\n\n")
    }

    private fun appendBlock(element: Element, sb: StringBuilder, transformUrl: (String) -> String) {
        sb.append("\n\n")
        convertChildren(element, sb, transformUrl)
        sb.append("\n\n")
    }

    private fun appendLink(element: Element, sb: StringBuilder, transformUrl: (String) -> String) {
        val href = element.absUrl("href")
        if (href.isBlank()) {
            convertChildren(element, sb, transformUrl)
            return
        }
        sb.append("[")
        convertChildren(element, sb, transformUrl)
        sb.append("](")
        sb.append(transformUrl(href))
        sb.append(")")
    }

    private fun appendList(element: Element, sb: StringBuilder, ordered: Boolean, transformUrl: (String) -> String) {
        sb.append("\n\n")
        val items = element.children().filter { it.tagName() == "li" }
        for ((index, item) in items.withIndex()) {
            val prefix = if (ordered) "${index + 1}. " else "- "
            sb.append(prefix)
            convertChildren(item, sb, transformUrl)
            sb.append("\n")
        }
        sb.append("\n")
    }

    private fun appendTable(element: Element, sb: StringBuilder) {
        val headers = element.select("thead th, thead td, tr:first-child th")
            .map { it.text().trim() }

        val rows = element.select("tbody tr").ifEmpty {
            val allRows = element.select("tr")
            if (headers.isNotEmpty()) allRows.drop(1) else allRows
        }

        sb.append("\n\n[Table]\n")

        for ((rowIndex, row) in rows.withIndex()) {
            val cells = row.select("td, th")
            sb.append("Row ${rowIndex + 1}:")
            for ((colIndex, cell) in cells.withIndex()) {
                val columnLabel = if (colIndex < headers.size && headers[colIndex].isNotBlank()) {
                    headers[colIndex]
                } else {
                    "Column ${colIndex + 1}"
                }
                val value = cell.text().trim()
                if (colIndex > 0) sb.append(" |")
                sb.append(" [$columnLabel] $value")
            }
            sb.append("\n")
        }
        sb.append("\n")
    }

    private fun appendBlockquote(element: Element, sb: StringBuilder, transformUrl: (String) -> String) {
        sb.append("\n\n")
        val content = StringBuilder()
        convertChildren(element, content, transformUrl)
        content.toString().trim().lines().forEach { line ->
            sb.append("> ")
            sb.append(line)
            sb.append("\n")
        }
        sb.append("\n")
    }

    private fun appendPreformatted(element: Element, sb: StringBuilder) {
        sb.append("\n\n```\n")
        sb.append(element.wholeText())
        sb.append("\n```\n\n")
    }
}
