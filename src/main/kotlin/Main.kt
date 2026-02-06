import config.AppConfig
import config.loadConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import org.koin.core.context.startKoin
import org.koin.dsl.module
import org.slf4j.LoggerFactory
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
        single { WebScraper(get()) }
    }

    startKoin {
        modules(configModule, scraperModule)
    }
}
