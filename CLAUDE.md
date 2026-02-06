# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run

```bash
./gradlew build          # Build and run tests
./gradlew test           # Run tests only (JUnit 5)
./gradlew test --tests "scraper.ContentExtractorTest"  # Run a single test class
./gradlew run --args="config.yaml"  # Run the application with a config file
```

## Architecture

Council Cycle is a Kotlin JVM application that scrapes UK council websites for committee information.

**Key tech:** Kotlin 2.1, Gradle 8.11, Ktor Client (CIO engine) for HTTP, Jsoup for HTML parsing, kotlinx.serialization + kaml for YAML config, Koin for DI, SLF4J/Logback for logging.

**Entry point:** `Main.kt` — takes a YAML config file path as its sole CLI argument, loads config, sets up Koin DI modules, then fetches each council's site URL.

**Packages:**
- `config/` — `AppConfig` (serializable data classes for YAML structure) and `ConfigLoader` (reads and parses YAML files). Config defines councils with names, site URLs, and committee lists. See `config.example.yaml` for the schema.
- `scraper/` — `WebScraper` fetches web pages via Ktor and delegates to `ContentExtractor` for processing. `fetchAndExtract()` combines both steps. `ContentExtractor` runs a pipeline on raw HTML: removes invisible elements (scripts, styles, hidden attributes, aria-hidden, etc.) then extracts the main content area via configurable CSS selectors.

**DI setup:** Koin modules are defined inline in `Main.kt` — `configModule` provides `AppConfig`, `scraperModule` provides `HttpClient`, `ContentExtractor`, and `WebScraper`.
