package orchestrator

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import llm.MockLlmClient
import scraper.ContentExtractor
import scraper.WebScraper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OrchestratorTest {

    private fun webScraper(responses: Map<String, String>): WebScraper {
        val engine = MockEngine { request ->
            val body = responses[request.url.toString()]
            if (body != null) {
                respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/html"))
            } else {
                respond("Not Found", HttpStatusCode.NotFound)
            }
        }
        return WebScraper(HttpClient(engine), ContentExtractor())
    }

    @Test
    fun `returns found when LLM finds info on first page`() = runBlocking {
        val scraper = webScraper(mapOf("https://council.example.com" to "<html><body><p>Committee info</p></body></html>"))
        val llm = MockLlmClient { _ ->
            """{"type":"found","committeeName":"Planning","summary":"Found the info"}"""
        }
        val orchestrator = Orchestrator(scraper, llm, maxIterations = 3)

        val result = orchestrator.findCommitteeInfo("Test Council", "https://council.example.com", "Planning")

        assertEquals("Planning", result?.committeeName)
        assertEquals("Found the info", result?.summary)
    }

    @Test
    fun `follows fetch instructions then returns found`() = runBlocking {
        val scraper = webScraper(
            mapOf(
                "https://council.example.com" to "<html><body><p>Main page</p></body></html>",
                "https://council.example.com/committees" to "<html><body><p>Meeting on March 15</p></body></html>",
            ),
        )
        var callCount = 0
        val llm = MockLlmClient { _ ->
            callCount++
            if (callCount == 1) {
                """{"type":"fetch","urls":["https://council.example.com/committees"],"reason":"Following link"}"""
            } else {
                """{"type":"found","committeeName":"Planning","nextMeetingDate":"2026-03-15","summary":"Meeting found"}"""
            }
        }
        val orchestrator = Orchestrator(scraper, llm, maxIterations = 5)

        val result = orchestrator.findCommitteeInfo("Test Council", "https://council.example.com", "Planning")

        assertEquals("Planning", result?.committeeName)
        assertEquals("2026-03-15", result?.nextMeetingDate)
    }

    @Test
    fun `stops at max iterations`() = runBlocking {
        val scraper = webScraper(mapOf("https://example.com" to "<html><body><p>Page</p></body></html>"))
        val llm = MockLlmClient { _ ->
            """{"type":"fetch","urls":["https://example.com"],"reason":"Need more"}"""
        }
        val orchestrator = Orchestrator(scraper, llm, maxIterations = 2)

        val result = orchestrator.findCommitteeInfo("Test", "https://example.com", "Planning")

        assertNull(result)
    }

    @Test
    fun `returns null when all fetches fail`() = runBlocking {
        val scraper = webScraper(emptyMap())
        val llm = MockLlmClient { _ -> """{"type":"found","committeeName":"X","summary":"Y"}""" }
        val orchestrator = Orchestrator(scraper, llm, maxIterations = 3)

        val result = orchestrator.findCommitteeInfo("Test", "https://missing.example.com", "Planning")

        assertNull(result)
    }

    @Test
    fun `returns null on malformed LLM response`() = runBlocking {
        val scraper = webScraper(mapOf("https://example.com" to "<html><body><p>Page</p></body></html>"))
        val llm = MockLlmClient { _ -> "this is not json" }
        val orchestrator = Orchestrator(scraper, llm, maxIterations = 3)

        val result = orchestrator.findCommitteeInfo("Test", "https://example.com", "Planning")

        assertNull(result)
    }
}
