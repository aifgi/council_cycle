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
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun processCouncil(council: CouncilConfig) {
        val dateFrom = council.dateFrom ?: LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        val dateTo = council.dateTo
            ?: LocalDate.now().plusMonths(3).format(DateTimeFormatter.ISO_LOCAL_DATE)

        for (committee in council.committees) {
            logger.info("Processing committee '{}' for council '{}'", committee, council.name)

            val committeeUrl = findCommitteePage(council.siteUrl, council.name, committee)
            if (committeeUrl == null) {
                logger.warn("Could not find page for committee '{}' at '{}'", committee, council.name)
                continue
            }
            logger.info("Found committee page for '{}': {}", committee, committeeUrl)

            val meetings = findMeetings(committeeUrl, council.name, committee, dateFrom, dateTo)
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
                val triage = triageAgenda(meeting.agendaUrl, council.name, committee, meeting)
                if (triage == null || !triage.relevant) {
                    logger.info("Agenda not relevant for meeting '{}' on {}", meeting.title, meeting.date)
                    continue
                }
                val schemes = analyzeExtract(triage.extract!!, council.name, committee, meeting)
                if (schemes != null) {
                    allSchemes.addAll(schemes)
                }
            }

            resultProcessor.process(council.name, committee, allSchemes)
        }
    }

    internal suspend fun findCommitteePage(
        startUrl: String,
        councilName: String,
        committeeName: String,
    ): String? {
        return navigationLoop(
            startUrl = startUrl,
            phaseName = "Phase 1: Find committee page",
            model = lightModel,
            buildPrompt = { url, content -> buildPhase1Prompt(councilName, committeeName, url, content) },
            extractResult = { response -> (response as? PhaseResponse.CommitteePageFound)?.url },
        )
    }

    internal suspend fun findMeetings(
        committeeUrl: String,
        councilName: String,
        committeeName: String,
        dateFrom: String,
        dateTo: String,
    ): List<Meeting>? {
        return navigationLoop(
            startUrl = committeeUrl,
            phaseName = "Phase 2: Find meetings",
            model = lightModel,
            buildPrompt = { url, content ->
                buildPhase2Prompt(councilName, committeeName, dateFrom, dateTo, url, content)
            },
            extractResult = { response -> (response as? PhaseResponse.MeetingsFound)?.meetings },
        )
    }

    internal suspend fun triageAgenda(
        agendaUrl: String,
        councilName: String,
        committeeName: String,
        meeting: Meeting,
    ): PhaseResponse.AgendaTriaged? {
        return navigationLoop(
            startUrl = agendaUrl,
            phaseName = "Phase 3: Triage agenda",
            model = lightModel,
            buildPrompt = { url, content ->
                buildPhase3Prompt(councilName, committeeName, meeting, url, content)
            },
            extractResult = { response -> response as? PhaseResponse.AgendaTriaged },
        )
    }

    internal suspend fun analyzeExtract(
        extract: String,
        councilName: String,
        committeeName: String,
        meeting: Meeting,
    ): List<Scheme>? {
        logger.info("Phase 4: Analyzing extract for meeting '{}' on {}", meeting.title, meeting.date)
        val prompt = buildPhase4Prompt(councilName, committeeName, meeting, extract)
        val rawResponse = llmClient.generate(prompt, heavyModel)
        val response = parseResponse(rawResponse) ?: return null
        return (response as? PhaseResponse.AgendaAnalyzed)?.schemes
    }

    private suspend fun <R> navigationLoop(
        startUrl: String,
        phaseName: String,
        model: String,
        buildPrompt: (String, String) -> String,
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

            val prompt = buildPrompt(url, conversionResult.text)
            logger.trace("LLM Prompt {}", prompt)
            val rawResponse = llmClient.generate(prompt, model)
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

    private fun buildPhase1Prompt(
        councilName: String,
        committeeName: String,
        pageUrl: String,
        pageContent: String,
    ): String {
        return """
You are helping find a council committee's page on their website.

Council: $councilName
Committee: $committeeName

Below are the contents of a web page from this council's website. Your job is to either:
1. Identify the URL of the committee's dedicated page, OR
2. Identify links that are likely to lead to the committee's page.

URLs are represented as short references like @1, @2. Use these references when specifying URLs in your response.

--- Page: $pageUrl ---
$pageContent

Respond with a single JSON object (no other text). The JSON must have a "type" field.

If you need to follow links to find the committee page, respond with:
{
  "type": "fetch",
  "urls": ["@1"],
  "reason": "Brief explanation of why you want to fetch these URLs"
}

Only include URLs that appeared as links in the page content above. Choose the most relevant 1-3 links.

If you found the committee's page URL, respond with:
{
  "type": "committee_page_found",
  "url": "@1"
}
""".trimIndent()
    }

    private fun buildPhase2Prompt(
        councilName: String,
        committeeName: String,
        dateFrom: String,
        dateTo: String,
        pageUrl: String,
        pageContent: String,
    ): String {
        return """
You are helping find committee meeting agendas.

Council: $councilName
Committee: $committeeName
Date range: $dateFrom to $dateTo

Below are the contents of a web page. Your job is to either:
1. Find meetings within the date range that have agenda documents/pages, OR
2. Identify links that are likely to lead to meeting listings or agendas.

URLs are represented as short references like @1, @2. Use these references when specifying URLs in your response.

--- Page: $pageUrl ---
$pageContent

Respond with a single JSON object (no other text). The JSON must have a "type" field.

If you need to follow links, respond with:
{
  "type": "fetch",
  "urls": ["@1"],
  "reason": "Brief explanation"
}

Only include URLs that appeared as links in the page content above. Choose the most relevant 1-3 links.

If you found meetings, respond with:
{
  "type": "meetings_found",
  "meetings": [
    {
      "date": "YYYY-MM-DD",
      "title": "Meeting title",
      "agendaUrl": "@1 or null if no agenda link found"
    }
  ]
}

Only include meetings within the date range $dateFrom to $dateTo.
""".trimIndent()
    }

    private fun buildPhase3Prompt(
        councilName: String,
        committeeName: String,
        meeting: Meeting,
        pageUrl: String,
        pageContent: String,
    ): String {
        val topicsList = TOPICS.joinToString(", ")

        return """
You are triaging a council committee meeting agenda to check if it contains items related to transport and planning schemes.

Council: $councilName
Committee: $committeeName
Meeting date: ${meeting.date}
Meeting title: ${meeting.title}

Topics of interest: $topicsList

URLs are represented as short references like @1, @2. Use these references when specifying URLs in your response.

--- Page: $pageUrl ---
$pageContent

Respond with a single JSON object (no other text). The JSON must have a "type" field.

If you need to follow links to read the full agenda or individual agenda items, respond with:
{
  "type": "fetch",
  "urls": ["@1"],
  "reason": "Brief explanation"
}

Only include URLs that appeared as links in the page content above. Choose the most relevant 1-3 links. Do not follow links to PDF documents if the agenda content is already available on the page.

Once you have seen enough of the agenda, determine whether it contains any items related to the topics listed above.

If the agenda contains relevant items, extract just the relevant portions verbatim and respond with:
{
  "type": "agenda_triaged",
  "relevant": true,
  "extract": "The relevant text extracted from the agenda"
}

Exception: if the page contains meeting minutes (rather than a forward-looking agenda), return a summary focusing on the question raised and the decision made, rather than verbatim text.

If no relevant items are found, respond with:
{
  "type": "agenda_triaged",
  "relevant": false
}
""".trimIndent()
    }

    private fun buildPhase4Prompt(
        councilName: String,
        committeeName: String,
        meeting: Meeting,
        extract: String,
    ): String {
        val topicsList = TOPICS.joinToString(", ")

        return """
You are analyzing pre-extracted content from a council committee meeting agenda for transport and planning schemes.

Council: $councilName
Committee: $committeeName
Meeting date: ${meeting.date}
Meeting title: ${meeting.title}

Topics of interest: $topicsList

--- Extracted content ---
$extract

Respond with a single JSON object (no other text). The JSON must have a "type" field.

Analyze the content above and identify any schemes or items related to the topics listed.

{
  "type": "agenda_analyzed",
  "schemes": [
    {
      "title": "Name of the scheme or agenda item",
      "topic": "Which topic it relates to (one of: $topicsList)",
      "summary": "Brief summary of what is proposed or discussed",
      "meetingDate": "${meeting.date}",
      "committeeName": "$committeeName"
    }
  ]
}

If no relevant items are found, return an empty schemes array: {"type": "agenda_analyzed", "schemes": []}
""".trimIndent()
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
        val TOPICS = listOf(
            "cycle lanes",
            "traffic filters",
            "LTN/low traffic neighbourhoods",
            "public realm improvements",
        )
    }
}
