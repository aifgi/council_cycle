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

Council Cycle is a Kotlin JVM application that scrapes UK council websites for committee meeting information (specifically transport and planning schemes) using LLM-guided navigation.

**Key tech:** Kotlin 2.1, Gradle 8.11, Ktor Client (CIO engine) for HTTP, Jsoup for HTML parsing, Apache PDFBox for PDF text extraction, Anthropic Java SDK (async client) for Claude, kotlinx.serialization (YAML via kaml, JSON), Koin for DI, SLF4J/Logback for logging.

**Entry point:** `Main.kt` — takes two CLI arguments: a YAML config file and an LLM credentials file (plain text API key). Sets up four Koin DI modules (`configModule`, `scraperModule`, `llmModule`, `orchestratorModule`), then runs `Orchestrator.processCouncil()` for each council.

### 4-Phase Pipeline (orchestrator/)

The orchestrator runs a 4-phase pipeline per council/committee. Phases 1-3 use a reusable `navigationLoop()` (iterative fetch-ask-follow, max 5 iterations). Phase 4 is a single LLM call.

1. **Phase 1: Find Committee Page** — navigates from council site URL to the specific committee's page. Uses light model (Haiku).
2. **Phase 2: Find Meetings** — locates meetings with agenda links within a date range. Uses light model (Haiku).
3. **Phase 3: Triage Agenda** — navigates agenda pages and determines relevance. If relevant, extracts the relevant portions (or summarizes minutes). Uses light model (Haiku). Returns `AgendaTriaged` with a `relevant` flag and optional `extract`.
4. **Phase 4: Analyze Extract** — analyzes pre-extracted content from phase 3 and produces `Scheme` objects. Uses heavy model (Sonnet). Single LLM call (no navigation). Only runs if phase 3 found relevant content.

**Two-model strategy:** Haiku (`lightModel`) for navigation and triage (phases 1-3), Sonnet (`heavyModel`) for deep analysis (phase 4).

`PhaseResponse` — sealed interface with JSON `"type"` discriminator. Variants: `Fetch` (follow more URLs), `CommitteePageFound` (phase 1 result), `MeetingsFound` (phase 2 result, contains `Meeting` objects), `AgendaTriaged` (phase 3 result, relevant flag + extracted text), `AgendaAnalyzed` (phase 4 result, contains `Scheme` objects).

### Other Packages

- `config/` — `AppConfig` (serializable data classes) and `ConfigLoader`. Config defines councils with names, site URLs, committee lists, and optional date range. See `config.example.yaml` for the schema.
- `scraper/` — Content extraction pipeline:
  - `WebScraper` — fetches pages via Ktor. `fetch()` returns raw HTML, `fetchAndExtract()` detects Content-Type and either extracts text from PDFs (via PDFBox) or runs the HTML extraction pipeline.
  - `ContentExtractor` — three-step pipeline: (1) remove invisible elements, (2) extract main content area via configurable CSS selectors (keeps innermost on nested matches), (3) convert to annotated markdown.
  - `AnnotatedMarkdownConverter` — converts Jsoup Document to lightweight text preserving structure (headings, links, lists, tables as `[Table] / Row N: [Header] value`). Resolves relative links to absolute URLs.
- `llm/` — `LlmClient` interface (`suspend fun generate(prompt, model)`) with `ClaudeLlmClient` implementation using the Anthropic Java SDK's async client (`AnthropicClientAsync`, `CompletableFuture.await()`).
- `processor/` — `ResultProcessor` fun interface for handling extracted schemes. `LoggingResultProcessor` is the default implementation.

**DI setup:** Koin modules defined inline in `Main.kt` — `configModule` (AppConfig), `scraperModule` (HttpClient/ContentExtractor/WebScraper), `llmModule` (AnthropicClientAsync/LlmClient), `orchestratorModule` (ResultProcessor/Orchestrator). Both `AnthropicClientAsync` and `HttpClient` are closed explicitly in a `finally` block (Koin's `close()` does not auto-close `single` beans).

## Test Conventions

- Tests use `kotlin.test` with JUnit 5 and backtick-named test methods.
- HTTP mocking: Ktor `MockEngine` for scraper/orchestrator tests, OkHttp `MockWebServer` for Anthropic API tests.
- `MockLlmClient` (in test sources) accepts a handler function for orchestrator testing.
