package orchestrator.phase

import llm.LlmClient
import orchestrator.DecisionEntry
import orchestrator.LlmResponse
import orchestrator.buildEnrichDecisionPrompt
import orchestrator.resolveUrls
import scraper.WebScraper

data class EnrichDecisionInput(
    val decision: DecisionEntry,
)

class EnrichDecisionPhase(
    webScraper: WebScraper,
    llmClient: LlmClient,
    private val lightModel: String = DEFAULT_LIGHT_MODEL,
    private val maxIterations: Int = DEFAULT_TRIAGE_MAX_ITERATIONS,
) : BasePhase<EnrichDecisionInput, LlmResponse.DecisionEnriched>(webScraper, llmClient) {

    override val name = "Enrich decision"

    override suspend fun doExecute(input: EnrichDecisionInput): LlmResponse.DecisionEnriched? {
        var currentUrl = input.decision.detailUrl
        val processedUrls = mutableSetOf(currentUrl)
        var currentExtract = null as orchestrator.TriagedItem?

        for (iteration in 1..maxIterations) {
            logger.info("{} — iteration {}: fetching {}", name, iteration, currentUrl)

            val conversionResult = fetchAndExtract(currentUrl)
            if (conversionResult == null) {
                logger.warn("{} — fetch failed for {}", name, currentUrl)
                if (iteration == 1) return null
                else break
            }

            val prompt = buildEnrichDecisionPrompt(input.decision, currentExtract, conversionResult.text)
            val raw = llmClient.generate(prompt.system, prompt.user, lightModel)
            val response = parseResponse(raw)?.resolveUrls(conversionResult.urlRegistry::resolve)
            if (response == null) return null

            when (response) {
                is LlmResponse.DecisionEnriched -> return response

                is LlmResponse.DecisionFetch -> {
                    if (response.extract != null) currentExtract = response.extract
                    val newUrls = response.urls.filterNot { it in processedUrls }
                    if (newUrls.isEmpty()) break
                    currentUrl = newUrls.first()
                    processedUrls += currentUrl
                    logger.info("{} — fetching additional document: {} ({})", name, currentUrl, response.reason)
                }

                else -> {
                    logger.warn("{} — unexpected response type: {}", name, response::class.simpleName)
                    return null
                }
            }
        }

        logger.warn("{} — max iterations ({}) reached for '{}'", name, maxIterations, input.decision.title)
        return null
    }
}
