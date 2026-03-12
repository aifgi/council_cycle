package orchestrator.phase

import llm.LlmClient
import orchestrator.LlmResponse
import orchestrator.buildPhase3aPrompt
import scraper.WebScraper

data class FindAgendaInput(val meetingUrl: String)

class FindAgendaPhase(
    webScraper: WebScraper,
    llmClient: LlmClient,
    private val lightModel: String = DEFAULT_LIGHT_MODEL,
    private val maxIterations: Int = DEFAULT_MAX_ITERATIONS,
) : BasePhase(webScraper, llmClient), Phase<FindAgendaInput, String> {

    override val name = "Phase 3: Find agenda"

    override suspend fun execute(input: FindAgendaInput): String? {
        return navigationLoop(
            startUrl = input.meetingUrl,
            phaseName = name,
            model = lightModel,
            maxIterations = maxIterations,
            buildPrompt = { content -> buildPhase3aPrompt(input.meetingUrl, content) },
            extractResult = { (it as? LlmResponse.AgendaFound)?.agendaUrl },
        )
    }
}
