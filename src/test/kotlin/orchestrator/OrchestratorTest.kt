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
import orchestrator.phase.AnalyzeExtractInput
import orchestrator.phase.AnalyzeExtractPhase
import orchestrator.phase.FindCommitteePagesInput
import orchestrator.phase.FindCommitteePagesPhase
import orchestrator.phase.FindMeetingsInput
import orchestrator.phase.FindMeetingsPhase
import orchestrator.phase.TriageAgendaInput
import orchestrator.phase.TriageAgendaPhase
import processor.ResultProcessor
import scraper.ContentExtractor
import scraper.WebScraper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    // --- Phase 1: Find committee pages ---

    @Test
    fun `phase 1 returns URLs when found immediately`() = runBlocking {
        val scraper = webScraper(mapOf("https://council.example.com" to "<html><body><p>Committees page</p></body></html>"))
        val llm = MockLlmClient { _, _ ->
            """{"type":"committee_pages_found","committees":[{"name":"Planning","url":"https://council.example.com/planning"}]}"""
        }
        val phase = FindCommitteePagesPhase(scraper, llm, maxIterations = 3)

        val result = phase.execute(FindCommitteePagesInput("https://council.example.com", listOf("Planning")))

        assertEquals(mapOf("Planning" to "https://council.example.com/planning"), result)
    }

    @Test
    fun `phase 1 follows fetch then finds committee pages`() = runBlocking {
        val scraper = webScraper(
            mapOf(
                "https://council.example.com" to "<html><body><p>Main page</p></body></html>",
                "https://council.example.com/committees" to "<html><body><p>Committee list</p></body></html>",
            ),
        )
        var callCount = 0
        val llm = MockLlmClient { _, _ ->
            callCount++
            if (callCount == 1) {
                """{"type":"fetch","urls":["https://council.example.com/committees"],"reason":"Following committees link"}"""
            } else {
                """{"type":"committee_pages_found","committees":[{"name":"Planning","url":"https://council.example.com/committees/planning"}]}"""
            }
        }
        val phase = FindCommitteePagesPhase(scraper, llm, maxIterations = 5)

        val result = phase.execute(FindCommitteePagesInput("https://council.example.com", listOf("Planning")))

        assertEquals(mapOf("Planning" to "https://council.example.com/committees/planning"), result)
    }

    // --- Phase 2: Find meetings ---

    @Test
    fun `phase 2 returns meetings when found immediately`() = runBlocking {
        val scraper = webScraper(
            mapOf("https://council.example.com/planning" to "<html><body><p>Meetings</p></body></html>"),
        )
        val llm = MockLlmClient { _, _ ->
            """{"type":"meetings_found","meetings":[{"date":"2026-03-15","title":"Planning Meeting","meetingUrl":"https://council.example.com/agenda/1"}]}"""
        }
        val phase = FindMeetingsPhase(scraper, llm, maxIterations = 3)

        val result = phase.execute(
            FindMeetingsInput("https://council.example.com/planning", "Planning", "2026-01-01", "2026-06-30"),
        )

        assertEquals(1, result?.size)
        assertEquals("2026-03-15", result?.get(0)?.date)
        assertEquals("https://council.example.com/agenda/1", result?.get(0)?.meetingUrl)
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
        val llm = MockLlmClient { _, _ ->
            callCount++
            if (callCount == 1) {
                """{"type":"fetch","urls":["https://council.example.com/planning/meetings"],"reason":"Following meetings link"}"""
            } else {
                """{"type":"meetings_found","meetings":[{"date":"2026-04-01","title":"April Meeting"}]}"""
            }
        }
        val phase = FindMeetingsPhase(scraper, llm, maxIterations = 5)

        val result = phase.execute(
            FindMeetingsInput("https://council.example.com/planning", "Planning", "2026-01-01", "2026-06-30"),
        )

        assertEquals(1, result?.size)
        assertNull(result?.get(0)?.meetingUrl)
    }

    // --- Phase 3: Triage agenda ---

    @Test
    fun `phase 3 returns relevant triage with items`() = runBlocking {
        val scraper = webScraper(
            mapOf("https://council.example.com/agenda/1" to "<html><body><p>Agenda items</p></body></html>"),
        )
        val llm = MockLlmClient { _, _ ->
            """{"type":"agenda_triaged","relevant":true,"items":[{"title":"High Street Cycle Lane","extract":"Item 1: High Street Cycle Lane - new protected lane"}]}"""
        }
        val phase = TriageAgendaPhase(scraper, llm, maxIterations = 3)

        val result = phase.execute(
            TriageAgendaInput(
                "https://council.example.com/agenda/1",
                "Transport Committee",
                "2025-01-15"
            )
        )

        assertEquals(true, result?.relevant)
        assertEquals(1, result?.items?.size)
        assertEquals("High Street Cycle Lane", result?.items?.first()?.title)
        assertEquals("Item 1: High Street Cycle Lane - new protected lane", result?.items?.first()?.extract)
    }

    @Test
    fun `phase 3 returns not relevant when no matching items`() = runBlocking {
        val scraper = webScraper(
            mapOf("https://council.example.com/agenda/1" to "<html><body><p>Budget discussion</p></body></html>"),
        )
        val llm = MockLlmClient { _, _ ->
            """{"type":"agenda_triaged","relevant":false}"""
        }
        val phase = TriageAgendaPhase(scraper, llm, maxIterations = 3)

        val result = phase.execute(
            TriageAgendaInput(
                "https://council.example.com/agenda/1",
                "Transport Committee",
                "2025-01-15"
            )
        )

        assertEquals(false, result?.relevant)
        assertEquals(0, result?.items?.size)
    }

    @Test
    fun `phase 3 iterates with agenda_item_fetch and accumulates items`() = runBlocking {
        val scraper = webScraper(
            mapOf(
                "https://council.example.com/agenda/1" to "<html><body><p>Agenda with items</p></body></html>",
                "https://council.example.com/report/1" to "<html><body><p>Report details</p></body></html>",
            ),
        )
        var callCount = 0
        val llm = MockLlmClient { _, _ ->
            callCount++
            if (callCount == 1) {
                """{"type":"agenda_item_fetch","urls":["https://council.example.com/report/1"],"reason":"Fetching report for Cycle Lane item","items":[{"title":"Traffic Filter","extract":"Detailed extract about traffic filter scheme"}]}"""
            } else {
                """{"type":"agenda_triaged","relevant":true,"items":[{"title":"Cycle Lane","extract":"Detailed extract about cycle lane from report"}]}"""
            }
        }
        val phase = TriageAgendaPhase(scraper, llm, maxIterations = 3)

        val result = phase.execute(
            TriageAgendaInput(
                "https://council.example.com/agenda/1",
                "Transport Committee",
                "2025-01-15"
            )
        )

        assertEquals(2, callCount)
        assertEquals(true, result?.relevant)
        assertEquals(2, result?.items?.size)
        val titles = result?.items?.map { it.title }?.toSet()
        assertEquals(setOf("Traffic Filter", "Cycle Lane"), titles)
    }

    @Test
    fun `phase 3 includes fetch reason in subsequent prompt`() = runBlocking {
        val scraper = webScraper(
            mapOf(
                "https://council.example.com/agenda/1" to "<html><body><p>Agenda</p></body></html>",
                "https://council.example.com/report/1" to "<html><body><p>Report</p></body></html>",
            ),
        )
        var secondUserPrompt: String? = null
        var callCount = 0
        val llm = MockLlmClient { _, userPrompt ->
            callCount++
            if (callCount == 1) {
                """{"type":"agenda_item_fetch","urls":["https://council.example.com/report/1"],"reason":"Need to read the full cycle lane report","items":[]}"""
            } else {
                secondUserPrompt = userPrompt
                """{"type":"agenda_triaged","relevant":false}"""
            }
        }
        val phase = TriageAgendaPhase(scraper, llm, maxIterations = 3)

        phase.execute(TriageAgendaInput("https://council.example.com/agenda/1", "Transport Committee", "2025-01-15"))

        assertTrue(secondUserPrompt!!.contains("Need to read the full cycle lane report"))
    }

    @Test
    fun `phase 3 includes accumulated items in subsequent prompt`() = runBlocking {
        val scraper = webScraper(
            mapOf(
                "https://council.example.com/agenda/1" to "<html><body><p>Agenda</p></body></html>",
                "https://council.example.com/report/1" to "<html><body><p>Report</p></body></html>",
            ),
        )
        var secondUserPrompt: String? = null
        var callCount = 0
        val llm = MockLlmClient { _, userPrompt ->
            callCount++
            if (callCount == 1) {
                """{"type":"agenda_item_fetch","urls":["https://council.example.com/report/1"],"reason":"Need report","items":[{"title":"Traffic Filter","extract":"Existing extract"}]}"""
            } else {
                secondUserPrompt = userPrompt
                """{"type":"agenda_triaged","relevant":true,"items":[]}"""
            }
        }
        val phase = TriageAgendaPhase(scraper, llm, maxIterations = 3)

        phase.execute(TriageAgendaInput("https://council.example.com/agenda/1", "Transport Committee", "2025-01-15"))

        assertTrue(secondUserPrompt!!.contains("Traffic Filter"))
        assertTrue(secondUserPrompt!!.contains("Existing extract"))
    }

    @Test
    fun `phase 3 merges items preferring newer version`() = runBlocking {
        val scraper = webScraper(
            mapOf(
                "https://council.example.com/agenda/1" to "<html><body><p>Agenda</p></body></html>",
                "https://council.example.com/report/1" to "<html><body><p>Report</p></body></html>",
            ),
        )
        var callCount = 0
        val llm = MockLlmClient { _, _ ->
            callCount++
            if (callCount == 1) {
                """{"type":"agenda_item_fetch","urls":["https://council.example.com/report/1"],"reason":"Need report","items":[{"title":"Cycle Lane","extract":"Brief extract"}]}"""
            } else {
                """{"type":"agenda_triaged","relevant":true,"items":[{"title":"Cycle Lane","extract":"Updated detailed extract from report"}]}"""
            }
        }
        val phase = TriageAgendaPhase(scraper, llm, maxIterations = 3)

        val result = phase.execute(
            TriageAgendaInput(
                "https://council.example.com/agenda/1",
                "Transport Committee",
                "2025-01-15"
            )
        )

        assertEquals(1, result?.items?.size)
        assertEquals("Updated detailed extract from report", result?.items?.first()?.extract)
    }

    @Test
    fun `phase 3 returns accumulated items on max iterations`() = runBlocking {
        val scraper = webScraper(
            mapOf("https://council.example.com/agenda/1" to "<html><body><p>Agenda</p></body></html>"),
        )
        val llm = MockLlmClient { _, _ ->
            """{"type":"agenda_item_fetch","urls":["https://council.example.com/agenda/1"],"reason":"Need more","items":[{"title":"Cycle Lane","extract":"Some extract"}]}"""
        }
        val phase = TriageAgendaPhase(scraper, llm, maxIterations = 2)

        val result = phase.execute(
            TriageAgendaInput(
                "https://council.example.com/agenda/1",
                "Transport Committee",
                "2025-01-15"
            )
        )

        assertEquals(true, result?.relevant)
        assertEquals(1, result?.items?.size)
        assertEquals("Cycle Lane", result?.items?.first()?.title)
    }

    @Test
    fun `phase 3 continues processing queued URLs when LLM returns agenda_triaged early`() = runBlocking {
        val scraper = webScraper(
            mapOf(
                "https://council.example.com/agenda/1" to "<html><body><p>Agenda with items</p></body></html>",
                "https://council.example.com/report/1" to "<html><body><p>Report 1</p></body></html>",
                "https://council.example.com/report/2" to "<html><body><p>Report 2</p></body></html>",
            ),
        )
        var callCount = 0
        val llm = MockLlmClient { _, _ ->
            callCount++
            when (callCount) {
                1 -> """{"type":"agenda_item_fetch","urls":["https://council.example.com/report/1","https://council.example.com/report/2"],"reason":"Fetching reports","items":[{"title":"Traffic Filter","extract":"Extract about traffic filter"}]}"""
                2 -> """{"type":"agenda_triaged","relevant":true,"items":[{"title":"Cycle Lane","extract":"Extract about cycle lane"}]}"""
                3 -> """{"type":"agenda_triaged","relevant":true,"items":[{"title":"Bus Route","extract":"Extract about bus route"}]}"""
                else -> error("Unexpected call $callCount")
            }
        }
        val phase = TriageAgendaPhase(scraper, llm, maxIterations = 5)

        val result = phase.execute(
            TriageAgendaInput(
                "https://council.example.com/agenda/1",
                "Transport Committee",
                "2025-01-15"
            )
        )

        assertEquals(3, callCount)
        assertEquals(true, result?.relevant)
        assertEquals(3, result?.items?.size)
        val titles = result?.items?.map { it.title }?.toSet()
        assertEquals(setOf("Traffic Filter", "Cycle Lane", "Bus Route"), titles)
    }

    @Test
    fun `phase 3 rejects plain fetch response`() = runBlocking {
        val scraper = webScraper(
            mapOf(
                "https://council.example.com/agenda/1" to "<html><body><p>Agenda</p></body></html>",
            ),
        )
        val llm = MockLlmClient { _, _ ->
            """{"type":"fetch","urls":["https://council.example.com/meetings"],"reason":"Going back to meetings"}"""
        }
        val phase = TriageAgendaPhase(scraper, llm, maxIterations = 3)

        val result = phase.execute(
            TriageAgendaInput(
                "https://council.example.com/agenda/1",
                "Transport Committee",
                "2025-01-15"
            )
        )

        assertNull(result)
    }

    // --- Phase 4: Analyze extract ---

    @Test
    fun `phase 4 returns schemes from extract`() = runBlocking {
        val scraper = webScraper(emptyMap())
        val llm = MockLlmClient { _, _ ->
            """{"type":"agenda_analyzed","schemes":[{"title":"High Street Cycle Lane","topic":"cycle lanes","summary":"New protected lane"}]}"""
        }
        val phase = AnalyzeExtractPhase(scraper, llm)

        val meeting = Meeting(date = "2026-03-15", title = "Planning Meeting", meetingUrl = "https://council.example.com/agenda/1")
        val result = phase.execute(
            AnalyzeExtractInput("Item 1: High Street Cycle Lane - new protected lane", "Planning", meeting),
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
        val llm = MockLlmClient { _, _ ->
            """{"type":"fetch","urls":["https://example.com"],"reason":"Need more"}"""
        }
        val phase = FindCommitteePagesPhase(scraper, llm, maxIterations = 2)

        val result = phase.execute(FindCommitteePagesInput("https://example.com", listOf("Planning")))

        assertNull(result)
    }

    @Test
    fun `returns null when all fetches fail`() = runBlocking {
        val scraper = webScraper(emptyMap())
        val llm = MockLlmClient { _, _ -> """{"type":"committee_pages_found","committees":[{"name":"Planning","url":"https://x.com"}]}""" }
        val phase = FindCommitteePagesPhase(scraper, llm, maxIterations = 3)

        val result = phase.execute(FindCommitteePagesInput("https://missing.example.com", listOf("Planning")))

        assertNull(result)
    }

    @Test
    fun `returns null on malformed LLM response`() = runBlocking {
        val scraper = webScraper(mapOf("https://example.com" to "<html><body><p>Page</p></body></html>"))
        val llm = MockLlmClient { _, _ -> "this is not json" }
        val phase = FindCommitteePagesPhase(scraper, llm, maxIterations = 3)

        val result = phase.execute(FindCommitteePagesInput("https://example.com", listOf("Planning")))

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
        val llm = MockLlmClient { _, userPrompt ->
            // The user prompt (page content) should contain @N tokens in markdown links instead of full URLs
            val linkToken = Regex("""\[Committees]\((@\d+)\)""").find(userPrompt)!!.groupValues[1]
            """{"type":"committee_pages_found","committees":[{"name":"Planning","url":"$linkToken"}]}"""
        }
        val phase = FindCommitteePagesPhase(scraper, llm, maxIterations = 3)

        val result = phase.execute(FindCommitteePagesInput("https://council.example.com", listOf("Planning")))

        // The token should have been resolved back to the full URL
        assertEquals(mapOf("Planning" to "https://council.example.com/committees"), result)
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
        val llm = MockLlmClient { _, _ ->
            callCount++
            when (callCount) {
                1 -> """{"type":"committee_pages_found","committees":[{"name":"Planning","url":"https://council.example.com/planning"}]}"""
                2 -> """{"type":"meetings_found","meetings":[{"date":"2026-03-15","title":"Planning Meeting","meetingUrl":"https://council.example.com/agenda/1"}]}"""
                3 -> """{"type":"agenda_triaged","relevant":true,"items":[{"title":"Cycle Lane proposal","extract":"Item 1: Cycle Lane proposal - detailed description of the proposed cycle lane on High Street"}]}"""
                4 -> """{"type":"agenda_analyzed","schemes":[{"title":"Cycle Lane","topic":"cycle lanes","summary":"New lane"}]}"""
                else -> error("Unexpected call $callCount")
            }
        }
        val processed = mutableListOf<List<Scheme>>()
        val processor = ResultProcessor { _, _, schemes -> processed.add(schemes) }
        val orchestrator = Orchestrator(
            FindCommitteePagesPhase(scraper, llm, maxIterations = 5),
            FindMeetingsPhase(scraper, llm, maxIterations = 5),
            TriageAgendaPhase(scraper, llm, maxIterations = 5),
            AnalyzeExtractPhase(scraper, llm),
            processor,
        )

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
