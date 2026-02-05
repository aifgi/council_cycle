package uk.co.councilcycle.pipeline

import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import uk.co.councilcycle.config.AppConfig
import uk.co.councilcycle.config.CouncilRegistry
import java.time.Duration
import java.time.LocalTime

class Scheduler(
    private val pipeline: CouncilPipeline,
    private val config: AppConfig,
) {

    private val logger = LoggerFactory.getLogger(Scheduler::class.java)

    suspend fun runOnce() {
        logger.info("Running single pipeline execution")
        val councils = CouncilRegistry.loadCouncils()
        pipeline.processAllCouncils(councils)
    }

    suspend fun runDaily() {
        logger.info("Starting daily scheduler, run time: {}", config.pipeline.dailyRunTime)

        while (true) {
            val now = LocalTime.now()
            val targetTime = LocalTime.parse(config.pipeline.dailyRunTime)

            val delayUntilRun = if (now.isBefore(targetTime)) {
                Duration.between(now, targetTime)
            } else {
                // Already past today's run time; schedule for tomorrow
                Duration.between(now, targetTime).plusHours(24)
            }

            logger.info("Next run in {}", delayUntilRun)
            delay(delayUntilRun.toMillis())

            try {
                runOnce()
            } catch (e: Exception) {
                logger.error("Pipeline run failed: {}", e.message, e)
            }
        }
    }
}
