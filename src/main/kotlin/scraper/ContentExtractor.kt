package scraper

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger(ContentExtractor::class.java)

class ContentExtractor(
    private val mainContentSelectors: List<String> = DEFAULT_MAIN_CONTENT_SELECTORS,
    private val markdownConverter: AnnotatedMarkdownConverter = AnnotatedMarkdownConverter(),
) {

    fun extract(html: String, baseUrl: String, transformUrl: (String) -> String = { it }): String {
        val document = Jsoup.parse(html, baseUrl)
        removeInvisibleElements(document)
        val mainContent = extractMainContent(document)
        return markdownConverter.convert(mainContent, transformUrl)
    }

    private fun removeInvisibleElements(document: Document) {
        document.select("script, style, noscript, template, meta, link, img").remove()
        document.select("[hidden]").remove()
        document.select("[style~=(?i)display\\s*:\\s*none]").remove()
        document.select("[style~=(?i)visibility\\s*:\\s*hidden]").remove()
        document.select("[aria-hidden=true]").remove()
    }

    private fun extractMainContent(document: Document): Document {
        val matches = mutableListOf<Element>()
        for (selector in mainContentSelectors) {
            matches.addAll(document.select(selector))
        }

        // Remove ancestors when nested matches exist (keep innermost)
        val deduplicated = matches.filter { candidate ->
            matches.none { other -> other !== candidate && candidate.isAncestorOf(other) }
        }

        return when {
            deduplicated.size > 1 -> throw IllegalStateException(
                "Found ${deduplicated.size} main content elements matching selectors $mainContentSelectors"
            )
            deduplicated.size == 1 -> {
                val element = deduplicated.first()
                Document(document.baseUri()).also { it.body().appendChild(element.clone()) }
            }
            else -> {
                logger.warn("No main content element found, returning full document")
                document
            }
        }
    }

    private fun Element.isAncestorOf(other: Element): Boolean {
        var parent = other.parent()
        while (parent != null) {
            if (parent === this) return true
            parent = parent.parent()
        }
        return false
    }

    companion object {
        val DEFAULT_MAIN_CONTENT_SELECTORS = listOf(
            "main",
            "[role=main]",
            "#content",
            "#content-holder",
            "#main-content",
            ".content-area",
        )
    }
}
