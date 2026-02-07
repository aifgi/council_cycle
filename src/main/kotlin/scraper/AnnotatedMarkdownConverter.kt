package scraper

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

class AnnotatedMarkdownConverter {

    private var urlRegistry = UrlRegistry()

    fun convert(document: Document): ConversionResult {
        urlRegistry = UrlRegistry()
        val sb = StringBuilder()
        convertChildren(document.body(), sb)
        return ConversionResult(sb.toString().trim(), urlRegistry)
    }

    private fun ensureBlankLine(sb: StringBuilder) {
        if (sb.isEmpty()) return
        if (!sb.endsWith("\n")) {
            sb.append("\n\n")
        } else if (!sb.endsWith("\n\n")) {
            sb.append("\n")
        }
    }

    private fun convertChildren(element: Element, sb: StringBuilder) {
        for (child in element.childNodes()) {
            convertNode(child, sb)
        }
    }

    private fun convertNode(node: Node, sb: StringBuilder) {
        when (node) {
            is TextNode -> {
                val text = node.text().let { if (node.parent()?.nodeName() == "pre") node.wholeText else it }
                if (text.isNotBlank()) {
                    sb.append(text)
                }
            }
            is Element -> convertElement(node, sb)
        }
    }

    private fun convertElement(element: Element, sb: StringBuilder) {
        when (element.tagName()) {
            "h1" -> appendHeading(element, sb, "#")
            "h2" -> appendHeading(element, sb, "##")
            "h3" -> appendHeading(element, sb, "###")
            "h4" -> appendHeading(element, sb, "####")
            "h5" -> appendHeading(element, sb, "#####")
            "h6" -> appendHeading(element, sb, "######")
            "p" -> appendBlock(element, sb)
            "br" -> sb.append("\n")
            "a" -> appendLink(element, sb)
            "ul" -> appendList(element, sb, ordered = false)
            "ol" -> appendList(element, sb, ordered = true)
            "table" -> appendTable(element, sb)
            "blockquote" -> appendBlockquote(element, sb)
            "pre" -> appendPreformatted(element, sb)
            "strong", "b" -> {
                sb.append("**")
                convertChildren(element, sb)
                sb.append("**")
            }
            "em", "i" -> {
                sb.append("*")
                convertChildren(element, sb)
                sb.append("*")
            }
            "code" -> {
                sb.append("`")
                convertChildren(element, sb)
                sb.append("`")
            }
            "hr" -> { ensureBlankLine(sb); sb.append("---"); ensureBlankLine(sb) }
            else -> convertChildren(element, sb)
        }
    }

    private fun appendHeading(element: Element, sb: StringBuilder, prefix: String) {
        ensureBlankLine(sb)
        sb.append(prefix)
        sb.append(" ")
        convertChildren(element, sb)
        ensureBlankLine(sb)
    }

    private fun appendBlock(element: Element, sb: StringBuilder) {
        ensureBlankLine(sb)
        convertChildren(element, sb)
        ensureBlankLine(sb)
    }

    private fun appendLink(element: Element, sb: StringBuilder) {
        val href = element.absUrl("href")
        if (href.isBlank()) {
            convertChildren(element, sb)
            return
        }
        sb.append("[")
        convertChildren(element, sb)
        sb.append("](")
        sb.append(urlRegistry.register(href))
        sb.append(")")
    }

    private fun appendList(element: Element, sb: StringBuilder, ordered: Boolean) {
        ensureBlankLine(sb)
        val items = element.children().filter { it.tagName() == "li" }
        for ((index, item) in items.withIndex()) {
            val prefix = if (ordered) "${index + 1}. " else "- "
            sb.append(prefix)
            convertChildren(item, sb)
            sb.append("\n")
        }
        ensureBlankLine(sb)
    }

    private fun appendTable(element: Element, sb: StringBuilder) {
        val headers = element.select("thead th, thead td, tr:first-child th")
            .map { it.text().trim() }

        val rows = element.select("tbody tr").ifEmpty {
            val allRows = element.select("tr")
            if (headers.isNotEmpty()) allRows.drop(1) else allRows
        }

        ensureBlankLine(sb)
        sb.append("[Table]\n")

        for ((rowIndex, row) in rows.withIndex()) {
            val cells = row.select("td, th")
            sb.append("Row ${rowIndex + 1}:")
            for ((colIndex, cell) in cells.withIndex()) {
                val columnLabel = if (colIndex < headers.size && headers[colIndex].isNotBlank()) {
                    headers[colIndex]
                } else {
                    "Column ${colIndex + 1}"
                }
                val cellContent = StringBuilder()
                convertChildren(cell, cellContent)
                val value = cellContent.toString().trim()
                if (colIndex > 0) sb.append(" |")
                sb.append(" [$columnLabel] $value")
            }
            sb.append("\n")
        }
        ensureBlankLine(sb)
    }

    private fun appendBlockquote(element: Element, sb: StringBuilder) {
        ensureBlankLine(sb)
        val content = StringBuilder()
        convertChildren(element, content)
        content.toString().trim().lines().forEach { line ->
            sb.append("> ")
            sb.append(line)
            sb.append("\n")
        }
        ensureBlankLine(sb)
    }

    private fun appendPreformatted(element: Element, sb: StringBuilder) {
        ensureBlankLine(sb)
        sb.append("```\n")
        sb.append(element.wholeText())
        sb.append("\n```")
        ensureBlankLine(sb)
    }
}
