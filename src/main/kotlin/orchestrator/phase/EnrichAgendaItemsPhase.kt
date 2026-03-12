package orchestrator.phase

import llm.LlmClient
import orchestrator.IdentifiedAgendaItem
import orchestrator.LlmResponse
import orchestrator.TriagedItem
import orchestrator.buildPhase3cPrompt
import orchestrator.resolveUrls
import org.slf4j.LoggerFactory
import scraper.WebScraper

private val logger = LoggerFactory.getLogger(EnrichAgendaItemsPhase::class.java)

data class EnrichAgendaItemsInput(
    val meetingUrl: String,
    val identifiedItems: List<IdentifiedAgendaItem>,
    val committeeName: String,
    val meetingDate: String,
)

class EnrichAgendaItemsPhase(
    webScraper: WebScraper,
    llmClient: LlmClient,
    private val lightModel: String = DEFAULT_LIGHT_MODEL,
    private val maxIterations: Int = DEFAULT_TRIAGE_MAX_ITERATIONS,
) : BasePhase(webScraper, llmClient), Phase<EnrichAgendaItemsInput, LlmResponse.AgendaTriaged> {

    override val name = "Phase 5: Enrich agenda items"

    override suspend fun execute(input: EnrichAgendaItemsInput): LlmResponse.AgendaTriaged? {
        val urlQueue = mutableListOf(input.meetingUrl)
        val fetchedUrls = mutableSetOf<String>()
        val accumulatedItems = mutableMapOf<String, TriagedItem>()
        var fetchReason: String? = null

        try {
            for (iteration in 1..maxIterations) {
                val url = urlQueue.removeFirstOrNull() ?: break
                fetchedUrls.add(url)
                logger.info("{} — iteration {}: fetching {}", name, iteration, url)

                val conversionResult = webScraper.fetchAndExtract(url)
                if (conversionResult == null) {
                    logger.warn("{} — fetch failed for {}", name, url)
                    continue
                }

                val prompt = buildPhase3cPrompt(
                    input.committeeName,
                    input.meetingDate,
                    input.identifiedItems,
                    conversionResult.text,
                    fetchReason,
                    accumulatedItems.values,
                )
                logger.trace("LLM Prompt {}", prompt.user)
                val rawResponse = llmClient.generate(prompt.system, prompt.user, lightModel)
                logger.debug("LLM response {}", rawResponse)
                val response = parseResponse(rawResponse)
                    ?.resolveUrls(conversionResult.urlRegistry::resolve) ?: return null

                when (response) {
                    is LlmResponse.AgendaTriaged -> {
                        response.items.associateByTo(accumulatedItems) { it.title }
                        if (urlQueue.isEmpty()) {
                            return response.copy(items = accumulatedItems.values)
                        }
                        logger.info(
                            "{} — LLM returned agenda_triaged but {} URL(s) remain in queue, continuing",
                            name, urlQueue.size,
                        )
                    }
                    is LlmResponse.AgendaFetch -> {
                        response.items.associateByTo(accumulatedItems) { it.title }
                        fetchReason = response.reason
                        urlQueue.addAll(response.urls)
                        logger.info(
                            "{} — LLM requests {} more URL(s): {}. Items so far: {}",
                            name, response.urls.size, response.reason, accumulatedItems.size,
                        )
                    }
                    else -> {
                        logger.warn("{} — unexpected response type: {}", name, response::class.simpleName)
                        return null
                    }
                }
            }

            if (accumulatedItems.isNotEmpty()) {
                logger.warn(
                    "{} — max iterations ({}) reached, returning {} accumulated items",
                    name, maxIterations, accumulatedItems.size,
                )
                return LlmResponse.AgendaTriaged(relevant = true, items = accumulatedItems.values)
            }

            logger.warn("{} — max iterations ({}) reached with no results", name, maxIterations)
            return null
        } finally {
            fetchedUrls.forEach { webScraper.releaseDocument(it) }
        }
    }
}
