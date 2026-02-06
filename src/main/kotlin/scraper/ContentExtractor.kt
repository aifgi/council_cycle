package scraper

import org.jsoup.Jsoup
import org.jsoup.nodes.Document

class ContentExtractor {

    fun extract(html: String): Document {
        val document = Jsoup.parse(html)
        removeInvisibleElements(document)
        return document
    }

    private fun removeInvisibleElements(document: Document) {
        document.select("script, style, noscript, template, meta, link").remove()
        document.select("[hidden]").remove()
        document.select("[style~=(?i)display\\s*:\\s*none]").remove()
        document.select("[style~=(?i)visibility\\s*:\\s*hidden]").remove()
        document.select("[aria-hidden=true]").remove()
    }
}
