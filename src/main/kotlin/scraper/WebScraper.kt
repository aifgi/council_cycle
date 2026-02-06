package scraper

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger(WebScraper::class.java)

class WebScraper(
    private val httpClient: HttpClient,
    private val contentExtractor: ContentExtractor,
) {
    suspend fun fetch(url: String): String? {
        val encodedUrl = url.replace(" ", "%20")
        logger.info("Fetching $encodedUrl page")
        return try {
            val response = httpClient.get(encodedUrl)
            if (!response.status.isSuccess()) {
                logger.error("Failed to fetch {}: HTTP {}", url, response.status)
                return null
            }
            response.bodyAsText()
        } catch (e: Exception) {
            logger.error("Failed to fetch {}: {}", url, e.message)
            null
        }
    }

    suspend fun fetchAndExtract(url: String, transformUrl: (String) -> String = { it }): String? {
        val html = fetch(url) ?: return null
        return contentExtractor.extract(html, baseUrl = url, transformUrl)
    }
}
