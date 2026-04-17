package orchestrator.phase

import llm.LlmClient
import orchestrator.DecisionEntry
import orchestrator.LlmResponse
import orchestrator.buildFindDecisionsPrompt
import orchestrator.resolveUrls
import scraper.WebScraper

data class FindDecisionsInput(
    val decisionsUrl: String,
    val decisionMakers: List<String>,
    val dateFrom: String,
    val dateTo: String,
)

class FindDecisionsPhase(
    webScraper: WebScraper,
    llmClient: LlmClient,
    private val lightModel: String = DEFAULT_LIGHT_MODEL,
    private val maxIterations: Int = DEFAULT_D2_MAX_ITERATIONS,
) : BasePhase<FindDecisionsInput, List<DecisionEntry>>(webScraper, llmClient) {

    override val name = "Find decisions"

    override suspend fun doExecute(input: FindDecisionsInput): List<DecisionEntry>? {
        val accumulated = mutableListOf<DecisionEntry>()
        var currentUrl = input.decisionsUrl
        var lastResponse: LlmResponse.DecisionsPageScanned? = null

        for (iteration in 1..maxIterations) {
            logger.info("{} — iteration {}: fetching {}", name, iteration, currentUrl)

            val conversionResult = fetchAndExtract(currentUrl)
            if (conversionResult == null) {
                logger.warn("{} — fetch failed for {}", name, currentUrl)
                break
            }

            val prompt = buildFindDecisionsPrompt(
                input.decisionMakers,
                input.dateFrom,
                input.dateTo,
                conversionResult.text,
            )
            val raw = llmClient.generate(prompt.system, prompt.user, lightModel)
            val response = parseResponse(raw)?.resolveUrls(conversionResult.urlRegistry::resolve)
            if (response == null) return null

            when (response) {
                is LlmResponse.DecisionsPageScanned -> {
                    lastResponse = response
                    accumulated += response.decisions
                    if (response.nextUrl == null) break
                    currentUrl = response.nextUrl

                    if (iteration == maxIterations) {
                        logger.warn(
                            "{} — max iterations ({}) reached for last page: {}. Returning {} decisions found so far.",
                            name, maxIterations, currentUrl, accumulated.size,
                        )
                    }
                }
                else -> {
                    logger.warn("{} — unexpected response type: {}", name, response::class.simpleName)
                    return null
                }
            }
        }

        return accumulated
    }
}
