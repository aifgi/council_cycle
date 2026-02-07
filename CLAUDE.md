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

The orchestrator runs a 4-phase pipeline per council. Phases 1-2 use a reusable `navigationLoop()` (iterative fetch-ask-follow, max 5 iterations). Phase 3 has its own iterative loop with item accumulation (max 10 iterations). Phase 4 is a single LLM call.

1. **Phase 1: Find Committee Pages** — navigates from council site URL and finds pages for all committees at once. Returns `CommitteePagesFound` with a list of `CommitteeUrl` objects. Uses light model (Haiku).
2. **Phase 2: Find Meetings** — locates meetings with agenda links within a date range. Uses light model (Haiku).
3. **Phase 3: Triage Agenda** — iteratively navigates agenda pages, triaging individual items. The LLM can respond with `AgendaFetch` to request more URLs while carrying forward already-triaged `TriagedItem` objects, or `AgendaTriaged` to finalize. Items are accumulated across iterations in a `MutableMap` keyed by title (newer items overwrite older ones). Uses light model (Haiku).
4. **Phase 4: Analyze Extract** — analyzes pre-extracted content from phase 3 and produces `Scheme` objects. Uses heavy model (Sonnet). Single LLM call (no navigation). Only runs if phase 3 found relevant content. Populates `meetingDate` and `committeeName` on `Scheme` objects in code (not by the LLM).

**Two-model strategy:** Haiku (`lightModel`) for navigation and triage (phases 1-3), Sonnet (`heavyModel`) for deep analysis (phase 4).

**Prompt structure:** Prompts are split into `SplitPrompt(system, user)`. The `system` part contains static instructions (cached via `CacheControlEphemeral`), the `user` part contains dynamic page content. This enables Anthropic prompt caching — subsequent navigation loop iterations within a phase hit the cache on the system prompt. Prompt builders are in `Prompts.kt`.

`PhaseResponse` — sealed interface with JSON `"type"` discriminator. Variants: `Fetch` (follow more URLs), `CommitteePagesFound` (phase 1 result, list of `CommitteeUrl`), `MeetingsFound` (phase 2 result, contains `Meeting` objects), `AgendaFetch` (phase 3 intermediate — follow URLs + carry forward `TriagedItem` objects), `AgendaTriaged` (phase 3 final result, relevant flag + list of `TriagedItem`), `AgendaAnalyzed` (phase 4 result, contains `Scheme` objects).

### Other Packages

- `config/` — `AppConfig` (serializable data classes) and `ConfigLoader`. Config defines councils with names, site URLs, committee lists, and optional date range. See `config.example.yaml` for the schema.
- `scraper/` — Content extraction pipeline:
  - `WebScraper` — fetches pages via Ktor. `fetch()` returns raw HTML, `fetchAndExtract()` detects Content-Type and either extracts text from PDFs (via PDFBox) or runs the HTML extraction pipeline.
  - `ContentExtractor` — three-step pipeline: (1) remove invisible elements, (2) extract main content area via configurable CSS selectors (keeps innermost on nested matches), (3) convert to annotated markdown.
  - `AnnotatedMarkdownConverter` — converts Jsoup Document to lightweight text preserving structure (headings, links, lists, tables as `[Table] / Row N: [Header] value`). Uses `ensureBlankLine()` to prevent excess newlines without regex.
- `llm/` — `LlmClient` interface (`suspend fun generate(systemPrompt, userPrompt, model)`) with `ClaudeLlmClient` implementation using the Anthropic Java SDK's async client. System prompts are sent with `CacheControlEphemeral` for prompt caching. Includes application-level retry (3 retries with 30s/60s/60s delays + jitter) on top of the SDK's built-in retry (5 attempts) for rate limit errors.
- `processor/` — `ResultProcessor` fun interface for handling extracted schemes. Implementations in `processor.impl`: `LoggingResultProcessor` (logs to console), `FileResultProcessor` (writes to output directory), `CompositeResultProcessor` (delegates to multiple processors).

**DI setup:** Koin modules defined inline in `Main.kt` — `configModule` (AppConfig), `scraperModule` (HttpClient/ContentExtractor/WebScraper), `llmModule` (AnthropicClientAsync/LlmClient), `orchestratorModule` (ResultProcessor/Orchestrator). Both `AnthropicClientAsync` and `HttpClient` are closed explicitly in a `finally` block (Koin's `close()` does not auto-close `single` beans).

## Test Conventions

- Tests use `kotlin.test` with JUnit 5 and backtick-named test methods.
- HTTP mocking: Ktor `MockEngine` for scraper/orchestrator tests, OkHttp `MockWebServer` for Anthropic API tests.
- `MockLlmClient` (in test sources) accepts a `(systemPrompt, userPrompt) -> String` handler for orchestrator testing.
