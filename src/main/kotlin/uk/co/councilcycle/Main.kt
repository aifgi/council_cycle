package uk.co.councilcycle

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.logging.*
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import uk.co.councilcycle.config.AppConfig
import uk.co.councilcycle.llm.DefaultLlmAnalyzer
import uk.co.councilcycle.pipeline.CouncilPipeline
import uk.co.councilcycle.pipeline.Scheduler
import uk.co.councilcycle.publisher.DefaultBlueskyPublisher
import uk.co.councilcycle.scraper.DefaultWebScraper

fun main(args: Array<String>) {
    val logger = LoggerFactory.getLogger("uk.co.councilcycle.Main")
    logger.info("CouncilCycle starting up")

    val config = loadConfig()

    val httpClient = HttpClient(CIO) {
        install(Logging) {
            level = LogLevel.INFO
        }
        engine {
            requestTimeout = 30_000
        }
    }

    val scraper = DefaultWebScraper(httpClient)
    val analyzer = DefaultLlmAnalyzer(httpClient, config)
    val publisher = DefaultBlueskyPublisher(httpClient, config)
    val pipeline = CouncilPipeline(scraper, analyzer, publisher, config)
    val scheduler = Scheduler(pipeline, config)

    val mode = args.firstOrNull() ?: "once"

    runBlocking {
        when (mode) {
            "once" -> {
                logger.info("Running in single-execution mode")
                scheduler.runOnce()
            }
            "schedule" -> {
                logger.info("Running in scheduled mode")
                scheduler.runDaily()
            }
            else -> {
                logger.error("Unknown mode: {}. Use 'once' or 'schedule'.", mode)
            }
        }
    }

    httpClient.close()
    logger.info("CouncilCycle finished")
}

private fun loadConfig(): AppConfig {
    // Load from environment variables, falling back to defaults.
    // For production, replace with Hoplite YAML loading:
    //   ConfigLoaderBuilder.default().addResourceSource("/application.yaml").build().loadConfigOrThrow<AppConfig>()
    return AppConfig(
        llm = uk.co.councilcycle.config.LlmConfig(
            apiKey = System.getenv("ANTHROPIC_API_KEY") ?: "",
            model = System.getenv("LLM_MODEL") ?: "claude-sonnet-4-20250514",
        ),
        bluesky = uk.co.councilcycle.config.BlueskyConfig(
            handle = System.getenv("BLUESKY_HANDLE") ?: "",
            appPassword = System.getenv("BLUESKY_APP_PASSWORD") ?: "",
        ),
        pipeline = uk.co.councilcycle.config.PipelineConfig(
            dailyRunTime = System.getenv("DAILY_RUN_TIME") ?: "07:00",
            maxFollowUpDepth = System.getenv("MAX_FOLLOW_UP_DEPTH")?.toIntOrNull() ?: 5,
        ),
    )
}
