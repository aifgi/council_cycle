package scraper

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebScraperTest {

    @Test
    fun `fetch returns body on successful response`() = runBlocking {
        val mockEngine = MockEngine { _ ->
            respond(
                content = "<html>Hello</html>",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/html"),
            )
        }
        val scraper = WebScraper(HttpClient(mockEngine), ContentExtractor())

        val result = scraper.fetch("https://example.com")

        assertEquals("<html>Hello</html>", result)
    }

    @Test
    fun `fetch returns null on non-success status`() = runBlocking {
        val mockEngine = MockEngine { _ ->
            respond(
                content = "Not Found",
                status = HttpStatusCode.NotFound,
                headers = headersOf(HttpHeaders.ContentType, "text/html"),
            )
        }
        val scraper = WebScraper(HttpClient(mockEngine), ContentExtractor())

        val result = scraper.fetch("https://example.com")

        assertNull(result)
    }

    @Test
    fun `fetch encodes spaces in URL`() = runBlocking {
        val mockEngine = MockEngine { request ->
            assertEquals(
                "https://example.com/docs/My%20Report%20Jan%202026.pdf?T=10",
                request.url.toString(),
            )
            respond(
                content = "<html>Report</html>",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/html"),
            )
        }
        val scraper = WebScraper(HttpClient(mockEngine), ContentExtractor())

        val result = scraper.fetch("https://example.com/docs/My Report Jan 2026.pdf?T=10")

        assertEquals("<html>Report</html>", result)
    }

    @Test
    fun `fetch returns null on exception`() = runBlocking {
        val mockEngine = MockEngine { _ ->
            throw RuntimeException("Connection refused")
        }
        val scraper = WebScraper(HttpClient(mockEngine), ContentExtractor())

        val result = scraper.fetch("https://example.com")

        assertNull(result)
    }

    @Test
    fun `fetchAndExtract returns text from PDF response`() = runBlocking {
        val pdfBytes = createPdfWithText("Transport scheme agenda item 7")
        val mockEngine = MockEngine { _ ->
            respond(
                content = pdfBytes,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/pdf"),
            )
        }
        val scraper = WebScraper(HttpClient(mockEngine), ContentExtractor())

        val result = scraper.fetchAndExtract("https://example.com/agenda.pdf")

        assertNotNull(result)
        assertTrue(result.text.contains("Transport scheme agenda item 7"))
    }

    @Test
    fun `fetchAndExtract returns null when PDF text extraction fails`() = runBlocking {
        val mockEngine = MockEngine { _ ->
            respond(
                content = byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x00, 0x01, 0x02),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/pdf"),
            )
        }
        val scraper = WebScraper(HttpClient(mockEngine), ContentExtractor())

        val result = scraper.fetchAndExtract("https://example.com/broken.pdf")

        assertNull(result)
    }

    @Test
    fun `fetchAndExtract still works for HTML`() = runBlocking {
        val mockEngine = MockEngine { _ ->
            respond(
                content = "<html><body><h1>Meeting Agenda</h1></body></html>",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/html"),
            )
        }
        val scraper = WebScraper(HttpClient(mockEngine), ContentExtractor())

        val result = scraper.fetchAndExtract("https://example.com/agenda")

        assertNotNull(result)
        assertTrue(result.text.contains("Meeting Agenda"))
    }

    @Test
    fun `fetchAndExtract returns first 25 pages and next-page token for large PDF`() = runBlocking {
        val pdfBytes = createPdfWithPages(30)
        val mockEngine = MockEngine { _ ->
            respond(
                content = pdfBytes,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/pdf"),
            )
        }
        val scraper = WebScraper(HttpClient(mockEngine), ContentExtractor())

        val result = scraper.fetchAndExtract("https://example.com/large.pdf")

        assertNotNull(result)
        assertTrue(result.text.contains("Page 1"))
        assertTrue(result.text.contains("Page 25"))
        assertFalse(result.text.contains("Page 26"))
        val nextPageUrl = result.urlRegistry.resolve("@1")
        assertTrue(nextPageUrl.startsWith("https://pdf-page.internal/"))
        val pathParts = nextPageUrl.removePrefix("https://pdf-page.internal/").split("/")
        assertEquals(2, pathParts.size)
        assertEquals(26, pathParts[1].toInt())
    }

    @Test
    fun `fetchAndExtract returns remaining pages from cache without HTTP request`() = runBlocking {
        val pdfBytes = createPdfWithPages(30)
        var requestCount = 0
        val mockEngine = MockEngine { _ ->
            requestCount++
            respond(
                content = pdfBytes,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/pdf"),
            )
        }
        val scraper = WebScraper(HttpClient(mockEngine), ContentExtractor())

        val firstResult = scraper.fetchAndExtract("https://example.com/large.pdf")
        assertNotNull(firstResult)
        val nextPageUrl = firstResult.urlRegistry.resolve("@1")

        val secondResult = scraper.fetchAndExtract(nextPageUrl)

        assertNotNull(secondResult)
        assertTrue(secondResult.text.contains("Page 26"))
        assertTrue(secondResult.text.contains("Page 30"))
        assertFalse(secondResult.text.contains("Page 25"))
        assertEquals(1, requestCount)
    }

    @Test
    fun `fetchAndExtract returns null for next-page URL after releaseDocument`() = runBlocking {
        val pdfBytes = createPdfWithPages(30)
        val mockEngine = MockEngine { _ ->
            respond(
                content = pdfBytes,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/pdf"),
            )
        }
        val scraper = WebScraper(HttpClient(mockEngine), ContentExtractor())

        val firstResult = scraper.fetchAndExtract("https://example.com/large.pdf")
        assertNotNull(firstResult)
        val nextPageUrl = firstResult.urlRegistry.resolve("@1")

        scraper.releaseDocument("https://example.com/large.pdf")

        val secondResult = scraper.fetchAndExtract(nextPageUrl)
        assertNull(secondResult)
    }

    @Test
    fun `fetchAndExtract does not append next-page token for PDF within page limit`() = runBlocking {
        val pdfBytes = createPdfWithPages(10)
        val mockEngine = MockEngine { _ ->
            respond(
                content = pdfBytes,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/pdf"),
            )
        }
        val scraper = WebScraper(HttpClient(mockEngine), ContentExtractor())

        val result = scraper.fetchAndExtract("https://example.com/small.pdf")

        assertNotNull(result)
        assertTrue(result.text.contains("Page 1"))
        assertTrue(result.text.contains("Page 10"))
        assertFalse(result.text.contains("pdf-page.internal"))
        assertEquals("@1", result.urlRegistry.resolve("@1"))
    }

    private fun createPdfWithText(text: String): ByteArray {
        val output = ByteArrayOutputStream()
        PDDocument().use { doc ->
            val page = PDPage()
            doc.addPage(page)
            PDPageContentStream(doc, page).use { cs ->
                cs.beginText()
                cs.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f)
                cs.newLineAtOffset(50f, 700f)
                cs.showText(text)
                cs.endText()
            }
            doc.save(output)
        }
        return output.toByteArray()
    }

    private fun createPdfWithPages(pageCount: Int): ByteArray {
        val output = ByteArrayOutputStream()
        PDDocument().use { doc ->
            repeat(pageCount) { i ->
                val page = PDPage()
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    cs.beginText()
                    cs.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f)
                    cs.newLineAtOffset(50f, 700f)
                    cs.showText("Page ${i + 1}")
                    cs.endText()
                }
            }
            doc.save(output)
        }
        return output.toByteArray()
    }
}
