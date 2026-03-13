package orchestrator.phase

import llm.LlmClient
import orchestrator.EnrichedItem
import orchestrator.IdentifiedAgendaItem
import orchestrator.LlmResponse
import orchestrator.TriagedItem
import orchestrator.buildEnrichAgendaItemsPrompt
import orchestrator.resolveUrls
import scraper.WebScraper

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
        val completedItems = mutableMapOf<String, TriagedItem>()
        val pendingItems = input.identifiedItems.toMutableList()

        try {
            for (iteration in 1..maxIterations) {
                if (pendingItems.isEmpty()) break
                val url = urlQueue.removeFirstOrNull() ?: break
                fetchedUrls.add(url)
                logger.info("{} — iteration {}: fetching {}", name, iteration, url)

                val conversionResult = webScraper.fetchAndExtract(url)
                if (conversionResult == null) {
                    logger.warn("{} — fetch failed for {}", name, url)
                    continue
                }

                val prompt = buildEnrichAgendaItemsPrompt(
                    input.committeeName,
                    input.meetingDate,
                    pendingItems,
                    conversionResult.text,
                )
                logger.trace("LLM Prompt {}", prompt.user)
                val rawResponse = llmClient.generate(prompt.system, prompt.user, lightModel)
                logger.debug("LLM response {}", rawResponse)
                val response = parseResponse(rawResponse)
                    ?.resolveUrls(conversionResult.urlRegistry::resolve) ?: return null

                when (response) {
                    is LlmResponse.AgendaItemsEnriched -> {
                        for (item in response.items) {
                            when (item) {
                                is EnrichedItem.Summary -> {
                                    completedItems[item.title] = TriagedItem(item.title, item.extract)
                                    pendingItems.removeIf { it.title == item.title }
                                }
                                is EnrichedItem.Fetch -> {
                                    val newUrls = item.urls.filterNot { it in fetchedUrls }
                                    urlQueue.addAll(newUrls)
                                    logger.info(
                                        "{} — item '{}' requests {} URL(s): {}",
                                        name, item.title, newUrls.size, item.reason,
                                    )
                                }
                            }
                        }
                    }
                    else -> {
                        logger.warn("{} — unexpected response type: {}", name, response::class.simpleName)
                        return null
                    }
                }
            }

            if (completedItems.isEmpty()) {
                logger.warn("{} — no items summarized", name)
                return null
            }

            if (pendingItems.isNotEmpty()) {
                logger.warn(
                    "{} — returning {} item(s); {} item(s) not summarized: {}",
                    name, completedItems.size, pendingItems.size, pendingItems.map { it.title },
                )
            }

            return LlmResponse.AgendaTriaged(relevant = true, items = completedItems.values)
        } finally {
            fetchedUrls.forEach { webScraper.releaseDocument(it) }
        }
    }
}
