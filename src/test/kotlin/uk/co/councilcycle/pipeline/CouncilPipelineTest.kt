package uk.co.councilcycle.pipeline

import kotlinx.coroutines.test.runTest
import uk.co.councilcycle.config.AppConfig
import uk.co.councilcycle.llm.LlmAnalyzer
import uk.co.councilcycle.model.*
import uk.co.councilcycle.publisher.BlueskyPublisher
import uk.co.councilcycle.scraper.WebScraper
import kotlin.test.Test
import kotlin.test.assertEquals

class CouncilPipelineTest {

    @Test
    fun `pipeline processes council and collects decisions`() = runTest {
        val testDecision = Decision(
            councilId = "test",
            councilName = "Test Council",
            meetingTitle = "Transport Committee",
            meetingUrl = "https://example.com/meetings",
            summary = "New cycle lane approved",
            category = DecisionCategory.CYCLE_LANE,
            relevanceScore = 8,
        )

        val scraper = object : WebScraper {
            override suspend fun fetchMeetingsPage(council: Council) = "<html>test</html>"
            override suspend fun fetchPage(url: String) = "<html>test</html>"
            override suspend fun findRecentMeetings(council: Council) = emptyList<Meeting>()
        }

        val analyzer = object : LlmAnalyzer {
            override suspend fun analyzePage(council: Council, pageContent: String, pageUrl: String) =
                AnalysisResult(
                    decisions = listOf(testDecision),
                    followUpUrls = emptyList(),
                    hasRelevantContent = true,
                )
        }

        val published = mutableListOf<Decision>()
        val publisher = object : BlueskyPublisher {
            override suspend fun publish(decision: Decision) { published.add(decision) }
            override suspend fun publishAll(decisions: List<Decision>) { published.addAll(decisions) }
        }

        val pipeline = CouncilPipeline(scraper, analyzer, publisher, AppConfig())
        val council = Council(id = "test", name = "Test Council", meetingsUrl = "https://example.com/meetings")
        val decisions = pipeline.processCouncil(council)

        assertEquals(1, decisions.size)
        assertEquals("New cycle lane approved", decisions[0].summary)
    }
}
