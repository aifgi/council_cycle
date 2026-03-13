package scraper

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
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
        val scraper = WebScraper(HttpClient(mockEngine), HtmlExtractor(), PdfExtractor())

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
        val scraper = WebScraper(HttpClient(mockEngine), HtmlExtractor(), PdfExtractor())

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
        val scraper = WebScraper(HttpClient(mockEngine), HtmlExtractor(), PdfExtractor())

        val result = scraper.fetch("https://example.com/docs/My Report Jan 2026.pdf?T=10")

        assertEquals("<html>Report</html>", result)
    }

    @Test
    fun `fetch returns null on exception`() = runBlocking {
        val mockEngine = MockEngine { _ ->
            throw RuntimeException("Connection refused")
        }
        val scraper = WebScraper(HttpClient(mockEngine), HtmlExtractor(), PdfExtractor())

        val result = scraper.fetch("https://example.com")

        assertNull(result)
    }

    @Test
    fun `fetchAndExtract returns text from HTML response`() = runBlocking {
        val mockEngine = MockEngine { _ ->
            respond(
                content = "<html><body><h1>Meeting Agenda</h1></body></html>",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/html"),
            )
        }
        val scraper = WebScraper(HttpClient(mockEngine), HtmlExtractor(), PdfExtractor())

        val result = scraper.fetchAndExtract("https://example.com/agenda")

        assertNotNull(result)
        assertTrue(result.text.contains("Meeting Agenda"))
    }
}
