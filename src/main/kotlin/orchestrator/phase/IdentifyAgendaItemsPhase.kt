package orchestrator.phase

import llm.LlmClient
import orchestrator.IdentifiedAgendaItem
import orchestrator.LlmResponse
import orchestrator.buildIdentifyAgendaItemsPrompt
import orchestrator.resolveUrls
import org.slf4j.LoggerFactory
import scraper.WebScraper

private val logger = LoggerFactory.getLogger(IdentifyAgendaItemsPhase::class.java)

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
        val urlQueue = mutableListOf(input.agendaUrl)
        val accumulatedItems = mutableMapOf<String, IdentifiedAgendaItem>()
        var fetchReason: String? = null

        for (iteration in 1..maxIterations) {
            val url = urlQueue.removeFirstOrNull() ?: break
            logger.info("{} — iteration {}: fetching {}", name, iteration, url)

            val conversionResult = webScraper.fetchAndExtract(url)
            if (conversionResult == null) {
                logger.warn("{} — fetch failed for {}", name, url)
                continue
            }

            val prompt = buildIdentifyAgendaItemsPrompt(
                input.committeeName,
                input.meetingDate,
                conversionResult.text,
                fetchReason,
            )
            logger.trace("LLM Prompt {}", prompt.user)
            val rawResponse = llmClient.generate(prompt.system, prompt.user, lightModel)
            logger.debug("LLM response {}", rawResponse)
            val response = parseResponse(rawResponse)
                ?.resolveUrls(conversionResult.urlRegistry::resolve) ?: return null

            when (response) {
                is LlmResponse.AgendaItemsIdentified -> {
                    response.items.associateByTo(accumulatedItems) { it.title }
                    if (response.fetchUrls.isEmpty()) {
                        return accumulatedItems.values.toList()
                    }
                    fetchReason = response.fetchReason
                    urlQueue.addAll(response.fetchUrls)
                    logger.info(
                        "{} — LLM requests {} more page(s). Items so far: {}",
                        name, response.fetchUrls.size, accumulatedItems.size,
                    )
                }
                else -> {
                    logger.warn("{} — unexpected response type: {}", name, response::class.simpleName)
                    return null
                }
            }
        }

        logger.warn("{} — max iterations ({}) reached", name, maxIterations)
        return if (accumulatedItems.isNotEmpty()) accumulatedItems.values.toList() else null
    }
}
