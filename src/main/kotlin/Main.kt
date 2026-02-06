import com.anthropic.client.okhttp.AnthropicOkHttpClient
import config.AppConfig
import config.loadConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.runBlocking
import llm.ClaudeLlmClient
import llm.LlmClient
import orchestrator.Orchestrator
import processor.LoggingResultProcessor
import processor.ResultProcessor
import org.koin.core.context.startKoin
import org.koin.dsl.module
import org.slf4j.LoggerFactory
import scraper.ContentExtractor
import scraper.WebScraper
import java.io.File

private val logger = LoggerFactory.getLogger("Main")

fun main(args: Array<String>) {
    if (args.size < 2) {
        logger.error("Usage: council-cycle <config.yaml> <llm-credentials>")
        return
    }

    val appConfig = loadConfig(args[0]) ?: return

    val credentialsFile = File(args[1])
    if (!credentialsFile.exists()) {
        logger.error("LLM credentials file not found: {}", args[1])
        return
    }
    val apiKey = credentialsFile.readText().trim()

    val configModule = module {
        single<AppConfig> { appConfig }
    }

    val scraperModule = module {
        single { HttpClient(CIO) }
        single { ContentExtractor() }
        single { WebScraper(get(), get()) }
    }

    val llmModule = module {
        single {
            AnthropicOkHttpClient.builder()
                .apiKey(apiKey)
                .build()
        }
        single<LlmClient> { ClaudeLlmClient(get()) }
    }

    val orchestratorModule = module {
        single<ResultProcessor> { LoggingResultProcessor() }
        single { Orchestrator(get(), get(), get()) }
    }

    val koinApp = startKoin {
        modules(configModule, scraperModule, llmModule, orchestratorModule)
    }

    val orchestrator = koinApp.koin.get<Orchestrator>()

    runBlocking {
        for (council in appConfig.councils) {
            orchestrator.processCouncil(council)
        }
    }
}
