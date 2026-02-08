package orchestrator

import config.CouncilConfig
import orchestrator.phase.AnalyzeExtractInput
import orchestrator.phase.AnalyzeExtractPhase
import orchestrator.phase.FindCommitteePagesInput
import orchestrator.phase.FindCommitteePagesPhase
import orchestrator.phase.FindMeetingsInput
import orchestrator.phase.FindMeetingsPhase
import orchestrator.phase.TriageAgendaInput
import orchestrator.phase.TriageAgendaPhase
import org.slf4j.LoggerFactory
import processor.ResultProcessor
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val logger = LoggerFactory.getLogger(Orchestrator::class.java)

class Orchestrator(
    private val findCommitteePagesPhase: FindCommitteePagesPhase,
    private val findMeetingsPhase: FindMeetingsPhase,
    private val triageAgendaPhase: TriageAgendaPhase,
    private val analyzeExtractPhase: AnalyzeExtractPhase,
    private val resultProcessor: ResultProcessor,
) {
    suspend fun processCouncil(council: CouncilConfig) {
        val dateFrom = council.dateFrom ?: LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        val dateTo = council.dateTo
            ?: LocalDate.now().plusMonths(3).format(DateTimeFormatter.ISO_LOCAL_DATE)

        val committeeUrls = findCommitteePagesPhase.execute(
            FindCommitteePagesInput(council.siteUrl, council.committees)
        )
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

            val meetings = findMeetingsPhase.execute(
                FindMeetingsInput(committeeUrl, committee, dateFrom, dateTo)
            )
            if (meetings == null || meetings.isEmpty()) {
                logger.warn("No meetings found for '{}' at '{}'", committee, council.name)
                continue
            }
            logger.info("Found {} meeting(s) for '{}'", meetings.size, committee)

            val allSchemes = mutableListOf<Scheme>()
            for (meeting in meetings) {
                if (meeting.meetingUrl == null) {
                    logger.info("No agenda URL for meeting '{}' on {}", meeting.title, meeting.date)
                    continue
                }
                val triage = triageAgendaPhase.execute(
                    TriageAgendaInput(meeting.meetingUrl, committee, meeting.date)
                )
                if (triage == null || !triage.relevant || triage.items.isEmpty()) {
                    logger.info("Agenda not relevant for meeting '{}' on {}", meeting.title, meeting.date)
                    continue
                }
                val extract = triage.items.joinToString("\n\n") { "## ${it.title}\n${it.extract}" }
                val schemes = analyzeExtractPhase.execute(
                    AnalyzeExtractInput(extract, committee, meeting)
                )
                if (schemes != null) {
                    allSchemes.addAll(schemes)
                }
            }

            resultProcessor.process(council.name, committee, allSchemes)
        }
    }
}
