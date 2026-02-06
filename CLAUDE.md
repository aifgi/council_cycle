# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run

```bash
./gradlew build          # Build and run tests
./gradlew test           # Run tests only (JUnit 5)
./gradlew test --tests "scraper.ContentExtractorTest"  # Run a single test class
./gradlew run --args="config.yaml llm-credentials.txt"  # Run the application
```

## Architecture

Council Cycle is a Kotlin JVM application that scrapes UK council websites for committee meeting information using LLM-guided navigation.

**Key tech:** Kotlin 2.1, Gradle 8.11, Ktor Client (CIO engine) for HTTP, Jsoup for HTML parsing, Anthropic Java SDK for Claude, kotlinx.serialization (YAML via kaml, JSON), Koin for DI, SLF4J/Logback for logging.

**Entry point:** `Main.kt` — takes two CLI arguments: a YAML config file and an LLM credentials file (plain text API key). Sets up four Koin DI modules (`configModule`, `scraperModule`, `llmModule`, `orchestratorModule`), then runs `Orchestrator.processCouncil()` for each council.

**Packages:**
- `config/` — `AppConfig` (serializable data classes for YAML structure) and `ConfigLoader` (reads and parses YAML files). Config defines councils with names, site URLs, and committee lists. See `config.example.yaml` for the schema.
- `scraper/` — Content extraction pipeline:
  - `WebScraper` — fetches pages via Ktor. `fetch()` returns raw HTML, `fetchAndExtract()` fetches and runs the full extraction pipeline.
  - `ContentExtractor` — three-step pipeline: (1) remove invisible elements (scripts, styles, hidden/aria-hidden, images), (2) extract main content area via configurable CSS selectors (deduplicates nested matches by keeping innermost), (3) convert to annotated markdown via `AnnotatedMarkdownConverter`.
  - `AnnotatedMarkdownConverter` — converts Jsoup Document to a lightweight text format preserving structure (headings, links, lists, bold/italic, code, blockquotes) while stripping all HTML markup. Tables use `[Table] / Row N: [Header] value` format. Relative links are resolved to absolute URLs using the page's base URL.
- `llm/` — LLM integration:
  - `LlmClient` — interface with `suspend fun generate(prompt: String, model: String): String`.
  - `ClaudeLlmClient` — implementation using the Anthropic Java SDK. Takes an `AnthropicClient` in constructor, wraps calls in `Dispatchers.IO`.
- `orchestrator/` — LLM-guided navigation loop:
  - `Orchestrator` — for each council/committee, runs an iterative loop (max 5 iterations): fetch pages via `WebScraper`, send content to LLM with a prompt asking it to find committee meeting info, parse the JSON response, and either follow URLs the LLM suggests or return found results.
  - `LlmResponse` — sealed interface with two variants: `Fetch` (urls + reason to follow more links) and `Found` (committee meeting details). Deserialized from LLM JSON output using a `"type"` discriminator.

**DI setup:** Koin modules are defined inline in `Main.kt` — `configModule` provides `AppConfig`, `scraperModule` provides `HttpClient`/`ContentExtractor`/`WebScraper`, `llmModule` provides `AnthropicClient`/`LlmClient`, `orchestratorModule` provides `Orchestrator`.
