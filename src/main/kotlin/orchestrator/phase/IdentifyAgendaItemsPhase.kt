package orchestrator.phase

import llm.LlmClient
import orchestrator.IdentifiedAgendaItem
import orchestrator.LlmResponse
import orchestrator.buildIdentifyAgendaItemsPrompt
import scraper.WebScraper

data class IdentifyAgendaItemsInput(
    val agendaUrl: String,
    val committeeName: String,
    val meetingDate: String,
)

class IdentifyAgendaItemsPhase(
    webScraper: WebScraper,
    llmClient: LlmClient,
    private val lightModel: String = DEFAULT_LIGHT_MODEL,
    private val maxIterations: Int = DEFAULT_MAX_ITERATIONS,
) : BasePhase(webScraper, llmClient), Phase<IdentifyAgendaItemsInput, List<IdentifiedAgendaItem>> {

    override val name = "Phase 4: Identify agenda items"

    override suspend fun execute(input: IdentifyAgendaItemsInput): List<IdentifiedAgendaItem>? {
        return navigationLoop(
            startUrl = input.agendaUrl,
            phaseName = name,
            model = lightModel,
            maxIterations = maxIterations,
            buildPrompt = { content -> buildIdentifyAgendaItemsPrompt(input.committeeName, input.meetingDate, content) },
            extractResult = { (it as? LlmResponse.AgendaItemsIdentified)?.items },
        )
    }
}
