package orchestrator

import config.CouncilConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import llm.MockLlmClient
import processor.LoggingResultProcessor
import processor.ResultProcessor
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

    // --- Phase 1: Find committee page ---

    @Test
    fun `phase 1 returns URL when found immediately`() = runBlocking {
        val scraper = webScraper(mapOf("https://council.example.com" to "<html><body><p>Committees page</p></body></html>"))
        val llm = MockLlmClient { _ ->
            """{"type":"committee_page_found","url":"https://council.example.com/planning"}"""
        }
        val orchestrator = Orchestrator(scraper, llm, LoggingResultProcessor(), maxIterations = 3)

        val result = orchestrator.findCommitteePage("https://council.example.com", "Planning")

        assertEquals("https://council.example.com/planning", result)
    }

    @Test
    fun `phase 1 follows fetch then finds committee page`() = runBlocking {
        val scraper = webScraper(
            mapOf(
                "https://council.example.com" to "<html><body><p>Main page</p></body></html>",
                "https://council.example.com/committees" to "<html><body><p>Committee list</p></body></html>",
            ),
        )
        var callCount = 0
        val llm = MockLlmClient { _ ->
            callCount++
            if (callCount == 1) {
                """{"type":"fetch","urls":["https://council.example.com/committees"],"reason":"Following committees link"}"""
            } else {
                """{"type":"committee_page_found","url":"https://council.example.com/committees/planning"}"""
            }
        }
        val orchestrator = Orchestrator(scraper, llm, LoggingResultProcessor(), maxIterations = 5)

        val result = orchestrator.findCommitteePage("https://council.example.com", "Planning")

        assertEquals("https://council.example.com/committees/planning", result)
    }

    // --- Phase 2: Find meetings ---

    @Test
    fun `phase 2 returns meetings when found immediately`() = runBlocking {
        val scraper = webScraper(
            mapOf("https://council.example.com/planning" to "<html><body><p>Meetings</p></body></html>"),
        )
        val llm = MockLlmClient { _ ->
            """{"type":"meetings_found","meetings":[{"date":"2026-03-15","title":"Planning Meeting","agendaUrl":"https://council.example.com/agenda/1"}]}"""
        }
        val orchestrator = Orchestrator(scraper, llm, LoggingResultProcessor(), maxIterations = 3)

        val result = orchestrator.findMeetings(
            "https://council.example.com/planning", "Planning", "2026-01-01", "2026-06-30",
        )

        assertEquals(1, result?.size)
        assertEquals("2026-03-15", result?.get(0)?.date)
        assertEquals("https://council.example.com/agenda/1", result?.get(0)?.agendaUrl)
    }

    @Test
    fun `phase 2 follows fetch then finds meetings`() = runBlocking {
        val scraper = webScraper(
            mapOf(
                "https://council.example.com/planning" to "<html><body><p>Committee page</p></body></html>",
                "https://council.example.com/planning/meetings" to "<html><body><p>Meeting list</p></body></html>",
            ),
        )
        var callCount = 0
        val llm = MockLlmClient { _ ->
            callCount++
            if (callCount == 1) {
                """{"type":"fetch","urls":["https://council.example.com/planning/meetings"],"reason":"Following meetings link"}"""
            } else {
                """{"type":"meetings_found","meetings":[{"date":"2026-04-01","title":"April Meeting"}]}"""
            }
        }
        val orchestrator = Orchestrator(scraper, llm, LoggingResultProcessor(), maxIterations = 5)

        val result = orchestrator.findMeetings(
            "https://council.example.com/planning", "Planning", "2026-01-01", "2026-06-30",
        )

        assertEquals(1, result?.size)
        assertNull(result?.get(0)?.agendaUrl)
    }

    // --- Phase 3: Triage agenda ---

    @Test
    fun `phase 3 returns relevant triage with extract`() = runBlocking {
        val scraper = webScraper(
            mapOf("https://council.example.com/agenda/1" to "<html><body><p>Agenda items</p></body></html>"),
        )
        val llm = MockLlmClient { _ ->
            """{"type":"agenda_triaged","relevant":true,"extract":"Item 1: High Street Cycle Lane - new protected lane"}"""
        }
        val orchestrator = Orchestrator(scraper, llm, LoggingResultProcessor(), maxIterations = 3)

        val result = orchestrator.triageAgenda("https://council.example.com/agenda/1")

        assertEquals(true, result?.relevant)
        assertEquals("Item 1: High Street Cycle Lane - new protected lane", result?.extract)
    }

    @Test
    fun `phase 3 returns not relevant when no matching items`() = runBlocking {
        val scraper = webScraper(
            mapOf("https://council.example.com/agenda/1" to "<html><body><p>Budget discussion</p></body></html>"),
        )
        val llm = MockLlmClient { _ ->
            """{"type":"agenda_triaged","relevant":false}"""
        }
        val orchestrator = Orchestrator(scraper, llm, LoggingResultProcessor(), maxIterations = 3)

        val result = orchestrator.triageAgenda("https://council.example.com/agenda/1")

        assertEquals(false, result?.relevant)
    }

    // --- Phase 4: Analyze extract ---

    @Test
    fun `phase 4 returns schemes from extract`() = runBlocking {
        val scraper = webScraper(emptyMap())
        val llm = MockLlmClient { _ ->
            """{"type":"agenda_analyzed","schemes":[{"title":"High Street Cycle Lane","topic":"cycle lanes","summary":"New protected lane"}]}"""
        }
        val orchestrator = Orchestrator(scraper, llm, LoggingResultProcessor(), maxIterations = 3)

        val meeting = Meeting(date = "2026-03-15", title = "Planning Meeting", agendaUrl = "https://council.example.com/agenda/1")
        val result = orchestrator.analyzeExtract(
            "Item 1: High Street Cycle Lane - new protected lane", "Planning", meeting,
        )

        assertEquals(1, result?.size)
        assertEquals("High Street Cycle Lane", result?.get(0)?.title)
        assertEquals("cycle lanes", result?.get(0)?.topic)
        assertEquals("2026-03-15", result?.get(0)?.meetingDate)
        assertEquals("Planning", result?.get(0)?.committeeName)
    }

    // --- Cross-cutting concerns ---

    @Test
    fun `stops at max iterations`() = runBlocking {
        val scraper = webScraper(mapOf("https://example.com" to "<html><body><p>Page</p></body></html>"))
        val llm = MockLlmClient { _ ->
            """{"type":"fetch","urls":["https://example.com"],"reason":"Need more"}"""
        }
        val orchestrator = Orchestrator(scraper, llm, LoggingResultProcessor(), maxIterations = 2)

        val result = orchestrator.findCommitteePage("https://example.com", "Planning")

        assertNull(result)
    }

    @Test
    fun `returns null when all fetches fail`() = runBlocking {
        val scraper = webScraper(emptyMap())
        val llm = MockLlmClient { _ -> """{"type":"committee_page_found","url":"https://x.com"}""" }
        val orchestrator = Orchestrator(scraper, llm, LoggingResultProcessor(), maxIterations = 3)

        val result = orchestrator.findCommitteePage("https://missing.example.com", "Planning")

        assertNull(result)
    }

    @Test
    fun `returns null on malformed LLM response`() = runBlocking {
        val scraper = webScraper(mapOf("https://example.com" to "<html><body><p>Page</p></body></html>"))
        val llm = MockLlmClient { _ -> "this is not json" }
        val orchestrator = Orchestrator(scraper, llm, LoggingResultProcessor(), maxIterations = 3)

        val result = orchestrator.findCommitteePage("https://example.com", "Planning")

        assertNull(result)
    }

    // --- URL token round-trip ---

    @Test
    fun `URL tokens in prompt resolve back to full URLs`() = runBlocking {
        val scraper = webScraper(
            mapOf(
                "https://council.example.com" to
                    """<html><body><a href="https://council.example.com/committees">Committees</a></body></html>""",
            ),
        )
        val llm = MockLlmClient { prompt ->
            // The prompt should contain @N tokens in markdown links instead of full URLs
            val linkToken = Regex("""\[Committees]\((@\d+)\)""").find(prompt)!!.groupValues[1]
            """{"type":"committee_page_found","url":"$linkToken"}"""
        }
        val orchestrator = Orchestrator(scraper, llm, LoggingResultProcessor(), maxIterations = 3)

        val result = orchestrator.findCommitteePage("https://council.example.com", "Planning")

        // The token should have been resolved back to the full URL
        assertEquals("https://council.example.com/committees", result)
    }

    // --- End-to-end processCouncil ---

    @Test
    fun `processCouncil runs all four phases`() = runBlocking {
        val scraper = webScraper(
            mapOf(
                "https://council.example.com" to "<html><body><p>Main</p></body></html>",
                "https://council.example.com/planning" to "<html><body><p>Committee</p></body></html>",
                "https://council.example.com/agenda/1" to "<html><body><p>Agenda</p></body></html>",
            ),
        )
        var callCount = 0
        val llm = MockLlmClient { _ ->
            callCount++
            when (callCount) {
                1 -> """{"type":"committee_page_found","url":"https://council.example.com/planning"}"""
                2 -> """{"type":"meetings_found","meetings":[{"date":"2026-03-15","title":"Planning Meeting","agendaUrl":"https://council.example.com/agenda/1"}]}"""
                3 -> """{"type":"agenda_triaged","relevant":true,"extract":"Item 1: Cycle Lane proposal"}"""
                4 -> """{"type":"agenda_analyzed","schemes":[{"title":"Cycle Lane","topic":"cycle lanes","summary":"New lane"}]}"""
                else -> error("Unexpected call $callCount")
            }
        }
        val processed = mutableListOf<List<Scheme>>()
        val processor = ResultProcessor { _, _, schemes -> processed.add(schemes) }
        val orchestrator = Orchestrator(scraper, llm, processor, maxIterations = 5)

        val council = CouncilConfig(
            name = "Test Council",
            siteUrl = "https://council.example.com",
            committees = listOf("Planning"),
            dateFrom = "2026-01-01",
            dateTo = "2026-06-30",
        )
        orchestrator.processCouncil(council)

        assertEquals(4, callCount)
        assertEquals(1, processed.size)
        assertEquals(1, processed[0].size)
        assertEquals("Cycle Lane", processed[0][0].title)
    }
}
