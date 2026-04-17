package orchestrator

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import llm.MockLlmClient
import orchestrator.phase.EnrichDecisionInput
import orchestrator.phase.EnrichDecisionPhase
import scraper.HtmlExtractor
import scraper.PdfExtractor
import scraper.WebScraper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EnrichDecisionPhaseTest {

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

    private val defaultDecision = DecisionEntry(
        title = "Cycle Lane Scheme",
        decisionDate = "2025-03-15",
        detailUrl = "https://council.example.com/decisions/1",
        decisionMaker = null,
    )

    private val defaultInput = EnrichDecisionInput(decision = defaultDecision)

    @Test
    fun `returns DecisionEnriched with item and decisionMaker on immediate decision_enriched response`() = runBlocking {
        val scraper = webScraper(
            mapOf("https://council.example.com/decisions/1" to "<html><body>Decision details</body></html>"),
        )
        val llm = MockLlmClient { _, _ ->
            """{"type":"decision_enriched","item":{"title":"Cycle Lane Scheme","extract":"Full extract text"},"decisionMaker":"Cabinet Member for Streets"}"""
        }
        val phase = EnrichDecisionPhase(scraper, llm)

        val result = phase.execute(defaultInput)

        assertNotNull(result)
        assertEquals("Cycle Lane Scheme", result.item.title)
        assertEquals("Full extract text", result.item.extract)
        assertEquals("Cabinet Member for Streets", result.decisionMaker)
    }

    @Test
    fun `fetches additional document and returns enriched on second call`() = runBlocking {
        val scraper = webScraper(
            mapOf(
                "https://council.example.com/decisions/1" to "<html><body>Decision details <a href=\"https://council.example.com/decisions/1/report\">Report</a></body></html>",
                "https://council.example.com/decisions/1/report" to "<html><body>Full report content</body></html>",
            ),
        )
        var callCount = 0
        val llm = MockLlmClient { _, _ ->
            callCount++
            if (callCount == 1) {
                """{"type":"decision_fetch","urls":["https://council.example.com/decisions/1/report"],"reason":"Need full report"}"""
            } else {
                """{"type":"decision_enriched","item":{"title":"Cycle Lane Scheme","extract":"Enriched extract"},"decisionMaker":"Cabinet Member for Streets"}"""
            }
        }
        val phase = EnrichDecisionPhase(scraper, llm)

        val result = phase.execute(defaultInput)

        assertNotNull(result)
        assertEquals("Cycle Lane Scheme", result.item.title)
        assertEquals(2, callCount)
    }

    @Test
    fun `carries partial extract forward into next fetch prompt`() = runBlocking {
        val scraper = webScraper(
            mapOf(
                "https://council.example.com/decisions/1" to "<html><body>Decision details <a href=\"https://council.example.com/decisions/1/appendix\">Appendix</a></body></html>",
                "https://council.example.com/decisions/1/appendix" to "<html><body>Appendix content</body></html>",
            ),
        )
        var secondCallUserPrompt: String? = null
        var callCount = 0
        val llm = MockLlmClient { _, userPrompt ->
            callCount++
            if (callCount == 1) {
                """{"type":"decision_fetch","urls":["https://council.example.com/decisions/1/appendix"],"extract":{"title":"Cycle Lane Scheme","extract":"Partial extract so far"},"reason":"Need appendix"}"""
            } else {
                secondCallUserPrompt = userPrompt
                """{"type":"decision_enriched","item":{"title":"Cycle Lane Scheme","extract":"Final extract"},"decisionMaker":"Cabinet Member"}"""
            }
        }
        val phase = EnrichDecisionPhase(scraper, llm)

        val result = phase.execute(defaultInput)

        assertNotNull(result)
        assertNotNull(secondCallUserPrompt)
        assertTrue(secondCallUserPrompt!!.contains("Partial extract so far"), "Second prompt should contain partial extract")
    }

    @Test
    fun `returns null when detail page fetch fails on first iteration`() = runBlocking {
        val scraper = webScraper(emptyMap()) // 404 for all URLs
        val llm = MockLlmClient { _, _ -> """{"type":"decision_enriched","item":{"title":"x","extract":"y"}}""" }
        val phase = EnrichDecisionPhase(scraper, llm)

        val result = phase.execute(defaultInput)

        assertNull(result)
    }

    @Test
    fun `returns null when max iterations reached without decision_enriched`() = runBlocking {
        val scraper = webScraper(
            mapOf(
                "https://council.example.com/decisions/1" to "<html><body>Page 1 <a href=\"https://council.example.com/decisions/1/doc1\">Doc1</a></body></html>",
                "https://council.example.com/decisions/1/doc1" to "<html><body>Doc1 <a href=\"https://council.example.com/decisions/1/doc2\">Doc2</a></body></html>",
            ),
        )
        var callCount = 0
        val llm = MockLlmClient { _, _ ->
            callCount++
            val nextDoc = "https://council.example.com/decisions/1/doc$callCount"
            """{"type":"decision_fetch","urls":["$nextDoc"],"reason":"Need more"}"""
        }
        val phase = EnrichDecisionPhase(scraper, llm, maxIterations = 2)

        val result = phase.execute(defaultInput)

        assertNull(result)
    }

    @Test
    fun `returns null on unexpected response type`() = runBlocking {
        val scraper = webScraper(
            mapOf("https://council.example.com/decisions/1" to "<html><body>Decision details</body></html>"),
        )
        val llm = MockLlmClient { _, _ ->
            """{"type":"agenda_analyzed","schemes":[]}"""
        }
        val phase = EnrichDecisionPhase(scraper, llm)

        val result = phase.execute(defaultInput)

        assertNull(result)
    }
}
