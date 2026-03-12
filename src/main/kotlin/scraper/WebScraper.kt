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
import java.net.URI
import java.util.UUID

private val logger = LoggerFactory.getLogger(WebScraper::class.java)

private const val PDF_PAGE_LIMIT = 25
private const val PDF_CACHE_DOMAIN = "pdf-page.internal"

class WebScraper(
    private val httpClient: HttpClient,
    private val contentExtractor: ContentExtractor,
    private val pdfCache: PdfCache = PdfCache(),
) {
    suspend fun fetch(url: String): String? =
        executeRequest(url)?.bodyAsText()

    suspend fun fetchAndExtract(url: String): ConversionResult? {
        if (isPdfPageUrl(url)) {
            return fetchCachedPdfPage(url)
        }
        val response = executeRequest(url) ?: return null
        val contentType = response.contentType()
        if (contentType?.match(ContentType.Application.Pdf) == true) {
            return extractPdf(response, url)
        }
        val html = response.bodyAsText()
        return contentExtractor.extract(html, baseUrl = url)
    }

    fun releaseDocument(url: String) {
        pdfCache.releaseByUrl(url)
    }

    private fun isPdfPageUrl(url: String): Boolean =
        try {
            URI(url).host == PDF_CACHE_DOMAIN
        } catch (_: Exception) {
            false
        }

    private fun fetchCachedPdfPage(url: String): ConversionResult? {
        val parts = try {
            URI(url).path.trimStart('/').split("/")
        } catch (e: Exception) {
            logger.warn("Invalid pdf-page URL: {}", url)
            return null
        }
        if (parts.size != 2) {
            logger.warn("Invalid pdf-page URL structure: {}", url)
            return null
        }
        val (cacheKey, startPageStr) = parts
        val startPage = startPageStr.toIntOrNull() ?: run {
            logger.warn("Invalid page number in pdf-page URL: {}", url)
            return null
        }
        val bytes = pdfCache.getByKey(cacheKey) ?: run {
            logger.warn("PDF cache miss for key {} (from URL {})", cacheKey, url)
            return null
        }
        return extractPdfPages(bytes, cacheKey, startPage)
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

    private suspend fun extractPdf(response: HttpResponse, url: String): ConversionResult? {
        val bytes = try {
            response.bodyAsChannel().toInputStream().readBytes()
        } catch (e: Exception) {
            logger.warn("Failed to read PDF bytes from {}: {}", url, e.message)
            return null
        }
        val cacheKey = UUID.randomUUID().toString()
        pdfCache.store(cacheKey, url, bytes)
        return extractPdfPages(bytes, cacheKey, startPage = 1)
    }

    private fun extractPdfPages(bytes: ByteArray, cacheKey: String, startPage: Int): ConversionResult? =
        try {
            Loader.loadPDF(bytes).use { doc ->
                val totalPages = doc.numberOfPages
                if (startPage > totalPages) {
                    logger.warn("Requested start page {} exceeds total pages {}", startPage, totalPages)
                    return null
                }
                val endPage = minOf(startPage - 1 + PDF_PAGE_LIMIT, totalPages)
                val stripper = PDFTextStripper()
                stripper.startPage = startPage
                stripper.endPage = endPage
                val text = stripper.getText(doc).trim()
                if (text.isEmpty()) {
                    logger.warn("PDF pages {}-{} contained no extractable text", startPage, endPage)
                    return null
                }
                val urlRegistry = UrlRegistry()
                val sb = StringBuilder(text)
                if (endPage < totalPages) {
                    val nextPage = endPage + 1
                    val nextPageEnd = minOf(nextPage - 1 + PDF_PAGE_LIMIT, totalPages)
                    val nextUrl = "https://$PDF_CACHE_DOMAIN/$cacheKey/$nextPage"
                    val token = urlRegistry.register(nextUrl)
                    sb.append("\n\n[Document truncated: showing pages $startPage-$endPage of $totalPages. Load next section (pages $nextPage-$nextPageEnd): $token]")
                }
                ConversionResult(sb.toString(), urlRegistry)
            }
        } catch (e: Exception) {
            logger.warn("Failed to extract PDF pages {}-{}: {}", startPage, startPage + PDF_PAGE_LIMIT - 1, e.message)
            null
        }
}