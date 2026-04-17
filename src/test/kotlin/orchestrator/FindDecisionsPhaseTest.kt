package orchestrator

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import llm.MockLlmClient
import orchestrator.phase.FindDecisionsInput
import orchestrator.phase.FindDecisionsPhase
import scraper.HtmlExtractor
import scraper.PdfExtractor
import scraper.WebScraper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FindDecisionsPhaseTest {

    private fun webScraper(responses: Map<String, String>): WebScraper {
        val engine = MockEngine { request ->
            val body = responses[request.url.toString()]
            if (body != null) {
                respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/html"))
            } else {
                respond("Not Found", HttpStatusCode.NotFound)
            }
        }
        return WebScraper(HttpClient(engine), HtmlExtractor(), PdfExtractor())
    }

    private val defaultInput = FindDecisionsInput(
        decisionsUrl = "https://council.example.com/decisions",
        decisionMakers = listOf("Cabinet Member for Streets"),
        dateFrom = "2025-01-01",
        dateTo = "2025-12-31",
    )

    @Test
    fun `returns empty list when no decisions match`() = runBlocking {
        val scraper = webScraper(mapOf("https://council.example.com/decisions" to "<html><body>No decisions</body></html>"))
        val llm = MockLlmClient { _, _ ->
            """{"type":"decisions_page_scanned","decisions":[],"nextUrl":null}"""
        }
        val phase = FindDecisionsPhase(scraper, llm)

        val result = phase.execute(defaultInput)

        assertEquals(emptyList(), result)
    }

    @Test
    fun `returns decisions from single page`() = runBlocking {
        val scraper = webScraper(mapOf("https://council.example.com/decisions" to "<html><body>Decisions</body></html>"))
        val llm = MockLlmClient { _, _ ->
            """{"type":"decisions_page_scanned","decisions":[{"title":"Cycle Lane Scheme","decisionDate":"2025-03-15","detailUrl":"https://council.example.com/decisions/1","decisionMaker":"Cabinet Member for Streets"},{"title":"School Streets Programme","decisionDate":"2025-04-01","detailUrl":"https://council.example.com/decisions/2","decisionMaker":"Cabinet Member for Streets"}],"nextUrl":null}"""
        }
        val phase = FindDecisionsPhase(scraper, llm)

        val result = phase.execute(defaultInput)

        assertEquals(2, result?.size)
        assertEquals("Cycle Lane Scheme", result?.get(0)?.title)
        assertEquals("School Streets Programme", result?.get(1)?.title)
    }

    @Test
    fun `accumulates decisions across multiple pages`() = runBlocking {
        val scraper = webScraper(
            mapOf(
                "https://council.example.com/decisions" to "<html><body>Page 1</body></html>",
                "https://council.example.com/decisions?page=2" to "<html><body>Page 2</body></html>",
            ),
        )
        var callCount = 0
        val llm = MockLlmClient { _, _ ->
            callCount++
            if (callCount == 1) {
                """{"type":"decisions_page_scanned","decisions":[{"title":"Decision A","decisionDate":"2025-03-01","detailUrl":"https://council.example.com/decisions/A"},{"title":"Decision B","decisionDate":"2025-03-02","detailUrl":"https://council.example.com/decisions/B"}],"nextUrl":"https://council.example.com/decisions?page=2"}"""
            } else {
                """{"type":"decisions_page_scanned","decisions":[{"title":"Decision C","decisionDate":"2025-02-01","detailUrl":"https://council.example.com/decisions/C"}],"nextUrl":null}"""
            }
        }
        val phase = FindDecisionsPhase(scraper, llm)

        val result = phase.execute(defaultInput)

        assertEquals(3, result?.size)
        assertEquals("Decision A", result?.get(0)?.title)
        assertEquals("Decision B", result?.get(1)?.title)
        assertEquals("Decision C", result?.get(2)?.title)
    }

    @Test
    fun `stops at max iterations and returns partial results`() = runBlocking {
        val scraper = webScraper(
            mapOf(
                "https://council.example.com/decisions" to "<html><body>Page 1</body></html>",
                "https://council.example.com/decisions?page=2" to "<html><body>Page 2</body></html>",
            ),
        )
        var callCount = 0
        val llm = MockLlmClient { _, _ ->
            callCount++
            val page = callCount
            """{"type":"decisions_page_scanned","decisions":[{"title":"Decision $page","decisionDate":"2025-03-0$page","detailUrl":"https://council.example.com/decisions/$page"}],"nextUrl":"https://council.example.com/decisions?page=${page + 1}"}"""
        }
        val phase = FindDecisionsPhase(scraper, llm, maxIterations = 2)

        val result = phase.execute(defaultInput)

        assertEquals(2, result?.size)
        assertEquals("Decision 1", result?.get(0)?.title)
        assertEquals("Decision 2", result?.get(1)?.title)
    }

    @Test
    fun `returns null on unexpected response type`() = runBlocking {
        val scraper = webScraper(mapOf("https://council.example.com/decisions" to "<html><body>Decisions</body></html>"))
        val llm = MockLlmClient { _, _ ->
            """{"type":"fetch","urls":["https://council.example.com/other"],"reason":"Following link"}"""
        }
        val phase = FindDecisionsPhase(scraper, llm)

        val result = phase.execute(defaultInput)

        assertNull(result)
    }

    @Test
    fun `returns null on LLM parse failure`() = runBlocking {
        val scraper = webScraper(mapOf("https://council.example.com/decisions" to "<html><body>Decisions</body></html>"))
        val llm = MockLlmClient { _, _ ->
            "this is not valid json {"
        }
        val phase = FindDecisionsPhase(scraper, llm)

        val result = phase.execute(defaultInput)

        assertNull(result)
    }
}
