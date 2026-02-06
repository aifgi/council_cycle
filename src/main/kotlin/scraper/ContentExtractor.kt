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

    fun extract(html: String): String {
        val document = Jsoup.parse(html)
        removeInvisibleElements(document)
        val mainContent = extractMainContent(document)
        return markdownConverter.convert(mainContent)
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

        return when {
            matches.size > 1 -> throw IllegalStateException(
                "Found ${matches.size} main content elements matching selectors $mainContentSelectors"
            )
            matches.size == 1 -> {
                val element = matches.first()
                Document(document.baseUri()).also { it.body().appendChild(element.clone()) }
            }
            else -> {
                logger.warn("No main content element found, returning full document")
                document
            }
        }
    }

    companion object {
        val DEFAULT_MAIN_CONTENT_SELECTORS = listOf(
            "main",
            "[role=main]",
            "#content",
            "#main-content",
            ".content-area",
        )
    }
}
