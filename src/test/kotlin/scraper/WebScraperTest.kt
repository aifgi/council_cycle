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
import kotlin.test.assertNull

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
        val scraper = WebScraper(HttpClient(mockEngine))

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
        val scraper = WebScraper(HttpClient(mockEngine))

        val result = scraper.fetch("https://example.com")

        assertNull(result)
    }

    @Test
    fun `fetch returns null on exception`() = runBlocking {
        val mockEngine = MockEngine { _ ->
            throw RuntimeException("Connection refused")
        }
        val scraper = WebScraper(HttpClient(mockEngine))

        val result = scraper.fetch("https://example.com")

        assertNull(result)
    }
}
