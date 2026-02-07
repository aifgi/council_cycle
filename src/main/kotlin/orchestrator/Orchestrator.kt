package orchestrator

import config.CouncilConfig
import kotlinx.serialization.json.Json
import llm.LlmClient
import org.slf4j.LoggerFactory
import processor.ResultProcessor
import scraper.WebScraper
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val logger = LoggerFactory.getLogger(Orchestrator::class.java)

class Orchestrator(
    private val webScraper: WebScraper,
    private val llmClient: LlmClient,
    private val resultProcessor: ResultProcessor,
    private val lightModel: String = DEFAULT_LIGHT_MODEL,
    private val heavyModel: String = DEFAULT_HEAVY_MODEL,
    private val maxIterations: Int = DEFAULT_MAX_ITERATIONS,
    private val maxPhase3Iterations: Int = DEFAULT_MAX_PHASE3_ITERATIONS,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun processCouncil(council: CouncilConfig) {
        val dateFrom = council.dateFrom ?: LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        val dateTo = council.dateTo
            ?: LocalDate.now().plusMonths(3).format(DateTimeFormatter.ISO_LOCAL_DATE)

        val committeeUrls = findCommitteePages(council.siteUrl, council.committees)
        if (committeeUrls == null) {
            logger.warn("Could not find committee pages for council '{}'", council.name)
            return
        }

        for (committee in council.committees) {
            logger.info("Processing committee '{}' for council '{}'", committee, council.name)

            val committeeUrl = committeeUrls[committee]
            if (committeeUrl == null) {
                logger.warn("Could not find page for committee '{}' at '{}'", committee, council.name)
                continue
            }
            logger.info("Found committee page for '{}': {}", committee, committeeUrl)

            val meetings = findMeetings(committeeUrl, committee, dateFrom, dateTo)
            if (meetings == null || meetings.isEmpty()) {
                logger.warn("No meetings found for '{}' at '{}'", committee, council.name)
                continue
            }
            logger.info("Found {} meeting(s) for '{}'", meetings.size, committee)

            val allSchemes = mutableListOf<Scheme>()
            for (meeting in meetings) {
                if (meeting.agendaUrl == null) {
                    logger.info("No agenda URL for meeting '{}' on {}", meeting.title, meeting.date)
                    continue
                }
                val triage = triageAgenda(meeting.agendaUrl)
                if (triage == null || !triage.relevant || triage.items.isEmpty()) {
                    logger.info("Agenda not relevant for meeting '{}' on {}", meeting.title, meeting.date)
                    continue
                }
                val extract = triage.items.joinToString("\n\n") { "## ${it.title}\n${it.extract}" }
                val schemes = analyzeExtract(extract, committee, meeting)
                if (schemes != null) {
                    allSchemes.addAll(schemes)
                }
            }

            resultProcessor.process(council.name, committee, allSchemes)
        }
    }

    internal suspend fun findCommitteePages(
        startUrl: String,
        committeeNames: List<String>,
    ): Map<String, String>? {
        return navigationLoop(
            startUrl = startUrl,
            phaseName = "Phase 1: Find committee pages",
            model = lightModel,
            buildPrompt = { content -> buildPhase1Prompt(committeeNames, content) },
            extractResult = { response ->
                (response as? PhaseResponse.CommitteePagesFound)
                    ?.committees
                    ?.associate { it.name to it.url }
            },
        )
    }

    internal suspend fun findMeetings(
        committeeUrl: String,
        committeeName: String,
        dateFrom: String,
        dateTo: String,
    ): List<Meeting>? {
        return navigationLoop(
            startUrl = committeeUrl,
            phaseName = "Phase 2: Find meetings",
            model = lightModel,
            buildPrompt = { content ->
                buildPhase2Prompt(committeeName, dateFrom, dateTo, content)
            },
            extractResult = { response -> (response as? PhaseResponse.MeetingsFound)?.meetings },
        )
    }

    internal suspend fun triageAgenda(
        agendaUrl: String,
    ): PhaseResponse.AgendaTriaged? {
        val urlQueue = mutableListOf(agendaUrl)
        val accumulatedItems = mutableMapOf<String, TriagedItem>()
        var fetchReason: String? = null

        for (iteration in 1..maxPhase3Iterations) {
            val url = urlQueue.removeFirstOrNull() ?: break
            logger.info("Phase 3: Triage agenda — iteration {}: fetching {}", iteration, url)

            val conversionResult = webScraper.fetchAndExtract(url)
            if (conversionResult == null) {
                logger.warn("Phase 3: Triage agenda — fetch failed for {}", url)
                continue
            }

            val prompt = buildPhase3Prompt(agendaUrl, conversionResult.text, fetchReason, accumulatedItems.values)
            logger.trace("LLM Prompt {}", prompt.user)
            val rawResponse = llmClient.generate(prompt.system, prompt.user, lightModel)
            logger.debug("LLM response {}", rawResponse)
            val response = parseResponse(rawResponse)
                ?.resolveUrls(conversionResult.urlRegistry::resolve) ?: return null

            when (response) {
                is PhaseResponse.AgendaTriaged -> {
                    response.items.associateByTo(accumulatedItems) { it.title }
                    return response.copy(items = accumulatedItems.values)
                }
                is PhaseResponse.AgendaFetch -> {
                    response.items.associateByTo(accumulatedItems) { it.title }
                    fetchReason = response.reason
                    urlQueue.addAll(response.urls)
                    logger.info(
                        "Phase 3: Triage agenda — LLM requests {} more URL(s): {}. Items so far: {}",
                        response.urls.size, response.reason, accumulatedItems.size,
                    )
                }
                else -> {
                    logger.warn("Phase 3: Triage agenda — unexpected response type: {}", response::class.simpleName)
                    return null
                }
            }
        }

        if (accumulatedItems.isNotEmpty()) {
            logger.warn(
                "Phase 3: Triage agenda — max iterations ({}) reached, returning {} accumulated items",
                maxPhase3Iterations, accumulatedItems.size,
            )
            return PhaseResponse.AgendaTriaged(relevant = true, items = accumulatedItems.values)
        }

        logger.warn("Phase 3: Triage agenda — max iterations ({}) reached with no results", maxPhase3Iterations)
        return null
    }

    internal suspend fun analyzeExtract(
        extract: String,
        committeeName: String,
        meeting: Meeting,
    ): List<Scheme>? {
        logger.info("Phase 4: Analyzing extract for meeting '{}' on {}", meeting.title, meeting.date)
        val prompt = buildPhase4Prompt(extract)
        val rawResponse = llmClient.generate(prompt.system, prompt.user, heavyModel)
        val response = parseResponse(rawResponse) ?: return null
        return (response as? PhaseResponse.AgendaAnalyzed)?.schemes?.map {
            it.copy(meetingDate = meeting.date, committeeName = committeeName)
        }
    }

    private suspend fun <R> navigationLoop(
        startUrl: String,
        phaseName: String,
        model: String,
        buildPrompt: (String) -> SplitPrompt,
        extractResult: (PhaseResponse) -> R?,
    ): R? {
        val urlQueue = mutableListOf(startUrl)

        for (iteration in 1..maxIterations) {
            val url = urlQueue.removeFirstOrNull() ?: break
            logger.info("{} — iteration {}: fetching {}", phaseName, iteration, url)

            val conversionResult = webScraper.fetchAndExtract(url)
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
                is PhaseResponse.Fetch -> {
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

    internal fun parseResponse(raw: String): PhaseResponse? {
        val jsonString = raw
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```")
            .trim()
        return try {
            json.decodeFromString<PhaseResponse>(jsonString)
        } catch (e: Exception) {
            logger.error("Failed to parse LLM response: {}", e.message)
            logger.debug("Raw LLM response:\n{}", raw)
            null
        }
    }

    companion object {
        const val DEFAULT_LIGHT_MODEL = "claude-haiku-4-5-20251001"
        const val DEFAULT_HEAVY_MODEL = "claude-sonnet-4-5-20250929"
        const val DEFAULT_MAX_ITERATIONS = 5
        const val DEFAULT_MAX_PHASE3_ITERATIONS = 10
    }
}
