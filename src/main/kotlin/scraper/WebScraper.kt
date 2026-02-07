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
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger(WebScraper::class.java)

class WebScraper(
    private val httpClient: HttpClient,
    private val contentExtractor: ContentExtractor,
) {
    suspend fun fetch(url: String): String? =
        executeRequest(url)?.bodyAsText()

    suspend fun fetchAndExtract(url: String): ConversionResult? {
        val response = executeRequest(url) ?: return null
        val contentType = response.contentType()
        if (contentType?.match(ContentType.Application.Pdf) == true) {
            return extractPdf(response, url)
        }
        val html = response.bodyAsText()
        return contentExtractor.extract(html, baseUrl = url)
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

    private suspend fun extractPdf(response: HttpResponse, url: String): ConversionResult? =
        try {
            val bytes = response.bodyAsChannel().toInputStream().readBytes()
            val text = Loader.loadPDF(bytes).use { doc ->
                PDFTextStripper().getText(doc)
            }.trim()
            if (text.isEmpty()) {
                logger.warn("PDF at {} contained no extractable text", url)
                null
            } else {
                ConversionResult(text, UrlRegistry())
            }
        } catch (e: Exception) {
            logger.warn("Failed to extract text from PDF at {}: {}", url, e.message)
            null
        }
}
