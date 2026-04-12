# Council Cycle — LLM-Guided Council Meeting Scraper

## Overview

Kotlin JVM application that scrapes UK council websites for committee meeting agenda items related to transport and cycling schemes. For each configured council and committee, it runs a 6-phase LLM-guided pipeline that navigates the council website, identifies relevant agenda items, extracts detail from linked documents, and produces structured `Scheme` records.

Entry point: `Main.kt`. CLI: `<config.yaml> <llm-credentials.txt>` (credentials file contains a plain-text Anthropic API key). Bootstraps Koin DI with four modules (`configModule`, `scraperModule`, `llmModule`, `orchestratorModule`), then calls `Orchestrator.processCouncil()` for each council in the config. Both `AnthropicClientAsync` and `HttpClient` are explicitly closed in a `finally` block after all processing.

---

## Configuration (`config/`)

### `AppConfig`
Serialized from YAML.

| Field | Type | Description |
|---|---|---|
| `councils` | `List<CouncilConfig>` | Councils to process |
| `outputDir` | `String?` | Directory for output files; omitted means no file output |
| `debugLlmDir` | `String?` | Directory to write LLM prompt/response logs; omitted disables |

### `CouncilConfig`

| Field | Type | Description |
|---|---|---|
| `name` | `String` | Display name |
| `siteUrl` | `String` | Starting URL for phase 1 |
| `committees` | `List<String>` | Committee names to look for |
| `dateFrom` | `String?` | ISO date (YYYY-MM-DD); defaults to today |
| `dateTo` | `String?` | ISO date; defaults to 3 months from today |

### `loadConfig(filePath)`
Returns `AppConfig?`. Logs and returns `null` on missing file or parse error.

---

## Topics of Interest

Defined in `Prompts.kt`, used verbatim in phase 4 and phase 6 prompts:

**Included topics:** cycle lanes, traffic/modal filters, LTN/low traffic/liveable neighbourhoods, public realm improvements, school streets, pedestrian/zebra crossings, local implementation plan/LIP.

**Excluded topics:** highway maintenance, work programme.

---

## Orchestrator (`orchestrator/Orchestrator.kt`)

`processCouncil(council: CouncilConfig)` runs the full pipeline:

1. **Phase 1** — finds all committee page URLs in one call, returns `Map<committeeName, url>`.
2. For each committee:
   - **Phase 2** — finds meetings in date range from the committee page.
   - For each meeting (skip if `meetingUrl == null`):
     - **Phase 3** — finds the agenda document URL from the meeting page.
     - **Phase 4** — identifies relevant agenda items from the agenda document.
     - Skip if no items found.
     - **Phase 5** — enriches each item with extracts from linked documents.
     - Skip if result is `null`, not relevant, or has no items.
     - **Phase 6** — analyzes the combined extract and produces `Scheme` objects.
   - Calls `resultProcessor.process(councilName, committeeName, allSchemes)` once per committee with all schemes from all meetings.

On any phase returning `null` or empty, the orchestrator logs a warning and continues to the next item (meeting, committee, etc.) without aborting.

---

## Pipeline Phases (`orchestrator/phase/`)

### `Phase<I, O>` interface
```kotlin
interface Phase<in I, out O> {
    val name: String
    suspend fun execute(input: I): O?
}
```

### `BasePhase<I, O>`
All phases extend this. Provides:

- **`execute(input)`** — final; delegates to `doExecute(input)`. After `doExecute` returns (normally or via exception), calls `webScraper.releaseDocument(url)` for every URL fetched during this execution to free PDF cache memory.
- **`fetchAndExtract(url)`** — fetches via `WebScraper`, tracks the URL for cleanup.
- **`parseResponse(raw)`** — strips optional ` ```json ` fences, parses JSON as `LlmResponse`. Returns `null` and logs on failure.
- **`navigationLoop(...)`** — generic fetch-ask-follow loop (see below).

### `navigationLoop`
Parameters: `startUrl`, `phaseName`, `model`, `maxIterations`, `buildPrompt: (String) -> SplitPrompt`, `extractResult: (LlmResponse) -> R?`.

Algorithm:
1. Maintains a URL queue starting with `startUrl`.
2. Each iteration: dequeues a URL, fetches and extracts its content, builds a prompt from the content, calls the LLM.
3. Resolves all URL tokens in the LLM response via `conversionResult.urlRegistry::resolve`.
4. Calls `extractResult(response)`; if non-null, returns it immediately.
5. If result is null and response is `LlmResponse.Fetch`, appends the requested URLs to the queue.
6. Any other response type at this point is unexpected — logs a warning and returns `null`.
7. Returns `null` when iterations are exhausted (logs warning).

**Model constants:**
- `DEFAULT_LIGHT_MODEL = "claude-sonnet-4-6"` — phases 1–5
- `DEFAULT_HEAVY_MODEL = "claude-opus-4-6"` — phase 6
- `DEFAULT_MAX_ITERATIONS = 5`
- `DEFAULT_TRIAGE_MAX_ITERATIONS = 5`

---

### Phase 1 — `FindCommitteePagesPhase`
Input: `FindCommitteePagesInput(startUrl, committeeNames)`. Output: `Map<String, String>` (committee name → URL) or null.

Uses `navigationLoop` with `buildPhase1Prompt`. Extracts result from `LlmResponse.CommitteePagesFound` by associating `CommitteeUrl.name → url`.

---

### Phase 2 — `FindMeetingsPhase`
Input: `FindMeetingsInput(committeeUrl, committeeName, dateFrom, dateTo)`. Output: `List<Meeting>?`.

Uses `navigationLoop` with `buildPhase2Prompt`. Extracts `LlmResponse.MeetingsFound.meetings`.

---

### Phase 3 — `FindAgendaPhase`
Input: `FindAgendaInput(meetingUrl)`. Output: `String?` (agenda URL).

Uses `navigationLoop` with `buildFindAgendaPrompt`. Extracts `LlmResponse.AgendaFound.agendaUrl`.

---

### Phase 4 — `IdentifyAgendaItemsPhase`
Input: `IdentifyAgendaItemsInput(agendaUrl, committeeName, meetingDate)`. Output: `List<IdentifiedAgendaItem>?`.

Custom loop (not `navigationLoop`) that accumulates items across multiple pages:
- Maintains a URL queue (starts with `agendaUrl`) and a `Map<title, IdentifiedAgendaItem>` (newer entries overwrite older on same title).
- Each iteration: fetches a URL, calls LLM with `buildIdentifyAgendaItemsPrompt` (passing the previous `fetchReason` on continuation pages).
- On `AgendaItemsIdentified`: merges items into the accumulator. If `fetchUrls` is empty, returns accumulated items immediately. Otherwise queues the fetch URLs and continues.
- Returns accumulated items (or null if empty) on iteration exhaustion.

---

### Phase 5 — `EnrichAgendaItemsPhase`
Input: `EnrichAgendaItemsInput(meetingUrl, identifiedItems, committeeName, meetingDate)`. Output: `LlmResponse.AgendaTriaged?`.

Custom iterative loop:
- URL queue entries are `Pair<url, List<IdentifiedAgendaItem>?>` where the second element is the set of items that requested this URL (null for the initial meeting URL, which targets all items).
- Tracks `processedOrQueuedUrls` to avoid fetching the same URL twice.
- Each iteration: fetches a URL, calls LLM with `buildEnrichAgendaItemsPrompt` (passing `fetchedFor` when applicable).
- Only accepts `LlmResponse.AgendaItemsEnriched` responses; any other type returns `null`.
- For each `EnrichedItem.Summary`: appends `extract` to a `summariesByItem[title]` list.
- For each `EnrichedItem.Fetch`: queues new URLs (not already seen), associating each with the requesting item (or all items if the requesting item can't be found by title).
- After queue exhaustion: builds `TriagedItem` list — each item's extract is all collected summaries joined by `\n\n---\n\n`.
- Returns `null` if no summaries were collected; otherwise returns `AgendaTriaged(relevant=true, items=completedItems)`.

---

### Phase 6 — `AnalyzeExtractPhase`
Input: `AnalyzeExtractInput(extract, committeeName, meeting)`. Output: `List<Scheme>?`.

Single LLM call (no navigation loop) with `buildAnalyzeExtractPrompt`. The `extract` passed in is all `TriagedItem` objects joined as markdown sections (`## {title}\n{extract}`). Maps `LlmResponse.AgendaAnalyzed.schemes`, setting `meetingDate`, `committeeName`, and `agendaUrl` from the input (not from the LLM).

---

## LLM Response Types (`orchestrator/LlmResponse.kt`)

All variants are a `sealed interface LlmResponse` with a JSON `"type"` discriminator.

| Type value | Class | Fields |
|---|---|---|
| `fetch` | `Fetch` | `urls: List<String>`, `reason: String` |
| `committee_pages_found` | `CommitteePagesFound` | `committees: List<CommitteeUrl>` |
| `meetings_found` | `MeetingsFound` | `meetings: List<Meeting>` |
| `agenda_found` | `AgendaFound` | `agendaUrl: String` |
| `agenda_items_identified` | `AgendaItemsIdentified` | `items: List<IdentifiedAgendaItem>`, `fetchUrls: List<String>`, `fetchReason: String?` |
| `agenda_items_enriched` | `AgendaItemsEnriched` | `items: List<EnrichedItem>` |
| `agenda_triaged` | `AgendaTriaged` | `relevant: Boolean`, `items: Collection<TriagedItem>`, `summary: String?` |
| `agenda_analyzed` | `AgendaAnalyzed` | `schemes: List<Scheme>` |

`EnrichedItem` is a separate sealed interface with JSON `"action"` discriminator:
- `action: "summary"` → `EnrichedItem.Summary(title, extract)`
- `action: "fetch"` → `EnrichedItem.Fetch(title, urls, reason)`

`resolveUrls(resolve: (String) -> String): LlmResponse` — extension function that maps URL fields in response variants through the `resolve` function (pass-through for non-URL-bearing variants like `AgendaTriaged` and `AgendaAnalyzed`).

**Domain data classes:**
- `CommitteeUrl(name, url)`
- `Meeting(date, title, meetingUrl?)`
- `IdentifiedAgendaItem(title, description)`
- `TriagedItem(title, extract)`
- `Scheme(title, topic, summary, meetingDate, committeeName, agendaUrl)` — all strings, non-null with empty defaults.

---

## Prompts (`orchestrator/Prompts.kt`)

`SplitPrompt(system: String, user: String)` — the system part carries static instructions (sent with `CacheControlEphemeral` for prompt caching); the user part carries dynamic page content.

All prompt builders instruct the LLM to respond with **only** a single JSON object with a `"type"` field. URL references in page content are short tokens (e.g. `@1`, `@2`) substituted by `UrlRegistry`; prompts instruct the LLM to use these tokens when returning URLs.

| Builder | Phases | Key instructions |
|---|---|---|
| `buildPhase1Prompt(committeeNames, pageContent)` | 1 | Find committee page URLs or request more links (1–5 relevant links) |
| `buildPhase2Prompt(committeeName, dateFrom, dateTo, pageContent)` | 2 | Find meetings in date range; do not follow document/PDF links; normalize dates to YYYY-MM-DD |
| `buildFindAgendaPrompt(meetingUrl, pageContent)` | 3 | Find agenda document URL; prefer frontsheet over full pack; fall back to meeting URL itself |
| `buildIdentifyAgendaItemsPrompt(committeeName, meetingDate, pageContent, fetchReason?)` | 4 | Identify relevant items from topics list; use `fetchUrls` only if agenda list is truncated mid-list |
| `buildEnrichAgendaItemsPrompt(committeeName, meetingDate, items, pageContent, fetchedFor?)` | 5 | For each item: provide `"summary"` and/or `"fetch"`; do not fetch full agenda packs; include proposals, locations, consultation outcomes, decisions (verbatim), and vote counts |
| `buildAnalyzeExtractPrompt(extract)` | 6 | Identify schemes from topics list; decisions must be in summary; write "No decision." when none recorded |

---

## Scraper (`scraper/`)

### `WebScraper`
- `fetch(url): String?` — raw HTTP GET, returns body as text or null on failure.
- `fetchAndExtract(url): ConversionResult?` — detects content type:
  - If URL matches `pdf-page.internal` host: resolves via `PdfExtractor.getPage(url)`.
  - If `Content-Type: application/pdf`: reads bytes, calls `PdfExtractor.extract(bytes, url)`.
  - Otherwise: reads as text, calls `HtmlExtractor.extract(html, baseUrl)`.
- `releaseDocument(url)` — delegates to `PdfExtractor.release(url)`.
- Spaces in URLs are percent-encoded to `%20` before sending the HTTP request.
- Returns `null` and logs on non-2xx status or exception.

### `ConversionResult`
```kotlin
data class ConversionResult(val text: String, val urlRegistry: UrlRegistry)
```

### `UrlRegistry`
Maps full URLs to short tokens (`@1`, `@2`, …) and back. Tokens are assigned sequentially. `register(url)` is idempotent — same URL returns same token. `resolve(tokenOrUrl)` returns the full URL if the token is known, otherwise returns the input unchanged.

### `HtmlExtractor`
Three-step pipeline:
1. **Remove invisible elements** — strips `script`, `style`, `noscript`, `template`, `meta`, `link`, `img`, `[hidden]`, `[style~=display:none]`, `[style~=visibility:hidden]`, `[aria-hidden=true]`.
2. **Extract main content** — tries CSS selectors in order: `main`, `[role=main]`, `#content`, `#content-holder`, `#main-content`, `.content-area`. Keeps the innermost match on nested results. Throws `IllegalStateException` if more than one non-nested match remains. Falls back to the full document with a warning if nothing matches. Configurable via `mainContentSelectors` constructor parameter.
3. **Convert to annotated markdown** — via `AnnotatedMarkdownConverter`.

### `AnnotatedMarkdownConverter`
Converts a Jsoup `Document` to `ConversionResult`. All links are registered in a fresh `UrlRegistry` and replaced with tokens. Conversion rules:

| HTML | Markdown output |
|---|---|
| h1–h6 | `# ` … `###### ` prefix with blank lines around |
| p | Block with blank lines around |
| br | `\n` |
| a (with href) | `[text](@N)` — URL registered and replaced with token |
| a (no href) | plain text |
| ul | `- item\n` per `<li>` |
| ol | `1. item\n`, `2. item\n`, … |
| table | `[Table]\n` header, then `Row N: [ColHeader] value | [ColHeader] value` per row; column labels from `<thead th>` or first-row `<th>`; falls back to `Column N` |
| blockquote | `> ` prefix per line |
| pre | Fenced code block (` ``` `) |
| strong/b | `**text**` |
| em/i | `*text*` |
| code | `` `text` `` |
| hr | `---` with blank lines |
| unknown tags | render children recursively |

Blank lines are managed via `ensureBlankLine()` (appends `\n` or `\n\n` as needed, does not use regex).

### `PdfExtractor`
Extracts text from PDF bytes using PDFBox. Splits documents into 5-page chunks upfront.

- `extract(bytes, url): ConversionResult?` — loads the PDF, extracts text in 5-page chunks, stores all chunks in `PdfCache` keyed by `"<docId>/<startPage>"`. Returns the first chunk as a `ConversionResult`. When more chunks follow, appends a continuation notice with a `https://pdf-page.internal/<docId>/<nextStartPage>` URL (registered as a token) to the text.
- `getPage(url): ConversionResult?` — resolves a `pdf-page.internal` URL to a chunk key via the URI path and retrieves it from `PdfCache`.
- `release(url)` — removes all chunks for the given original URL from `PdfCache`.
- `isPdfPageUrl(url)` — returns true if `URI(url).host == "pdf-page.internal"`.

### `PdfCache`
Thread-safe (uses `ConcurrentHashMap`). Stores `PdfChunk` objects keyed by chunk key, and tracks which chunk keys belong to each original URL for bulk release.

```kotlin
data class PdfChunk(
    val text: String,
    val startPage: Int,
    val endPage: Int,
    val totalPages: Int,
    val nextChunkKey: String?,
)
```

---

## LLM Client (`llm/`)

### `LlmClient` interface
```kotlin
interface LlmClient {
    suspend fun generate(systemPrompt: String, userPrompt: String, model: String): String
}
```

### `ClaudeLlmClient`
Implements `LlmClient` using `AnthropicClientAsync`.

- Sends system prompt with `CacheControlEphemeral` (enables prompt caching).
- `maxTokens = 4096`.
- Rejects user prompts exceeding ~50,000 estimated tokens (length / 4) with `IllegalArgumentException`.
- Application-level retry on `RateLimitException` (on top of the SDK's built-in retries): 3 retries with delays of 30s, 60s, 60s plus up to 10% random jitter. Throws the last `RateLimitException` if all retries fail.
- SDK is configured with `maxRetries = 5`.

### `LoggingLlmClient`
Decorator that delegates to another `LlmClient` and writes each request/response to a timestamped `.txt` file in `outputDir`. File contains `=== MODEL ===`, `=== SYSTEM PROMPT ===`, `=== USER PROMPT ===`, `=== RESPONSE ===` sections. Failures to write are logged as warnings and do not affect the response.

---

## Result Processor (`processor/`)

### `ResultProcessor` (fun interface)
```kotlin
fun interface ResultProcessor {
    fun process(councilName: String, committeeName: String, schemes: List<Scheme>)
}
```

### `LoggingResultProcessor`
Logs to SLF4J. If `schemes` is empty, logs a single info message. Otherwise logs the count and one line per scheme showing `[topic] title - summary (meeting: date)`.

### `FileResultProcessor(outputDir: Path)`
Writes a text file per `(council, committee)` pair to `outputDir`, creating directories as needed. Filename: `"<councilName> - <committeeName>.txt"` with non-alphanumeric characters (except space, `.`, `_`, `-`) replaced by `_`. Content format:
```
Council: <name>
Committee: <name>
Schemes: <count>

---
Title: ...
Topic: ...
Meeting date: ...
Agenda URL: ...
Summary: <word-wrapped at 80 chars, contiknuation lines indented 2 spaces>
```

### `CompositeResultProcessor(delegates: List<ResultProcessor>)`
Delegates to all processors in order.

**DI wiring:** `LoggingResultProcessor` is always included. `FileResultProcessor` is added when `outputDir` is set in config. Both are combined into a `CompositeResultProcessor`.

---

## Testing

- Tests use `kotlin.test` assertions with JUnit 5 and backtick-named methods.
- HTTP mocking: Ktor `MockEngine` for scraper/orchestrator tests; OkHttp `MockWebServer` for Anthropic API tests.
- `MockLlmClient` in test sources: takes a `(systemPrompt, userPrompt) -> String` lambda.
- Tests tagged `real-llm` are excluded from the default `test` task; run via the `realLlmTest` Gradle task with `ANTHROPIC_API_KEY` env var.
- `EndToEndTest` uses actual HTML and PDF fixture files (Kingston council pages) with `MockEngine` for HTTP and either `MockLlmClient` or a real `ClaudeLlmClient`.
