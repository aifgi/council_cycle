package orchestrator

import config.CouncilConfig
import kotlinx.serialization.json.Json
import llm.LlmClient
import org.slf4j.LoggerFactory
import scraper.WebScraper

private val logger = LoggerFactory.getLogger(Orchestrator::class.java)

class Orchestrator(
    private val webScraper: WebScraper,
    private val llmClient: LlmClient,
    private val model: String = DEFAULT_MODEL,
    private val maxIterations: Int = DEFAULT_MAX_ITERATIONS,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun processCouncil(council: CouncilConfig) {
        for (committee in council.committees) {
            logger.info("Processing committee '{}' for council '{}'", committee, council.name)
            val result = findCommitteeInfo(council.name, council.siteUrl, committee)
            if (result != null) {
                logger.info("Found info for '{}' at '{}': {}", committee, council.name, result)
            } else {
                logger.warn("Could not find info for '{}' at '{}'", committee, council.name)
            }
        }
    }

    internal suspend fun findCommitteeInfo(
        councilName: String,
        startUrl: String,
        committeeName: String,
    ): LlmResponse.Found? {
        var urls = listOf(startUrl)

        for (iteration in 1..maxIterations) {
            logger.info("Iteration {} for '{}': fetching {} URL(s)", iteration, committeeName, urls.size)

            val pageContents = urls.mapNotNull { url ->
                val content = webScraper.fetchAndExtract(url)
                if (content != null) url to content else null
            }

            if (pageContents.isEmpty()) {
                logger.warn("All fetches failed for '{}' on iteration {}", committeeName, iteration)
                return null
            }

            val prompt = buildPrompt(councilName, committeeName, pageContents)
            val rawResponse = llmClient.generate(prompt, model)
            val response = parseResponse(rawResponse) ?: return null

            when (response) {
                is LlmResponse.Found -> return response
                is LlmResponse.Fetch -> {
                    logger.info("LLM requests fetching {} more URL(s): {}", response.urls.size, response.reason)
                    urls = response.urls
                }
            }
        }

        logger.warn("Max iterations ({}) reached for '{}'", maxIterations, committeeName)
        return null
    }

    private fun buildPrompt(
        councilName: String,
        committeeName: String,
        pageContents: List<Pair<String, String>>,
    ): String {
        val pagesSection = pageContents.joinToString("\n\n") { (url, content) ->
            "--- Page: $url ---\n$content"
        }

        return """
You are helping find information about a council committee.

Council: $councilName
Committee: $committeeName

Below are the contents of one or more web pages from this council's website. Your job is to either:
1. Find the next meeting date and details for this committee, OR
2. Identify links on the page that are likely to lead to the committee information.

$pagesSection

Respond with a single JSON object (no other text). The JSON must have a "type" field that is either "fetch" or "found".

If you need to follow links to find the information, respond with:
{
  "type": "fetch",
  "urls": ["https://..."],
  "reason": "Brief explanation of why you want to fetch these URLs"
}

Only include URLs that appeared as links in the page content above. Choose the most relevant 1-3 links.

If you found the committee meeting information, respond with:
{
  "type": "found",
  "committeeName": "The exact committee name",
  "nextMeetingDate": "YYYY-MM-DD or null if not found",
  "nextMeetingTime": "HH:MM or null if not found",
  "nextMeetingLocation": "Location or null if not found",
  "agendaUrl": "URL to agenda or null if not found",
  "summary": "Brief human-readable summary of what you found"
}

If the page content is completely unrelated and has no useful links, respond with:
{
  "type": "found",
  "committeeName": "$committeeName",
  "nextMeetingDate": null,
  "nextMeetingTime": null,
  "nextMeetingLocation": null,
  "agendaUrl": null,
  "summary": "Could not find any relevant information or links."
}
""".trimIndent()
    }

    internal fun parseResponse(raw: String): LlmResponse? {
        val jsonString = raw
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```")
            .trim()
        return try {
            json.decodeFromString<LlmResponse>(jsonString)
        } catch (e: Exception) {
            logger.error("Failed to parse LLM response: {}", e.message)
            logger.debug("Raw LLM response:\n{}", raw)
            null
        }
    }

    companion object {
        const val DEFAULT_MODEL = "claude-sonnet-4-5-20250929"
        const val DEFAULT_MAX_ITERATIONS = 5
    }
}
