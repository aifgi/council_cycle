package orchestrator

import llm.LlmClient
import org.slf4j.LoggerFactory
import scraper.WebScraper

private val logger = LoggerFactory.getLogger(AnalyzeExtractPhase::class.java)

data class AnalyzeExtractInput(
    val extract: String,
    val committeeName: String,
    val meeting: Meeting,
)

class AnalyzeExtractPhase(
    webScraper: WebScraper,
    llmClient: LlmClient,
    private val heavyModel: String = Orchestrator.DEFAULT_HEAVY_MODEL,
) : BasePhase(webScraper, llmClient), Phase<AnalyzeExtractInput, List<Scheme>> {

    override val name = "Phase 4: Analyze extract"

    override suspend fun execute(input: AnalyzeExtractInput): List<Scheme>? {
        logger.info("{} for meeting '{}' on {}", name, input.meeting.title, input.meeting.date)
        val prompt = buildPhase4Prompt(input.extract)
        val rawResponse = llmClient.generate(prompt.system, prompt.user, heavyModel)
        val response = parseResponse(rawResponse) ?: return null
        return (response as? PhaseResponse.AgendaAnalyzed)?.schemes?.map {
            it.copy(
                meetingDate = input.meeting.date,
                committeeName = input.committeeName,
                agendaUrl = input.meeting.meetingUrl ?: "",
            )
        }
    }
}
