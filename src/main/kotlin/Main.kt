import config.AppConfig
import config.loadConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.runBlocking
import org.koin.core.context.startKoin
import org.koin.dsl.module
import org.slf4j.LoggerFactory
import scraper.ContentExtractor
import scraper.WebScraper

private val logger = LoggerFactory.getLogger("Main")

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        logger.error("Usage: council-cycle <config.yaml>")
        return
    }

    val appConfig = loadConfig(args[0]) ?: return

    val configModule = module {
        single<AppConfig> { appConfig }
    }

    val scraperModule = module {
        single { HttpClient(CIO) }
        single { ContentExtractor() }
        single { WebScraper(get(), get()) }
    }

    val koinApp = startKoin {
        modules(configModule, scraperModule)
    }

    val scraper = koinApp.koin.get<WebScraper>()

    runBlocking {
        for (council in appConfig.councils) {
            logger.info("Fetching site for council: {}", council.name)
            val content = scraper.fetchAndExtract(council.siteUrl)
            if (content != null) {
                logger.info("Extracted {} chars for {}", content.length, council.name)
                logger.debug("Content for {}:\n{}", council.name, content)
            }
        }
    }
}
