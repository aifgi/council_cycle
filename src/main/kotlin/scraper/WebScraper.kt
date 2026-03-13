package scraper

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.jvm.javaio.toInputStream
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger(WebScraper::class.java)

class WebScraper(
    private val httpClient: HttpClient,
    private val htmlExtractor: HtmlExtractor,
    private val pdfExtractor: PdfExtractor,
) {
    suspend fun fetch(url: String): String? =
        executeRequest(url)?.bodyAsText()

    suspend fun fetchAndExtract(url: String): ConversionResult? {
        if (pdfExtractor.isPdfPageUrl(url)) {
            return pdfExtractor.getPage(url)
        }
        val response = executeRequest(url) ?: return null
        val contentType = response.contentType()
        if (contentType?.match(ContentType.Application.Pdf) == true) {
            val bytes = try {
                response.bodyAsChannel().toInputStream().readBytes()
            } catch (e: Exception) {
                logger.warn("Failed to read PDF bytes from {}: {}", url, e.message)
                return null
            }
            return pdfExtractor.extract(bytes, url)
        }
        val html = response.bodyAsText()
        return htmlExtractor.extract(html, baseUrl = url)
    }

    fun releaseDocument(url: String) {
        pdfExtractor.release(url)
    }

    private suspend fun executeRequest(url: String): HttpResponse? {
        val encodedUrl = url.replace(" ", "%20")
        logger.info("Fetching $encodedUrl page")
        return try {
            val response = httpClient.get(encodedUrl)
            if (!response.status.isSuccess()) {
                logger.error("Failed to fetch {}: HTTP {}", url, response.status)
                return null
            }
            response
        } catch (e: Exception) {
            logger.error("Failed to fetch {}: {}", url, e.message)
            null
        }
    }
}
