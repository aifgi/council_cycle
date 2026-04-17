package orchestrator

import config.CouncilConfig
import orchestrator.phase.AnalyzeExtractInput
import orchestrator.phase.AnalyzeExtractPhase
import orchestrator.phase.EnrichAgendaItemsInput
import orchestrator.phase.EnrichAgendaItemsPhase
import orchestrator.phase.EnrichDecisionInput
import orchestrator.phase.EnrichDecisionPhase
import orchestrator.phase.FindAgendaInput
import orchestrator.phase.FindAgendaPhase
import orchestrator.phase.FindCommitteePagesInput
import orchestrator.phase.FindCommitteePagesPhase
import orchestrator.phase.FindDecisionsInput
import orchestrator.phase.FindDecisionsPhase
import orchestrator.phase.FindMeetingsInput
import orchestrator.phase.FindMeetingsPhase
import orchestrator.phase.IdentifyAgendaItemsInput
import orchestrator.phase.IdentifyAgendaItemsPhase
import org.slf4j.LoggerFactory
import processor.ResultProcessor
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val logger = LoggerFactory.getLogger(Orchestrator::class.java)

class Orchestrator(
    private val findCommitteePagesPhase: FindCommitteePagesPhase,
    private val findMeetingsPhase: FindMeetingsPhase,
    private val findAgendaPhase: FindAgendaPhase,
    private val identifyAgendaItemsPhase: IdentifyAgendaItemsPhase,
    private val enrichAgendaItemsPhase: EnrichAgendaItemsPhase,
    private val analyzeExtractPhase: AnalyzeExtractPhase,
    private val findDecisionsPhase: FindDecisionsPhase,
    private val enrichDecisionPhase: EnrichDecisionPhase,
    private val resultProcessor: ResultProcessor,
) {
    suspend fun processCouncil(council: CouncilConfig) {
        if (council.mode == "decisions") {
            processCouncilDecisions(council)
        } else {
            processCouncilMeetings(council)
        }
    }

    private suspend fun processCouncilMeetings(council: CouncilConfig) {
        val dateFrom = council.dateFrom ?: LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        val dateTo = council.dateTo
            ?: LocalDate.now().plusMonths(3).format(DateTimeFormatter.ISO_LOCAL_DATE)

        val committeeUrls = findCommitteePagesPhase.execute(
            FindCommitteePagesInput(council.meetingsUrl!!, council.committees)
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

                val agendaUrl = findAgendaPhase.execute(FindAgendaInput(meeting.meetingUrl))
                if (agendaUrl == null) {
                    logger.info("Could not find agenda for meeting '{}' on {}", meeting.title, meeting.date)
                    continue
                }

                val identifiedItems = identifyAgendaItemsPhase.execute(
                    IdentifyAgendaItemsInput(agendaUrl, committee, meeting.date)
                )
                if (identifiedItems == null) {
                    logger.info("Could not identify items for meeting '{}' on {}", meeting.title, meeting.date)
                    continue
                }
                if (identifiedItems.isEmpty()) {
                    logger.info("No relevant items for meeting '{}' on {}", meeting.title, meeting.date)
                    continue
                }

                val triage = enrichAgendaItemsPhase.execute(
                    EnrichAgendaItemsInput(meeting.meetingUrl, identifiedItems, committee, meeting.date)
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

    private suspend fun processCouncilDecisions(council: CouncilConfig) {
        val dateFrom = council.dateFrom ?: LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        val dateTo = council.dateTo
            ?: LocalDate.now().plusMonths(3).format(DateTimeFormatter.ISO_LOCAL_DATE)

        val decisions = findDecisionsPhase.execute(
            FindDecisionsInput(
                decisionsUrl = council.decisionsUrl!!,
                decisionMakers = council.decisionMakers,
                dateFrom = dateFrom,
                dateTo = dateTo,
            )
        )
        if (decisions == null) {
            logger.warn("Find decisions phase failed for council '{}'", council.name)
            return
        }
        if (decisions.isEmpty()) {
            logger.info("No matching decisions found for council '{}'", council.name)
            return
        }
        logger.info("Found {} matching decision(s) for council '{}'", decisions.size, council.name)

        val allSchemes = mutableListOf<Scheme>()
        for (decision in decisions) {
            logger.info("Enriching decision '{}' ({})", decision.title, decision.detailUrl)

            val enriched = enrichDecisionPhase.execute(EnrichDecisionInput(decision))
            if (enriched == null) {
                logger.warn("Enrich decision phase failed for '{}', skipping", decision.title)
                continue
            }

            val triaged = enriched.item
            val extract = "## ${triaged.title}\n${triaged.extract}"
            val decisionMakerLabel = enriched.decisionMaker
                ?: decision.decisionMaker
                ?: council.decisionMakers.firstOrNull()
                ?: council.name
            val syntheticMeeting = Meeting(
                date = decision.decisionDate,
                title = decision.title,
                meetingUrl = decision.detailUrl,
            )

            val schemes = analyzeExtractPhase.execute(
                AnalyzeExtractInput(extract, decisionMakerLabel, syntheticMeeting)
            )
            if (schemes != null) {
                allSchemes.addAll(schemes)
            }
        }

        val outputLabel = council.decisionMakers.joinToString(", ")
        resultProcessor.process(council.name, outputLabel, allSchemes)
    }
}
