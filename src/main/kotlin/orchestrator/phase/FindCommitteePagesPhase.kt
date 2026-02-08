package orchestrator.phase

import llm.LlmClient
import orchestrator.Orchestrator
import orchestrator.LlmResponse
import orchestrator.buildPhase1Prompt
import scraper.WebScraper

data class FindCommitteePagesInput(
    val startUrl: String,
    val committeeNames: List<String>,
)

class FindCommitteePagesPhase(
    webScraper: WebScraper,
    llmClient: LlmClient,
    private val lightModel: String = Orchestrator.Companion.DEFAULT_LIGHT_MODEL,
    private val maxIterations: Int = Orchestrator.Companion.DEFAULT_MAX_ITERATIONS,
) : BasePhase(webScraper, llmClient), Phase<FindCommitteePagesInput, Map<String, String>> {

    override val name = "Phase 1: Find committee pages"

    override suspend fun execute(input: FindCommitteePagesInput): Map<String, String>? {
        return navigationLoop(
            startUrl = input.startUrl,
            phaseName = name,
            model = lightModel,
            maxIterations = maxIterations,
            buildPrompt = { content -> buildPhase1Prompt(input.committeeNames, content) },
            extractResult = { response ->
                (response as? LlmResponse.CommitteePagesFound)
                    ?.committees
                    ?.associate { it.name to it.url }
            },
        )
    }
}
