package orchestrator

import com.anthropic.client.okhttp.AnthropicOkHttpClientAsync
import config.CouncilConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import llm.ClaudeLlmClient
import llm.MockLlmClient
import org.junit.jupiter.api.Tag
import processor.ResultProcessor
import scraper.ContentExtractor
import scraper.WebScraper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EndToEndTest {

    private val base = "https://moderngov.kingston.gov.uk"

    private val urlToResource = mapOf(
        "$base/mgCommitteeStructure.aspx" to "CommitteeStructure.html",
        "$base/mgCommitteeDetails.aspx?ID=711" to "CommitteeDetails.html",
        "$base/ieListMeetings.aspx?CommitteeId=711" to "BrowseMeetings.html",
        "$base/ieListDocuments.aspx?CId=711&MId=10161&Ver=4" to "Agenda_412.html",
        "$base/ieListDocuments.aspx?CId=711&MId=10221&Ver=4" to "Agenda_151.html",
    )

    private fun loadHtml(resource: String): String =
        javaClass.getResource("/$resource")!!.readText()

    private val htmlResponses = urlToResource.mapValues { (_, resource) -> loadHtml(resource) }

    private fun mockWebScraper(): WebScraper {
        val engine = MockEngine { request ->
            val body = htmlResponses[request.url.toString()]
            if (body != null) {
                respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/html"))
            } else {
                respond("Not Found", HttpStatusCode.NotFound)
            }
        }
        return WebScraper(HttpClient(engine), ContentExtractor())
    }

    private val councilConfig = CouncilConfig(
        name = "Kingston",
        siteUrl = "$base/mgCommitteeStructure.aspx",
        committees = listOf("Kingston and North Kingston Neighbourhood Committee"),
        dateFrom = "2025-12-01",
        dateTo = "2026-01-31",
    )

    @Test
    fun `end-to-end with mock LLM`() = runBlocking {
        var callCount = 0
        val llm = MockLlmClient { _, _ ->
            callCount++
            when (callCount) {
                // Phase 1: CommitteeStructure → find committee pages
                1 -> """{"type":"committee_pages_found","committees":[{"name":"Kingston and North Kingston Neighbourhood Committee","url":"$base/mgCommitteeDetails.aspx?ID=711"}]}"""
                // Phase 2, iter 1: CommitteeDetails → fetch meetings listing
                2 -> """{"type":"fetch","urls":["$base/ieListMeetings.aspx?CommitteeId=711"],"reason":"Following browse meetings link"}"""
                // Phase 2, iter 2: BrowseMeetings → extract meetings in date range
                3 -> """{"type":"meetings_found","meetings":[{"date":"2025-12-04","title":"Kingston and North Kingston Neighbourhood Committee (Cancelled)","agendaUrl":"$base/ieListDocuments.aspx?CId=711&MId=10161&Ver=4"},{"date":"2026-01-15","title":"Kingston and North Kingston Neighbourhood Committee","agendaUrl":"$base/ieListDocuments.aspx?CId=711&MId=10221&Ver=4"}]}"""
                // Phase 3: Triage Agenda_412 (cancelled meeting) → not relevant
                4 -> """{"type":"agenda_triaged","relevant":false}"""
                // Phase 3: Triage Agenda_151 (real meeting) → relevant, extract items
                5 -> """{"type":"agenda_triaged","relevant":true,"items":[{"title":"Manorgate Road Traffic Management","extract":"Item 5: Manorgate Road Traffic Management - proposal for traffic filters in the Manorgate Road area."},{"title":"Coombe Lane West Zebra Crossing","extract":"Item 8: Coombe Lane West Zebra Crossing - proposed zebra crossing on Coombe Lane West."}]}"""
                // Phase 4: Analyze extract → schemes found
                6 -> """{"type":"agenda_analyzed","schemes":[{"title":"Manorgate Road Traffic Management","topic":"traffic filters","summary":"Traffic management scheme for Manorgate Road area","meetingDate":"2026-01-15","committeeName":"Kingston and North Kingston Neighbourhood Committee"},{"title":"Coombe Lane West Zebra Crossing","topic":"public realm improvements","summary":"Proposed zebra crossing on Coombe Lane West","meetingDate":"2026-01-15","committeeName":"Kingston and North Kingston Neighbourhood Committee"}]}"""
                else -> error("Unexpected LLM call $callCount")
            }
        }

        val processed = mutableListOf<List<Scheme>>()
        val processor = ResultProcessor { _, _, schemes -> processed.add(schemes) }
        val orchestrator = Orchestrator(mockWebScraper(), llm, processor, maxIterations = 5)

        orchestrator.processCouncil(councilConfig)

        assertEquals(6, callCount, "Expected exactly 6 LLM calls")
        assertEquals(1, processed.size, "ResultProcessor should be called once (one committee)")
        assertEquals(2, processed[0].size, "Expected 2 schemes from the January meeting")
        assertEquals("Manorgate Road Traffic Management", processed[0][0].title)
        assertEquals("Coombe Lane West Zebra Crossing", processed[0][1].title)
    }

    @Test
    @Tag("real-llm")
    fun `end-to-end with real LLM`() = runBlocking {
        val apiKey = System.getenv("ANTHROPIC_API_KEY")
        val anthropicClient = AnthropicOkHttpClientAsync.builder()
            .apiKey(apiKey)
            .build()
        val llm = ClaudeLlmClient(anthropicClient)

        val processed = mutableListOf<List<Scheme>>()
        val processor = ResultProcessor { _, _, schemes -> processed.add(schemes) }
        val orchestrator = Orchestrator(mockWebScraper(), llm, processor, maxIterations = 5)

        orchestrator.processCouncil(councilConfig)

        assertEquals(1, processed.size, "ResultProcessor should be called once (one committee)")
        val titles = processed[0].map { it.title }
        assertTrue(
            titles.any { "Traffic Management Measures On Manorgate Road" in it },
            "Expected scheme with title containing 'Traffic Management Measures On Manorgate Road', got: $titles",
        )
        assertTrue(
            titles.any { "Coombe Lane West Zebra Crossing" in it },
            "Expected scheme with title containing 'Coombe Lane West Zebra Crossing', got: $titles",
        )
    }
}
