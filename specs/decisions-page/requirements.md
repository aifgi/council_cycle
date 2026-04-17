---
spec: decisions-page
phase: requirements
created: 2026-04-12
---

# Requirements: Decisions Page Pipeline

## Goal

Enable Council Cycle to scrape UK council decision registers (ModernGov `mgDelegatedDecisions.aspx`) as an alternative to committee meeting scraping. Councils configured in `decisions` mode enumerate paginated decision listings, filter by configured decision makers, enrich each matching decision from its detail page and relevant linked documents, and produce `Scheme` objects using the same structure as the existing meetings pipeline.

---

## Glossary

| Term | Definition |
|------|-----------|
| **Decisions mode** | Per-council pipeline variant that scrapes a decisions listing page instead of navigating committee meetings. Selected via `mode: decisions` in config. |
| **Meetings mode** | The existing pipeline (phases 1–6). Default when `mode` is absent or `meetings`. |
| **Decisions listing page** | ModernGov `mgDelegatedDecisions.aspx` — a paginated table of delegated/executive decisions for a council. |
| **Date window** | A fixed-width date range slice displayed on the listing page at one time (~14 days on ModernGov). Navigated via "Earlier"/"Later" links that shift the `DR` URL parameter. |
| **Earlier/Later navigation** | Pagination controls on the listing page that shift the visible date window backward or forward in time. |
| **Decision detail page** | `ieDecisionDetails.aspx?ID=<ref>` — individual decision page showing decision maker, decision body text, status, and linked PDFs. |
| **Decision maker** | The role or named officer/cabinet member who took the decision (e.g. "Cabinet Member for Streets"). Shown on the detail page; may not appear in the listing table. |
| **`decisionMakers`** | Config field listing the decision maker names the app should include. Decisions by any other maker are skipped. |
| **`decisionsUrl`** | Config field holding the base URL of the council's decisions listing page. Mandatory when `mode: decisions`. |
| **Phase D2** | Decisions pipeline phase that enumerates decision listing pages, follows Earlier/Later pagination, and returns matching decisions. |
| **Phase D3** | Decisions pipeline phase that fetches a single decision's detail page and selectively fetches relevant linked documents (excluding drawings and non-textual files) to produce a rich text extract. |
| **Phase D4** | Decisions pipeline reuse of existing Phase 6 (`AnalyzeExtractPhase`) — unchanged. |
| **Relevant document** | A linked PDF or document that contains decision rationale, reports, or policy text. Excludes drawings, maps, plans, images, and other non-textual attachments. |

---

## User Stories

### US-1: Configure a council in decisions mode

**As a** council researcher
**I want to** configure a council with `mode: decisions`, a `decisionsUrl`, and a `decisionMakers` list
**So that** the app scrapes that council's decision register instead of navigating committee meetings

**Acceptance Criteria:**
- [ ] AC-1.1: A council config with `mode: decisions`, `decisionsUrl`, and `decisionMakers` is parsed without error and selects the decisions pipeline at runtime.
- [ ] AC-1.2: A council config without `mode` (or with `mode: meetings`) continues to select the existing meetings pipeline unchanged.
- [ ] AC-1.3: If `mode: decisions` is set but `decisionsUrl` is absent, the app fails fast with a clear error message at startup (not mid-run).
- [ ] AC-1.4: `decisionMakers` accepts one or more string values; the app fails fast if `mode: decisions` is set but `decisionMakers` is empty or absent.
- [ ] AC-1.5: A single config file may contain a mix of `decisions`-mode and `meetings`-mode councils; both run in the same execution.

---

### US-2: Enumerate all decisions within the configured date range

**As a** council researcher
**I want to** the app to follow Earlier/Later pagination on the decisions listing page until it has covered the full configured date range
**So that** no decisions within the date range are missed due to date-window truncation

**Acceptance Criteria:**
- [ ] AC-2.1: The app starts enumeration from `decisionsUrl` (with the council's `dateFrom`/`dateTo` range applied where possible).
- [ ] AC-2.2: The LLM follows "Earlier" / "Later" navigation links across date windows until the full `dateFrom`–`dateTo` range has been scanned or the configurable max-iterations limit is reached.
- [ ] AC-2.3: The max-iterations limit for D2 defaults to 50 and is defined as a code constant (`DEFAULT_D2_MAX_ITERATIONS = 50` in `Phase.kt`); it is not configurable per-council in config.
- [ ] AC-2.4: When max iterations is reached before the full range is covered, the app logs a warning identifying the council and the last date window reached, then continues with decisions found so far (no crash).
- [ ] AC-2.5: Decisions outside the configured date range (before `dateFrom` or after `dateTo`) are not returned by D2, even if they appear on a visited listing page.

---

### US-3: Filter decisions by configured decision makers and topics of interest

**As a** council researcher
**I want to** only decisions taken by the configured `decisionMakers` AND relating to the app's topics of interest to be processed
**So that** unrelated decisions (wrong officer, or irrelevant subject matter) do not waste tokens or appear in the output

**Acceptance Criteria:**
- [ ] AC-3.1: The LLM compares each decision's title/context on the listing page against the `decisionMakers` list and includes only matches.
- [ ] AC-3.2: Matching is case-insensitive substring or fuzzy match (e.g. "Cabinet Member for Streets" matches "Cllr Jane Smith — Cabinet Member for Streets").
- [ ] AC-3.3: If the decision maker is not visible in the listing table row (ModernGov `DS=2` view), the LLM may defer the decision maker check to D3 (detail page), where the "Decision Maker" field is always present.
- [ ] AC-3.4: The D3 LLM prompt includes the `decisionMakers` list; if the detail page shows a decision maker that does not match, the LLM returns a result indicating the decision is not relevant (e.g. empty/irrelevant extract). Code-level string matching on decision maker names is not applied — the LLM's judgement is trusted to handle name variations and spelling differences.
- [ ] AC-3.5: If zero decisions match after scanning the full date range, the app logs an informational message for the council and produces no output (no crash).
- [ ] AC-3.6: The D2 LLM prompt includes the `TOPICS` (topics of interest) and `EXCLUDED_TOPICS` lists from `Prompts.kt`; the LLM excludes decisions whose title clearly falls outside the topics of interest or matches an excluded topic — same filtering logic as Phase 4 in the meetings pipeline.
- [ ] AC-3.7: The D3 LLM prompt also includes the topics of interest so the LLM focuses enrichment on content relevant to those topics and can skip irrelevant linked documents.

---

### US-4: Enrich each matching decision from its detail page and relevant documents

**As a** council researcher
**I want to** the app to fetch each matching decision's detail page and its relevant supporting documents (excluding drawings and non-textual files)
**So that** Phase D4 receives sufficient decision text without wasting tokens or time on non-textual attachments

**Acceptance Criteria:**
- [ ] AC-4.1: For each decision returned by D2, D3 fetches the `ieDecisionDetails.aspx?ID=<ref>` detail page.
- [ ] AC-4.2: D3 extracts: decision title, decision maker name/role, decision date, decision body text, and URLs of linked documents.
- [ ] AC-4.3: The LLM in D3 selects which linked documents to fetch based on their title/description; it skips documents identified as drawings, maps, plans, or images (e.g. filenames or link text containing "drawing", "plan", "map", "layout", "elevation", "figure").
- [ ] AC-4.4: D3 fetches each selected document up to the configurable max-iterations limit (default 5) and incorporates extracted text into the enriched extract.
- [ ] AC-4.5: The enriched extract for each decision is structured to be compatible with Phase 6 (`AnalyzeExtractPhase`) input — same format as the `TriagedItem` output from Phase 5.
- [ ] AC-4.6: D3 calls `webScraper.releaseDocument(url)` in a `finally` block for each PDF fetched, to free PDF cache memory (consistent with Phase 5 pattern).
- [ ] AC-4.7: If the detail page fetch fails (HTTP error or timeout), D3 logs a warning for that decision and skips it; other decisions continue processing.

---

### US-5: Produce `Scheme` objects with the same structure as meeting-based schemes

**As a** council researcher
**I want to** decision-derived `Scheme` objects to use the same fields as meeting-derived ones
**So that** downstream output processing (file writing, logging) works without changes

**Acceptance Criteria:**
- [ ] AC-5.1: `Scheme.meetingDate` is populated with the decision date from the detail page.
- [ ] AC-5.2: `Scheme.committeeName` is populated with the decision maker name/role.
- [ ] AC-5.3: `Scheme.agendaUrl` is populated with the `ieDecisionDetails.aspx` URL for the decision.
- [ ] AC-5.4: `Scheme` data class is not modified; field mapping is applied in the orchestrator/phase code, not in the data class.
- [ ] AC-5.5: Phase D4 (`AnalyzeExtractPhase`) is invoked unchanged; no modifications to its prompt, input handling, or output parsing.

---

### US-6: Existing meetings-mode councils are unaffected

**As a** council researcher
**I want to** all existing councils configured without `mode` (or with `mode: meetings`) to continue working exactly as before
**So that** adding the decisions pipeline introduces zero regression

**Acceptance Criteria:**
- [ ] AC-6.1: All existing JUnit 5 tests pass after the decisions pipeline is added (`./gradlew test`). The one permitted modification is updating `OrchestratorTest.kt` to rename the `siteUrl` constructor argument to `meetingsUrl` (a mechanical rename, not a behavioural change).
- [ ] AC-6.2: No changes are made to Phase 1–6 class files, their prompts, or their `LlmResponse` variants.
- [ ] AC-6.3: `CouncilConfig` fields for meetings mode retain their existing semantics. `siteUrl` is renamed to `meetingsUrl` (String?, nullable, default null) to mirror `decisionsUrl`; the YAML key changes accordingly and existing config files must be migrated. `committees` is unchanged.
- [ ] AC-6.4: A config file with no `mode` field parses identically to one with `mode: meetings` and runs the existing pipeline.

---

## Functional Requirements

| ID | Requirement | Priority | Acceptance Criteria |
|----|-------------|----------|---------------------|
| FR-1 | `CouncilConfig` gains a `mode` field (`String`, default `"meetings"`) | High | Existing configs without `mode` parse without error; `mode: decisions` selects decisions pipeline |
| FR-2 | `CouncilConfig` gains a `decisionsUrl` field (`String?`, nullable) | High | Populated from YAML; null for meetings-mode councils |
| FR-3 | `CouncilConfig` gains a `decisionMakers` field (`List<String>`, default empty list) | High | Populated from YAML; used by D2/D3 for filtering |
| FR-4 | App fails fast at startup if `mode: decisions` and `decisionsUrl` is null or blank | High | Error names the offending council; no mid-pipeline crash |
| FR-5 | App fails fast at startup if `mode: decisions` and `decisionMakers` is empty | High | Error names the offending council |
| FR-6 | `Orchestrator.processCouncil()` dispatches to decisions pipeline when `mode == "decisions"`, meetings pipeline otherwise | High | Both pipelines reachable; dispatch testable with unit test |
| FR-7 | New `FindDecisionsPhase` (D2) takes the starting `decisionsUrl`, iterates listing pages, returns list of `(title, decisionDate, detailUrl, decisionMaker?)` for matching decisions | High | Returns only decisions matching `decisionMakers` within date range |
| FR-8 | D2 uses its own iteration loop with configurable max-iterations (default 50, `DEFAULT_D2_MAX_ITERATIONS`) | High | Warning logged on cap hit; no crash |
| FR-9 | D2 LLM prompt includes the `decisionMakers` list AND the `TOPICS`/`EXCLUDED_TOPICS` from `Prompts.kt`; LLM filters by decision maker (case-insensitive substring) AND topic relevance | High | Decisions by wrong maker or clearly off-topic excluded from returned list |
| FR-10 | New `EnrichDecisionPhase` (D3) fetches `ieDecisionDetails.aspx` and selectively follows linked documents, returning an enriched extract | High | Output structurally compatible with Phase 6 input |
| FR-11 | D3 LLM skips linked documents identified as drawings, maps, plans, or images based on link title/filename | High | Non-textual attachments not fetched; tokens not wasted |
| FR-12 | D3 max iterations defaults to 5; constant defined in phase class | Medium | Consistent with Phase 5 pattern |
| FR-13 | D3 calls `webScraper.releaseDocument(url)` in `finally` block for each PDF fetched | High | Memory not leaked; consistent with Phase 5 |
| FR-14 | New `LlmResponse` variants (e.g. `DecisionsFound`, `DecisionDetailExtracted`) added to sealed interface with correct JSON `"type"` discriminators | High | Deserializes correctly; corresponding branches added to `resolveUrls()` exhaustive `when` |
| FR-15 | `Scheme` field mapping: `meetingDate`=decision date, `committeeName`=decision maker, `agendaUrl`=detail page URL; applied in orchestrator/phase code | High | `Scheme` data class unmodified |
| FR-16 | New phase singletons registered in Koin `orchestratorModule` in `Main.kt` | High | App starts without DI errors when a decisions-mode council is present |
| FR-17 | New prompts for D2 and D3 added to `Prompts.kt` using `SplitPrompt(system, user)` with `CacheControlEphemeral` on system part | Medium | Prompt caching active for repeated D2 iterations |
| FR-18 | D2 and D3 use the Haiku light model constant from `Phase.kt` | Medium | Consistent with phases 1–5 model selection |
| FR-19 | D3 LLM prompt includes the `TOPICS`/`EXCLUDED_TOPICS` lists so the LLM can focus enrichment on relevant content and skip off-topic linked documents | High | Consistent with meetings pipeline; topics context present in D3 system prompt |

---

## Non-Functional Requirements

| ID | Requirement | Metric | Target |
|----|-------------|--------|--------|
| NFR-1 | No regression on existing meetings pipeline | All existing JUnit 5 tests pass | `./gradlew test` passes with zero failures after decisions pipeline added |
| NFR-2 | D2 max iterations constant | `DEFAULT_D2_MAX_ITERATIONS = 50` defined in `Phase.kt` | Default 50; no per-council config override |
| NFR-3 | D3 max iterations configurable | Constant in phase class | Default 5; same as Phase 5 |
| NFR-4 | Scope: ModernGov only | Only `mgDelegatedDecisions.aspx` format targeted | Non-ModernGov decision registers not in scope |
| NFR-5 | Both modes co-exist in same execution | No mutual exclusion enforced | Mixed-mode config file runs all councils without error |
| NFR-6 | Memory: PDF cache released per decision | `releaseDocument()` called in `finally` | No unbounded PDF cache growth across decisions within a run |

---

## Config Schema Change

Full config shape with a decisions-mode council alongside an existing meetings-mode council:

```yaml
# Decisions-mode council (new)
- name: Westminster
  mode: decisions
  decisionsUrl: https://westminster.moderngov.co.uk/mgDelegatedDecisions.aspx?bcr=1&DM=0&DS=2&K=0&V=0
  decisionMakers:
    - Cabinet Member for Streets
  dateFrom: "2025-01-01"
  dateTo: "2025-12-31"

# Meetings-mode council (meetingsUrl replaces siteUrl)
- name: Barnet
  meetingsUrl: https://barnet.moderngov.co.uk
  committees:
    - Planning Committee
    - Transport Committee
  dateFrom: "2025-01-01"
  dateTo: "2025-12-31"
```

**Field summary for `CouncilConfig`:**

| Field | Type | Default | Meetings | Decisions |
|-------|------|---------|----------|-----------|
| `name` | String | — | Required | Required |
| `meetingsUrl` | String? | null | Required | Ignored |
| `mode` | String | `"meetings"` | Optional | Required (`"decisions"`) |
| `decisionsUrl` | String? | null | Ignored | Required |
| `committees` | List\<String\> | `[]` | Required (phase 1) | Ignored |
| `decisionMakers` | List\<String\> | `[]` | Ignored | Required (min 1) |
| `dateFrom` | String? | null | Optional | Optional |
| `dateTo` | String? | null | Optional | Optional |
| `d2MaxIterations` | — | — | — | Not in config; constant `DEFAULT_D2_MAX_ITERATIONS = 50` in `Phase.kt` |

---

## Out of Scope

- Navigation to discover `decisionsUrl` (it is mandatory in config; no Phase D1)
- Non-ModernGov council decision registers (bespoke formats)
- `DM` URL parameter ID discovery or pre-filtering at URL level
- `mgofficerdecisions.aspx` endpoint (may be structurally identical, but not in scope for this iteration)
- Changes to Phase 1–6 class files, prompts, or their `LlmResponse` variants
- `Scheme` data class modification
- Parallel or multi-threaded execution across decisions (sequential, same as existing pipeline)

---

## Dependencies

| Dependency | Nature | Notes |
|------------|--------|-------|
| `AnalyzeExtractPhase` (Phase 6) | Shared reuse as D4 | No changes permitted |
| `CouncilConfig` data class | Extension | Add `mode`, `decisionsUrl`, `decisionMakers`, `d2MaxIterations` |
| `Orchestrator` | Dispatch branch | `processCouncil()` forks on `council.mode` |
| `LlmResponse` sealed interface | Extension | New variants + `resolveUrls()` branches |
| `Prompts.kt` | Extension | New `SplitPrompt` builders for D2 and D3 |
| Koin `orchestratorModule` in `Main.kt` | Extension | Register new phase singletons |
| `WebScraper.releaseDocument()` | Existing API | Called by D3 in `finally` block per PDF |

---

## Success Criteria

- A Westminster council run with `mode: decisions`, `decisionsUrl: https://westminster.moderngov.co.uk/mgDelegatedDecisions.aspx?bcr=1&DM=0&DS=2&K=0&V=0`, and `decisionMakers: [Cabinet Member for Streets]` produces at least one `Scheme` object with a populated `meetingDate`, `committeeName` matching "Cabinet Member for Streets", and `agendaUrl` pointing to an `ieDecisionDetails.aspx` URL.
- `./gradlew test` passes with zero failures after all changes (no regression).
- A config file containing both a decisions-mode and a meetings-mode council runs both councils end-to-end in a single execution.
- D3 does not fetch linked documents identified as drawings, maps, or plans; this is observable in logs.
- D2 with a 12-month date range and default max iterations does not crash; if the full range is not covered it logs a warning naming the council and the last date window reached.

---

## Unresolved Questions

- **Decision maker visibility in listing**: `DS=3` (details view) may expose the decision maker column in the listing table, enabling D2 to filter without visiting detail pages. Should the app always request `DS=3` when constructing the listing URL, or leave it to the LLM? Resolving this could reduce D3 calls significantly.
- **Date window construction**: Should D2 construct its own date windows from `dateFrom`/`dateTo` (fixed-size slices) rather than following "Earlier"/"Later" links? Constructed windows would be more predictable and easier to bound, but require parsing the `DR` URL parameter format.
- **`d2MaxIterations` placement**: Resolved — code constant in `Phase.kt`, not a config field.
- **Document skipping heuristic**: The LLM uses link title/filename to identify drawings. Should there be a configurable blocklist of keywords (e.g. `["drawing", "plan", "map", "elevation", "figure"]`) or is LLM judgement sufficient?

---

## Next Steps

1. Approve these requirements (or request changes).
2. Proceed to design phase: data class changes, new phase class signatures, `LlmResponse` variant definitions, `resolveUrls()` additions, Koin wiring.
3. Implement D2 (`FindDecisionsPhase`) with unit tests using `MockLlmClient`.
4. Implement D3 (`EnrichDecisionPhase`) with unit tests.
5. Update `Orchestrator` dispatch and integration-test with both modes in a single config.
