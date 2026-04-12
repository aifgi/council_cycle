package orchestrator.phase

import kotlinx.serialization.json.Json
import llm.LlmClient
import orchestrator.LlmResponse
import orchestrator.SplitPrompt
import orchestrator.resolveUrls
import org.slf4j.LoggerFactory
import scraper.ConversionResult
import scraper.WebScraper

const val DEFAULT_LIGHT_MODEL = "claude-sonnet-4-6"
const val DEFAULT_HEAVY_MODEL = "claude-opus-4-6"
const val DEFAULT_MAX_ITERATIONS = 5
const val DEFAULT_TRIAGE_MAX_ITERATIONS = 5

interface Phase<in I, out O> {
    val name: String
    suspend fun execute(input: I): O?
}

abstract class BasePhase<I, O>(
    private val webScraper: WebScraper,
    protected val llmClient: LlmClient,
) : Phase<I, O> {
    protected val logger = LoggerFactory.getLogger(this::class.java)
    protected val json = Json { ignoreUnknownKeys = true }

    private val trackedUrls = mutableSetOf<String>()

    protected suspend fun fetchAndExtract(url: String): ConversionResult? {
        trackedUrls.add(url)
        return webScraper.fetchAndExtract(url)
    }

    final override suspend fun execute(input: I): O? {
        try {
            return doExecute(input)
        } finally {
            trackedUrls.forEach { webScraper.releaseDocument(it) }
            trackedUrls.clear()
        }
    }

    protected abstract suspend fun doExecute(input: I): O?

    protected fun parseResponse(raw: String): LlmResponse? {
        val jsonString = raw
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```")
            .trim()
        return try {
            json.decodeFromString<LlmResponse>(jsonString)
        } catch (e: Exception) {
            logger.error("Failed to parse LLM response: {}", e.message)
            logger.error("Raw LLM response:\n{}", raw)
            null
        }
    }

    protected suspend fun <R> navigationLoop(
        startUrl: String,
        phaseName: String,
        model: String,
        maxIterations: Int,
        buildPrompt: (String) -> SplitPrompt,
        extractResult: (LlmResponse) -> R?,
    ): R? {
        val urlQueue = mutableListOf(startUrl)

        for (iteration in 1..maxIterations) {
            val url = urlQueue.removeFirstOrNull() ?: break
            logger.info("{} — iteration {}: fetching {}", phaseName, iteration, url)

            val conversionResult = fetchAndExtract(url)
            if (conversionResult == null) {
                logger.warn("{} — fetch failed for {}", phaseName, url)
                continue
            }

            val prompt = buildPrompt(conversionResult.text)
            logger.trace("LLM Prompt {}", prompt.user)
            val rawResponse = llmClient.generate(prompt.system, prompt.user, model)
            logger.debug("LLM response {}", rawResponse)
            val response = parseResponse(rawResponse)
                ?.resolveUrls(conversionResult.urlRegistry::resolve) ?: return null

            val result = extractResult(response)
            if (result != null) return result

            when (response) {
                is LlmResponse.Fetch -> {
                    logger.info("{} — LLM requests {} more URL(s): {}", phaseName, response.urls.size, response.reason)
                    urlQueue.addAll(response.urls)
                }
                else -> {
                    logger.warn("{} — unexpected response type: {}", phaseName, response::class.simpleName)
                    return null
                }
            }
        }

        logger.warn("{} — max iterations ({}) reached", phaseName, maxIterations)
        return null
    }
}
