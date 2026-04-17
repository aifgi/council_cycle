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
import orchestrator.phase.EnrichAgendaItemsPhase
import orchestrator.phase.EnrichDecisionInput
import orchestrator.phase.EnrichDecisionPhase
import orchestrator.phase.FindAgendaPhase
import orchestrator.phase.FindCommitteePagesPhase
import orchestrator.phase.FindDecisionsInput
import orchestrator.phase.FindDecisionsPhase
import orchestrator.phase.FindMeetingsPhase
import orchestrator.phase.IdentifyAgendaItemsPhase
import processor.ResultProcessor
import scraper.HtmlExtractor
import scraper.PdfExtractor
import scraper.WebScraper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OrchestratorDecisionsTest {

    private fun webScraper(responses: Map<String, String> = emptyMap()): WebScraper {
        val engine = MockEngine { request ->
            val body = responses[request.url.toString()]
            if (body != null) {
                respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/html"))
            } else {
                respond("<html><body></body></html>", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/html"))
            }
        }
        return WebScraper(HttpClient(engine), HtmlExtractor(), PdfExtractor())
    }

    private fun decisionsCouncil(
        decisionMakers: List<String> = listOf("Cabinet Member for Transport"),
    ) = CouncilConfig(
        name = "Test Council",
        mode = "decisions",
        decisionsUrl = "https://council.example.com/decisions",
        decisionMakers = decisionMakers,
        dateFrom = "2026-01-01",
        dateTo = "2026-06-30",
    )

    private fun meetingsCouncil() = CouncilConfig(
        name = "Test Council",
        mode = "meetings",
        meetingsUrl = "https://council.example.com",
        committees = listOf("Planning"),
        dateFrom = "2026-01-01",
        dateTo = "2026-06-30",
    )

    private fun noModeCouncil() = CouncilConfig(
        name = "Test Council",
        // mode not set → defaults to "meetings"
        meetingsUrl = "https://council.example.com",
        committees = listOf("Planning"),
        dateFrom = "2026-01-01",
        dateTo = "2026-06-30",
    )

    private fun makeOrchestrator(
        scraper: WebScraper,
        llm: MockLlmClient,
        processor: ResultProcessor = ResultProcessor { _, _, _ -> },
    ) = Orchestrator(
        FindCommitteePagesPhase(scraper, llm),
        FindMeetingsPhase(scraper, llm),
        FindAgendaPhase(scraper, llm),
        IdentifyAgendaItemsPhase(scraper, llm),
        EnrichAgendaItemsPhase(scraper, llm),
        AnalyzeExtractPhase(scraper, llm),
        FindDecisionsPhase(scraper, llm),
        EnrichDecisionPhase(scraper, llm),
        processor,
    )

    // 1. dispatches to decisions pipeline for mode=decisions
    @Test
    fun `dispatches to decisions pipeline for mode=decisions`() = runBlocking {
        val calledPhases = mutableListOf<String>()
        val scraper = webScraper(
            mapOf("https://council.example.com/decisions" to "<html><body><p>Decisions</p></body></html>"),
        )
        val llm = MockLlmClient { systemPrompt, _ ->
            when {
                systemPrompt.contains("decisions", ignoreCase = true) || systemPrompt.contains("decision makers", ignoreCase = true) -> {
                    calledPhases.add("decisions")
                    """{"type":"decisions_page_scanned","decisions":[]}"""
                }
                systemPrompt.contains("committee", ignoreCase = true) -> {
                    calledPhases.add("meetings")
                    """{"type":"committee_pages_found","committees":[]}"""
                }
                else -> {
                    calledPhases.add("unknown")
                    """{"type":"decisions_page_scanned","decisions":[]}"""
                }
            }
        }

        makeOrchestrator(scraper, llm).processCouncil(decisionsCouncil())

        assertTrue(calledPhases.contains("decisions"), "decisions phase should have been called")
        assertFalse(calledPhases.contains("meetings"), "meetings phase should not have been called")
    }

    // 2. dispatches to meetings pipeline for mode=meetings
    @Test
    fun `dispatches to meetings pipeline for mode=meetings`() = runBlocking {
        val calledPhases = mutableListOf<String>()
        val scraper = webScraper(
            mapOf("https://council.example.com" to "<html><body><p>Main</p></body></html>"),
        )
        val llm = MockLlmClient { systemPrompt, _ ->
            when {
                systemPrompt.contains("committee", ignoreCase = true) -> {
                    calledPhases.add("meetings")
                    """{"type":"committee_pages_found","committees":[]}"""
                }
                else -> {
                    calledPhases.add("decisions")
                    """{"type":"decisions_page_scanned","decisions":[]}"""
                }
            }
        }

        makeOrchestrator(scraper, llm).processCouncil(meetingsCouncil())

        assertTrue(calledPhases.contains("meetings"), "meetings phase should have been called")
        assertFalse(calledPhases.contains("decisions"), "decisions phase should not have been called")
    }

    // 3. dispatches to meetings pipeline when mode field absent (default "meetings")
    @Test
    fun `dispatches to meetings pipeline when mode field absent`() = runBlocking {
        val calledPhases = mutableListOf<String>()
        val scraper = webScraper(
            mapOf("https://council.example.com" to "<html><body><p>Main</p></body></html>"),
        )
        val llm = MockLlmClient { systemPrompt, _ ->
            when {
                systemPrompt.contains("committee", ignoreCase = true) -> {
                    calledPhases.add("meetings")
                    """{"type":"committee_pages_found","committees":[]}"""
                }
                else -> {
                    calledPhases.add("decisions")
                    """{"type":"decisions_page_scanned","decisions":[]}"""
                }
            }
        }

        // noModeCouncil has no mode field → should default to "meetings"
        makeOrchestrator(scraper, llm).processCouncil(noModeCouncil())

        assertTrue(calledPhases.contains("meetings"), "meetings phase should have been called for default mode")
        assertFalse(calledPhases.contains("decisions"), "decisions phase should not have been called")
    }

    // 4. decisions pipeline produces schemes with correct field mapping
    @Test
    fun `decisions pipeline produces schemes with correct field mapping`() = runBlocking {
        val scraper = webScraper(
            mapOf(
                "https://council.example.com/decisions" to "<html><body><p>Decisions list</p></body></html>",
                "https://council.example.com/decisions/42" to "<html><body><p>Decision detail</p></body></html>",
            ),
        )
        var callCount = 0
        val llm = MockLlmClient { _, _ ->
            callCount++
            when (callCount) {
                1 -> """{"type":"decisions_page_scanned","decisions":[{"title":"High Street Cycle Lane","decisionDate":"2026-03-10","detailUrl":"https://council.example.com/decisions/42"}]}"""
                2 -> """{"type":"decision_enriched","item":{"title":"High Street Cycle Lane","extract":"Detailed extract"},"decisionMaker":"Cabinet Member for Transport"}"""
                3 -> """{"type":"agenda_analyzed","schemes":[{"title":"High Street Cycle Lane","topic":"cycle lanes","summary":"New lane"}]}"""
                else -> error("Unexpected call $callCount")
            }
        }

        val processed = mutableListOf<List<Scheme>>()
        val processor = ResultProcessor { _, _, schemes -> processed.add(schemes) }

        makeOrchestrator(scraper, llm, processor).processCouncil(decisionsCouncil())

        assertEquals(1, processed.size)
        val scheme = processed[0].first()
        assertEquals("2026-03-10", scheme.meetingDate, "meetingDate should be decision date")
        assertEquals("Cabinet Member for Transport", scheme.committeeName, "committeeName should be decision maker label")
        assertEquals("https://council.example.com/decisions/42", scheme.agendaUrl, "agendaUrl should be detail page URL")
    }

    // 5. decisions pipeline uses decisionMaker from DecisionEnriched for committeeName fallback chain
    @Test
    fun `decisions pipeline uses decisionMaker from DecisionEnriched for committeeName fallback chain`() = runBlocking {
        val scraper = webScraper(
            mapOf(
                "https://council.example.com/decisions" to "<html><body><p>Decisions</p></body></html>",
                "https://council.example.com/decisions/1" to "<html><body><p>Decision A detail</p></body></html>",
                "https://council.example.com/decisions/2" to "<html><body><p>Decision B detail</p></body></html>",
            ),
        )
        var callCount = 0
        val capturedCommitteeNames = mutableListOf<String>()
        val llm = MockLlmClient { _, _ ->
            callCount++
            when (callCount) {
                1 -> """{"type":"decisions_page_scanned","decisions":[{"title":"Decision A","decisionDate":"2026-03-01","detailUrl":"https://council.example.com/decisions/1","decisionMaker":"Entry Maker A"},{"title":"Decision B","decisionDate":"2026-03-02","detailUrl":"https://council.example.com/decisions/2","decisionMaker":"Entry Maker B"}]}"""
                2 -> """{"type":"decision_enriched","item":{"title":"Decision A","extract":"extract A"},"decisionMaker":"Enriched Maker A"}"""  // non-null enriched.decisionMaker
                3 -> {
                    // capture what committeeName is passed to analyze
                    """{"type":"agenda_analyzed","schemes":[{"title":"Decision A","topic":"t","summary":"s"}]}"""
                }
                4 -> """{"type":"decision_enriched","item":{"title":"Decision B","extract":"extract B"}}"""  // null decisionMaker in enriched
                5 -> """{"type":"agenda_analyzed","schemes":[{"title":"Decision B","topic":"t","summary":"s"}]}"""
                else -> error("Unexpected call $callCount")
            }
        }

        val processed = mutableListOf<Pair<String, List<Scheme>>>()
        val processor = ResultProcessor { _, label, schemes ->
            schemes.forEach { processed.add(label to listOf(it)) }
        }

        makeOrchestrator(scraper, llm, processor).processCouncil(decisionsCouncil())

        // Check that Decision A used enriched.decisionMaker (non-null)
        val schemeA = processed.firstOrNull { it.second.any { s -> s.title == "Decision A" } }?.second?.first()
        assertEquals("Enriched Maker A", schemeA?.committeeName, "enriched.decisionMaker should be used when non-null")

        // Check that Decision B fell back to decision.decisionMaker (entry-level)
        val schemeB = processed.firstOrNull { it.second.any { s -> s.title == "Decision B" } }?.second?.first()
        assertEquals("Entry Maker B", schemeB?.committeeName, "should fall back to decision.decisionMaker when enriched.decisionMaker is null")
    }

    // 6. decisions pipeline skips decisions where enrich phase returns null
    @Test
    fun `decisions pipeline skips decisions where enrich phase returns null`() = runBlocking {
        val scraper = webScraper(
            mapOf(
                "https://council.example.com/decisions" to "<html><body><p>Decisions</p></body></html>",
                "https://council.example.com/decisions/1" to "<html><body><p>Good 1</p></body></html>",
                "https://council.example.com/decisions/2" to "<html><body><p>Bad</p></body></html>",
                "https://council.example.com/decisions/3" to "<html><body><p>Good 3</p></body></html>",
            ),
        )
        var callCount = 0
        val analyzedTitles = mutableListOf<String>()
        val llm = MockLlmClient { systemPrompt, userPrompt ->
            callCount++
            when (callCount) {
                // find phase: returns 3 decisions
                1 -> """{"type":"decisions_page_scanned","decisions":[{"title":"Good Decision 1","decisionDate":"2026-03-01","detailUrl":"https://council.example.com/decisions/1"},{"title":"Bad Decision","decisionDate":"2026-03-02","detailUrl":"https://council.example.com/decisions/2"},{"title":"Good Decision 3","decisionDate":"2026-03-03","detailUrl":"https://council.example.com/decisions/3"}]}"""
                // enrich Good Decision 1 → success
                2 -> """{"type":"decision_enriched","item":{"title":"Good Decision 1","extract":"extract 1"}}"""
                // analyze Good Decision 1
                3 -> {
                    analyzedTitles.add("Good Decision 1")
                    """{"type":"agenda_analyzed","schemes":[{"title":"Good Decision 1","topic":"t","summary":"s"}]}"""
                }
                // enrich Bad Decision → simulate failure by returning unexpected type
                4 -> """{"type":"decisions_page_scanned","decisions":[]}"""
                // enrich Good Decision 3 → success
                5 -> """{"type":"decision_enriched","item":{"title":"Good Decision 3","extract":"extract 3"}}"""
                // analyze Good Decision 3
                6 -> {
                    analyzedTitles.add("Good Decision 3")
                    """{"type":"agenda_analyzed","schemes":[{"title":"Good Decision 3","topic":"t","summary":"s"}]}"""
                }
                else -> error("Unexpected call $callCount")
            }
        }

        makeOrchestrator(scraper, llm).processCouncil(decisionsCouncil())

        assertEquals(2, analyzedTitles.size, "only 2 decisions should have been analyzed")
        assertTrue(analyzedTitles.contains("Good Decision 1"))
        assertTrue(analyzedTitles.contains("Good Decision 3"))
        assertFalse(analyzedTitles.contains("Bad Decision"))
    }

    // 7. decisions pipeline logs info and skips when find phase returns empty list
    @Test
    fun `decisions pipeline logs info and skips when find phase returns empty list`() = runBlocking {
        val scraper = webScraper(
            mapOf("https://council.example.com/decisions" to "<html><body><p>No decisions</p></body></html>"),
        )
        var enrichCallCount = 0
        var analyzeCallCount = 0
        var processorCallCount = 0
        val llm = MockLlmClient { systemPrompt, _ ->
            when {
                // find decisions phase prompt
                systemPrompt.contains("decision", ignoreCase = true) -> {
                    """{"type":"decisions_page_scanned","decisions":[]}"""
                }
                // enrich phase
                else -> {
                    enrichCallCount++
                    """{"type":"decision_enriched","item":{"title":"X","extract":"y"}}"""
                }
            }
        }
        val processor = ResultProcessor { _, _, _ -> processorCallCount++ }

        makeOrchestrator(scraper, llm, processor).processCouncil(decisionsCouncil())

        assertEquals(0, enrichCallCount, "enrich phase should not be called when no decisions found")
        assertEquals(0, processorCallCount, "processor should not be called when no decisions found")
    }
}
