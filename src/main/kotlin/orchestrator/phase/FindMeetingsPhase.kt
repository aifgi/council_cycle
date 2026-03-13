package orchestrator.phase

import llm.LlmClient
import orchestrator.Meeting
import orchestrator.LlmResponse
import orchestrator.buildPhase2Prompt
import scraper.WebScraper

data class FindMeetingsInput(
    val committeeUrl: String,
    val committeeName: String,
    val dateFrom: String,
    val dateTo: String,
)

class FindMeetingsPhase(
    webScraper: WebScraper,
    llmClient: LlmClient,
    private val lightModel: String = DEFAULT_LIGHT_MODEL,
    private val maxIterations: Int = DEFAULT_MAX_ITERATIONS,
) : BasePhase<FindMeetingsInput, List<Meeting>>(webScraper, llmClient) {

    override val name = "Phase 2: Find meetings"

    override suspend fun doExecute(input: FindMeetingsInput): List<Meeting>? {
        return navigationLoop(
            startUrl = input.committeeUrl,
            phaseName = name,
            model = lightModel,
            maxIterations = maxIterations,
            buildPrompt = { content ->
                buildPhase2Prompt(input.committeeName, input.dateFrom, input.dateTo, content)
            },
            extractResult = { response -> (response as? LlmResponse.MeetingsFound)?.meetings },
        )
    }
}
