package processor

import orchestrator.Scheme
import org.slf4j.LoggerFactory

class LoggingResultProcessor : ResultProcessor {

    private val logger = LoggerFactory.getLogger(LoggingResultProcessor::class.java)

    override fun process(councilName: String, committeeName: String, schemes: List<Scheme>) {
        if (schemes.isEmpty()) {
            logger.info("No relevant schemes found for '{}' at '{}'", committeeName, councilName)
            return
        }

        logger.info("Found {} scheme(s) for '{}' at '{}':", schemes.size, committeeName, councilName)
        for (scheme in schemes) {
            logger.info(
                "  [{}] {} - {} (meeting: {})",
                scheme.topic, scheme.title, scheme.summary, scheme.meetingDate,
            )
        }
    }
}
