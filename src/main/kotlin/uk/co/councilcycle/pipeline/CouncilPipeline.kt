package uk.co.councilcycle.pipeline

import org.slf4j.LoggerFactory
import uk.co.councilcycle.config.AppConfig
import uk.co.councilcycle.llm.LlmAnalyzer
import uk.co.councilcycle.model.Council
import uk.co.councilcycle.model.Decision
import uk.co.councilcycle.publisher.BlueskyPublisher
import uk.co.councilcycle.scraper.WebScraper

class CouncilPipeline(
    private val scraper: WebScraper,
    private val analyzer: LlmAnalyzer,
    private val publisher: BlueskyPublisher,
    private val config: AppConfig,
) {

    private val logger = LoggerFactory.getLogger(CouncilPipeline::class.java)

    suspend fun processCouncil(council: Council): List<Decision> {
        logger.info("Processing council: {}", council.name)

        val allDecisions = mutableListOf<Decision>()
        val visited = mutableSetOf<String>()
        val toVisit = ArrayDeque<String>()

        // Start with the meetings page
        toVisit.add(council.meetingsUrl)

        var depth = 0
        while (toVisit.isNotEmpty() && depth < config.pipeline.maxFollowUpDepth) {
            val url = toVisit.removeFirst()
            if (url in visited) continue
            visited.add(url)

            try {
                val pageContent = scraper.fetchPage(url)
                val result = analyzer.analyzePage(council, pageContent, url)

                allDecisions.addAll(result.decisions)

                // Queue follow-up URLs suggested by the LLM
                result.followUpUrls
                    .filter { it !in visited }
                    .forEach { toVisit.add(it) }

                depth++
            } catch (e: Exception) {
                logger.error("Error processing {} for {}: {}", url, council.name, e.message)
            }
        }

        logger.info("Found {} relevant decisions for {}", allDecisions.size, council.name)
        return allDecisions
    }

    suspend fun processAllCouncils(councils: List<Council>) {
        logger.info("Starting pipeline for {} councils", councils.size)

        for (council in councils) {
            try {
                val decisions = processCouncil(council)

                if (decisions.isNotEmpty()) {
                    publisher.publishAll(decisions)
                }
            } catch (e: Exception) {
                logger.error("Failed to process council {}: {}", council.name, e.message)
            }
        }

        logger.info("Pipeline completed")
    }
}
