import com.anthropic.client.AnthropicClientAsync
import com.anthropic.client.okhttp.AnthropicOkHttpClientAsync
import config.AppConfig
import config.loadConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.runBlocking
import llm.ClaudeLlmClient
import llm.LlmClient
import llm.LoggingLlmClient
import orchestrator.phase.AnalyzeExtractPhase
import orchestrator.phase.FindCommitteePagesPhase
import orchestrator.phase.FindMeetingsPhase
import orchestrator.Orchestrator
import orchestrator.phase.TriageAgendaPhase
import processor.ResultProcessor
import processor.impl.CompositeResultProcessor
import processor.impl.FileResultProcessor
import processor.impl.LoggingResultProcessor
import org.koin.core.context.startKoin
import org.koin.dsl.module
import org.slf4j.LoggerFactory
import scraper.ContentExtractor
import scraper.WebScraper
import java.io.File
import java.nio.file.Paths

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
        single<AnthropicClientAsync> {
            AnthropicOkHttpClientAsync.builder()
                .apiKey(apiKey)
                .maxRetries(5)
                .build()
        }
        single<LlmClient> {
            val client: LlmClient = ClaudeLlmClient(get())
            val config = get<AppConfig>()
            if (config.debugLlmDir != null) LoggingLlmClient(client, config.debugLlmDir) else client
        }
    }

    val orchestratorModule = module {
        single<ResultProcessor> {
            val processors = buildList {
                add(LoggingResultProcessor())
                val outputDir = appConfig.outputDir
                if (outputDir != null) {
                    add(FileResultProcessor(Paths.get(outputDir)))
                }
            }
            CompositeResultProcessor(processors)
        }
        single { FindCommitteePagesPhase(get(), get()) }
        single { FindMeetingsPhase(get(), get()) }
        single { TriageAgendaPhase(get(), get()) }
        single { AnalyzeExtractPhase(get(), get()) }
        single { Orchestrator(get(), get(), get(), get(), get()) }
    }

    val koinApp = startKoin {
        modules(configModule, scraperModule, llmModule, orchestratorModule)
    }

    val orchestrator = koinApp.koin.get<Orchestrator>()

    try {
        runBlocking {
            for (council in appConfig.councils) {
                orchestrator.processCouncil(council)
            }
        }
    } finally {
        koinApp.koin.get<AnthropicClientAsync>().close()
        koinApp.koin.get<HttpClient>().close()
        koinApp.close()
    }
}
