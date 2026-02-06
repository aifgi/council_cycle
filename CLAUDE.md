# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run

```bash
./gradlew build          # Build and run tests
./gradlew test           # Run tests only (JUnit 5)
./gradlew run --args="config.yaml"  # Run the application with a config file
```

No tests exist yet — `src/test/` is empty.

## Architecture

Council Cycle is a Kotlin JVM application that scrapes UK council websites for committee information. It's in early stages with a small codebase.

**Key tech:** Kotlin 2.1, Gradle 8.11, Ktor Client (CIO engine) for HTTP, kotlinx.serialization + kaml for YAML config, Koin for DI, SLF4J/Logback for logging.

**Entry point:** `Main.kt` — takes a YAML config file path as its sole CLI argument, loads config, sets up Koin DI modules (config + scraper), and starts the app.

**Modules:**
- `config/` — `AppConfig` (serializable data classes for YAML structure) and `ConfigLoader` (reads and parses YAML files). Config defines councils with names, site URLs, and committee lists. See `config.example.yaml` for the schema.
- `scraper/` — `WebScraper` wraps Ktor `HttpClient` to fetch web pages, returning HTML as a string or null on failure.

**DI setup:** Koin modules are defined inline in `Main.kt` — `configModule` provides `AppConfig`, `scraperModule` provides `HttpClient` and `WebScraper`.