package scraper

import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.slf4j.LoggerFactory
import java.net.URI
import java.util.UUID

private val logger = LoggerFactory.getLogger(PdfExtractor::class.java)

private const val PDF_PAGE_LIMIT = 5
private const val PDF_CACHE_DOMAIN = "pdf-page.internal"

class PdfExtractor(private val pdfCache: PdfCache = PdfCache()) {

    fun isPdfPageUrl(url: String): Boolean =
        try {
            URI(url).host == PDF_CACHE_DOMAIN
        } catch (_: Exception) {
            false
        }

    fun getPage(url: String): ConversionResult? {
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

    fun extract(bytes: ByteArray, url: String): ConversionResult? {
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

    fun release(url: String) {
        pdfCache.releaseByUrl(url)
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
