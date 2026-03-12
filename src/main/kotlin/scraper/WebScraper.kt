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
        val chunkKey = try {
            URI(url).path.trimStart('/')
        } catch (e: Exception) {
            logger.warn("Invalid pdf-page URL: {}", url)
            return null
        }
        val chunk = pdfCache.getChunk(chunkKey) ?: run {
            logger.warn("PDF cache miss for chunk key {} (from URL {})", chunkKey, url)
            return null
        }
        return buildConversionResult(chunk)
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

        val docId = UUID.randomUUID().toString()
        var firstChunkKey: String? = null
        val chunkData = mutableMapOf<String, PdfChunk>()
        try {
            Loader.loadPDF(bytes).use { doc ->
                val totalPages = doc.numberOfPages
                val stripper = PDFTextStripper()
                var startPage = 1
                while (startPage <= totalPages) {
                    val endPage = minOf(startPage - 1 + PDF_PAGE_LIMIT, totalPages)
                    stripper.startPage = startPage
                    stripper.endPage = endPage
                    val text = stripper.getText(doc).trim()
                    if (text.isNotEmpty()) {
                        val key = "$docId/$startPage"
                        val nextChunkKey = if (endPage < totalPages) "$docId/${endPage + 1}" else null
                        if (firstChunkKey == null) firstChunkKey = key
                        chunkData[key] = PdfChunk(text, startPage, endPage, totalPages, nextChunkKey)
                    }
                    startPage = endPage + 1
                }
            }
        } catch (e: Exception) {
            logger.warn("Failed to extract text from PDF at {}: {}", url, e.message)
            return null
        }

        if (firstChunkKey == null) {
            logger.warn("PDF at {} contained no extractable text", url)
            return null
        }

        pdfCache.storeChunks(url, chunkData)
        return buildConversionResult(chunkData[firstChunkKey]!!)
    }

    private fun buildConversionResult(chunk: PdfChunk): ConversionResult {
        val urlRegistry = UrlRegistry()
        val sb = StringBuilder(chunk.text)
        if (chunk.nextChunkKey != null) {
            val nextPageEnd = minOf(chunk.endPage + PDF_PAGE_LIMIT, chunk.totalPages)
            val nextUrl = "https://$PDF_CACHE_DOMAIN/${chunk.nextChunkKey}"
            val token = urlRegistry.register(nextUrl)
            sb.append("\n\n[Document truncated: showing pages ${chunk.startPage}-${chunk.endPage} of ${chunk.totalPages}. Load next section (pages ${chunk.endPage + 1}-$nextPageEnd): $token]")
        }
        return ConversionResult(sb.toString(), urlRegistry)
    }
}