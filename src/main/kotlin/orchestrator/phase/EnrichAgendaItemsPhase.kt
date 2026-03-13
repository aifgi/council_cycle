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
        // Each entry: (url, targetItems) — targetItems is null for the initial meeting URL
        val urlQueue = mutableListOf<Pair<String, List<IdentifiedAgendaItem>?>>(
            Pair(input.meetingUrl, null),
        )
        val processedOrQueuedUrls = mutableSetOf(input.meetingUrl)
        val summariesByItem = mutableMapOf<String, MutableList<String>>()

        try {
            for (iteration in 1..maxIterations) {
                val (url, fetchedFor) = urlQueue.removeFirstOrNull() ?: break
                logger.info("{} — iteration {}: fetching {}", name, iteration, url)

                val conversionResult = webScraper.fetchAndExtract(url)
                if (conversionResult == null) {
                    logger.warn("{} — fetch failed for {}", name, url)
                    continue
                }

                val targetItems = fetchedFor ?: input.identifiedItems
                val prompt = buildEnrichAgendaItemsPrompt(
                    input.committeeName,
                    input.meetingDate,
                    targetItems,
                    conversionResult.text,
                    fetchedFor,
                )
                logger.trace("LLM Prompt {}", prompt.user)
                val rawResponse = llmClient.generate(prompt.system, prompt.user, lightModel)
                logger.debug("LLM response {}", rawResponse)
                val response = parseResponse(rawResponse)
                    ?.resolveUrls(conversionResult.urlRegistry::resolve) ?: return null

                when (response) {
                    is LlmResponse.AgendaItemsEnriched -> {
                        val newFetchesByUrl = mutableMapOf<String, MutableList<IdentifiedAgendaItem>>()
                        for (item in response.items) {
                            when (item) {
                                is EnrichedItem.Summary -> {
                                    summariesByItem.getOrPut(item.title) { mutableListOf() }.add(item.extract)
                                }
                                is EnrichedItem.Fetch -> {
                                    val requestingItem = targetItems.find { it.title == item.title }
                                    val itemsForUrls = if (requestingItem != null) listOf(requestingItem) else targetItems
                                    for (newUrl in item.urls.filterNot { it in processedOrQueuedUrls }) {
                                        newFetchesByUrl.getOrPut(newUrl) { mutableListOf() }.addAll(itemsForUrls)
                                    }
                                    if (item.urls.isNotEmpty()) {
                                        logger.info(
                                            "{} — item '{}' requests {} URL(s): {}",
                                            name, item.title, item.urls.size, item.reason,
                                        )
                                    }
                                }
                            }
                        }
                        for ((newUrl, items) in newFetchesByUrl) {
                            processedOrQueuedUrls.add(newUrl)
                            urlQueue.add(Pair(newUrl, items.distinctBy { it.title }))
                        }
                    }
                    else -> {
                        logger.warn("{} — unexpected response type: {}", name, response::class.simpleName)
                        return null
                    }
                }
            }

            if (summariesByItem.isEmpty()) {
                logger.warn("{} — no items summarized", name)
                return null
            }

            val completedItems = summariesByItem.map { (title, summaries) ->
                TriagedItem(title, summaries.joinToString("\n\n---\n\n"))
            }

            val summarizedTitles = summariesByItem.keys
            val unsummarized = input.identifiedItems.filter { it.title !in summarizedTitles }
            if (unsummarized.isNotEmpty()) {
                logger.warn(
                    "{} — returning {} item(s); {} item(s) not summarized: {}",
                    name, completedItems.size, unsummarized.size, unsummarized.map { it.title },
                )
            }

            return LlmResponse.AgendaTriaged(relevant = true, items = completedItems)
        } finally {
            processedOrQueuedUrls.forEach { webScraper.releaseDocument(it) }
        }
    }
}
