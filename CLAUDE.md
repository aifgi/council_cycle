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

**Entry point:** `Main.kt` — takes a YAML config file path as its sole CLI argument, loads config, sets up Koin DI modules, then calls `fetchAndExtract()` for each council's site URL.

**Packages:**
- `config/` — `AppConfig` (serializable data classes for YAML structure) and `ConfigLoader` (reads and parses YAML files). Config defines councils with names, site URLs, and committee lists. See `config.example.yaml` for the schema.
- `scraper/` — Content extraction pipeline:
  - `WebScraper` — fetches pages via Ktor. `fetch()` returns raw HTML, `fetchAndExtract()` fetches and runs the full extraction pipeline.
  - `ContentExtractor` — three-step pipeline: (1) remove invisible elements (scripts, styles, hidden/aria-hidden, images), (2) extract main content area via configurable CSS selectors (deduplicates nested matches by keeping innermost), (3) convert to annotated markdown via `AnnotatedMarkdownConverter`.
  - `AnnotatedMarkdownConverter` — converts Jsoup Document to a lightweight text format preserving structure (headings, links, lists, bold/italic, code, blockquotes) while stripping all HTML markup. Tables use `[Table] / Row N: [Header] value` format. Relative links are resolved to absolute URLs using the page's base URL.

**DI setup:** Koin modules are defined inline in `Main.kt` — `configModule` provides `AppConfig`, `scraperModule` provides `HttpClient`, `ContentExtractor`, and `WebScraper`.
