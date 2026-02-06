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
        val orchestrator = Orchestrator(scraper, llm, maxIterations = 3)

        val result = orchestrator.findCommitteePage("https://council.example.com", "Test Council", "Planning")

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
        val orchestrator = Orchestrator(scraper, llm, maxIterations = 5)

        val result = orchestrator.findCommitteePage("https://council.example.com", "Test Council", "Planning")

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
        val orchestrator = Orchestrator(scraper, llm, maxIterations = 3)

        val result = orchestrator.findMeetings(
            "https://council.example.com/planning", "Test Council", "Planning", "2026-01-01", "2026-06-30",
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
        val orchestrator = Orchestrator(scraper, llm, maxIterations = 5)

        val result = orchestrator.findMeetings(
            "https://council.example.com/planning", "Test Council", "Planning", "2026-01-01", "2026-06-30",
        )

        assertEquals(1, result?.size)
        assertNull(result?.get(0)?.agendaUrl)
    }

    // --- Phase 3: Analyze agenda ---

    @Test
    fun `phase 3 returns schemes when found immediately`() = runBlocking {
        val scraper = webScraper(
            mapOf("https://council.example.com/agenda/1" to "<html><body><p>Agenda items</p></body></html>"),
        )
        val llm = MockLlmClient { _ ->
            """{"type":"agenda_analyzed","schemes":[{"title":"High Street Cycle Lane","topic":"cycle lanes","summary":"New protected lane","meetingDate":"2026-03-15","committeeName":"Planning"}]}"""
        }
        val orchestrator = Orchestrator(scraper, llm, maxIterations = 3)

        val meeting = Meeting(date = "2026-03-15", title = "Planning Meeting", agendaUrl = "https://council.example.com/agenda/1")
        val result = orchestrator.analyzeAgenda(
            "https://council.example.com/agenda/1", "Test Council", "Planning", meeting,
        )

        assertEquals(1, result?.size)
        assertEquals("High Street Cycle Lane", result?.get(0)?.title)
        assertEquals("cycle lanes", result?.get(0)?.topic)
    }

    @Test
    fun `phase 3 returns empty list when no relevant schemes`() = runBlocking {
        val scraper = webScraper(
            mapOf("https://council.example.com/agenda/1" to "<html><body><p>Budget discussion</p></body></html>"),
        )
        val llm = MockLlmClient { _ ->
            """{"type":"agenda_analyzed","schemes":[]}"""
        }
        val orchestrator = Orchestrator(scraper, llm, maxIterations = 3)

        val meeting = Meeting(date = "2026-03-15", title = "Planning Meeting", agendaUrl = "https://council.example.com/agenda/1")
        val result = orchestrator.analyzeAgenda(
            "https://council.example.com/agenda/1", "Test Council", "Planning", meeting,
        )

        assertEquals(0, result?.size)
    }

    // --- Cross-cutting concerns ---

    @Test
    fun `stops at max iterations`() = runBlocking {
        val scraper = webScraper(mapOf("https://example.com" to "<html><body><p>Page</p></body></html>"))
        val llm = MockLlmClient { _ ->
            """{"type":"fetch","urls":["https://example.com"],"reason":"Need more"}"""
        }
        val orchestrator = Orchestrator(scraper, llm, maxIterations = 2)

        val result = orchestrator.findCommitteePage("https://example.com", "Test", "Planning")

        assertNull(result)
    }

    @Test
    fun `returns null when all fetches fail`() = runBlocking {
        val scraper = webScraper(emptyMap())
        val llm = MockLlmClient { _ -> """{"type":"committee_page_found","url":"https://x.com"}""" }
        val orchestrator = Orchestrator(scraper, llm, maxIterations = 3)

        val result = orchestrator.findCommitteePage("https://missing.example.com", "Test", "Planning")

        assertNull(result)
    }

    @Test
    fun `returns null on malformed LLM response`() = runBlocking {
        val scraper = webScraper(mapOf("https://example.com" to "<html><body><p>Page</p></body></html>"))
        val llm = MockLlmClient { _ -> "this is not json" }
        val orchestrator = Orchestrator(scraper, llm, maxIterations = 3)

        val result = orchestrator.findCommitteePage("https://example.com", "Test", "Planning")

        assertNull(result)
    }

    // --- End-to-end processCouncil ---

    @Test
    fun `processCouncil runs all three phases`() = runBlocking {
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
                3 -> """{"type":"agenda_analyzed","schemes":[{"title":"Cycle Lane","topic":"cycle lanes","summary":"New lane","meetingDate":"2026-03-15","committeeName":"Planning"}]}"""
                else -> error("Unexpected call $callCount")
            }
        }
        val orchestrator = Orchestrator(scraper, llm, maxIterations = 5)

        val council = CouncilConfig(
            name = "Test Council",
            siteUrl = "https://council.example.com",
            committees = listOf("Planning"),
            dateFrom = "2026-01-01",
            dateTo = "2026-06-30",
        )
        orchestrator.processCouncil(council)

        assertEquals(3, callCount)
    }
}
