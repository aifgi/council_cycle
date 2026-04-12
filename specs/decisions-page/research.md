---
spec: decisions-page
phase: research
created: 2026-04-12
---

# Research: decisions-page

## Executive Summary

ModernGov's `mgDelegatedDecisions.aspx` is a widely-used, structurally consistent platform across UK councils. The decisions flow is substantially simpler than the existing meeting pipeline (phases 1–5 collapse to roughly 2–3 phases), but requires: (a) iterating over paginated date windows on the listing page to collect relevant decisions, (b) filtering decisions by decision maker before diving deeper, and (c) fetching individual decision detail pages and linked documents. The recommended approach is a **parallel pipeline branch that co-exists with the existing meetings pipeline**: a per-council config flag selects which pipeline runs, and Phase 6 (AnalyzeExtract) is shared by both. The existing meetings pipeline is preserved unchanged.

---

## External Research

### ModernGov Decisions Page Structure

Observed from Kingston (`moderngov.kingston.gov.uk`), Rotherham (`moderngov.rotherham.gov.uk`), Staffordshire, Barnet, Doncaster, Westminster:

**Listing page (`mgDelegatedDecisions.aspx`):**
- Consistent URL pattern across all ModernGov councils
- Displays decisions in a table; ~175 decisions per date window (all loaded at once, no in-window pagination)
- Summary view columns: Title (linked), Publication date, Effective from, Call-in status
- Decision maker column is NOT shown in the summary table — only visible on the detail page
- Date window navigation: "Earlier" / "Later" buttons shift the `DR` parameter by a fixed interval (~14 days)
- `DR` format: `DD%2fMM%2fYYYY-DD%2fMM%2fYYYY` (URL-encoded slashes)
- `DM` parameter: `0` = no filter (all decision makers); numeric or alphanumeric values filter to specific makers (e.g. `DM=13197PPL`)
- `K=1` = key decisions only; `K=0` = all
- `DS=2` = summary view; `DS=3` = details view (more columns visible)

**Key URL parameters:**

| Parameter | Meaning | Values seen |
|-----------|---------|-------------|
| `DR` | Date range | `DD/MM/YYYY-DD/MM/YYYY` |
| `DM` | Decision maker filter | `0` (all), numeric/alphanumeric ID |
| `K` | Key decisions only | `0` / `1` |
| `DS` | Display mode | `2` (summary), `3` (details) |
| `RP` | Result page | `0` (no in-window pagination observed) |
| `META` | Page type | `mgdelegateddecisions` / `mgofficerdecisions` |
| `V` | View | `0` / `1` |
| `Next=true` | Navigation flag | Present when using Earlier/Later |

**Decision detail page (`ieDecisionDetails.aspx?ID=<ref>`):**
- Fields: Title, Decision Maker (role/name), Decision Status, Key Decision flag, Call-in status, Publication Date, Decision Date
- Decision body text (1–3 paragraphs describing what was approved)
- Supporting documents: 1–3 PDFs, each linked directly (format: `{description}.pdf` or named document)
- Issue History link for audit trail
- No agenda pack — documents are specific to this decision only

### Pagination Reality

Each date window contains **all** decisions for that period in one page load — no row-level pagination within a window. The "Earlier"/"Later" navigation shifts the window. A council's decision register for 12 months requires walking ~26 two-week windows (or fewer if windows are larger). However, since the app already has a `dateFrom`/`dateTo` config, we need only walk windows that overlap the configured range.

### Decision Maker Filtering

The listing page `DM` parameter can pre-filter by decision maker ID. However:
- The IDs are council-specific numeric/alphanumeric values not known upfront
- The safer approach is: let the LLM read the listing page and filter by name/role, since the detail page always shows the decision maker clearly
- Alternatively, use `DS=3` (details view) which may surface more fields in the listing table

### Prior Art / Similar Approaches

- Some councils expose an `mgofficerdecisions.aspx` endpoint (distinct from `mgDelegatedDecisions`) — same structure, different `META` parameter
- The `DS=2` vs `DS=3` distinction matters: details view may include decision maker in the listing row, reducing need to visit each detail page individually to filter

---

## Codebase Analysis

### What Exists

**Config (`AppConfig.kt` / `CouncilConfig.kt`):**
```kotlin
data class CouncilConfig(
    val name: String,
    val siteUrl: String,
    val committees: List<String>,
    val dateFrom: String? = null,
    val dateTo: String? = null,
)
```
No mode flag. `committees` is currently used as both the filter criterion and the target for Phase 1 navigation. For decisions mode, the same list would serve as the "allowed decision makers" filter.

**Orchestrator (`Orchestrator.kt`):**
Single linear pipeline: phases 1→2→3→4→5→6, all hardwired. No branching. `processCouncil()` is the sole entry point.

**Phase architecture (`BasePhase`, `Phase<I,O>`):**
Well-abstracted. `navigationLoop()` handles fetch→LLM→follow with up to N iterations. Each phase is a stateless singleton injected via Koin. Adding new phases is straightforward.

**Existing phases mapping to decisions flow:**

| Existing Phase | Decisions Equivalent | Notes |
|---------------|---------------------|-------|
| Phase 1: Find committee pages | Phase D1: Navigate to decisions listing page | Much simpler — URL pattern is predictable |
| Phase 2: Find meetings | Phase D2: Enumerate decisions within date range | Different structure: iterate date windows, filter by decision maker |
| Phase 3: Find agenda | _(eliminated)_ | Decisions have no separate agenda doc |
| Phase 4: Identify agenda items | _(eliminated)_ | Each decision IS the item |
| Phase 5: Enrich agenda items | Phase D3: Fetch decision detail + documents | Simpler — 1 decision per iteration, 1-3 docs |
| Phase 6: Analyze extract | Phase D4 = Phase 6 unchanged | Same prompt, same output (`AgendaAnalyzed`) |

**LlmResponse sealed interface:**
Can be extended without breaking existing deserialization. New response types (e.g. `DecisionsFound`, `DecisionDetailExtracted`) slot in cleanly.

**`resolveUrls()`:**
New LlmResponse variants need corresponding `when` branches in `resolveUrls()` or they'll hit the exhaustive `when` and fail to compile — this is a safe guardrail.

**Koin DI (`Main.kt`):**
All phase singletons registered in `orchestratorModule`. New phases would be added here. `Orchestrator` constructor injection would need updating if a second orchestrator is introduced.

**ResultProcessor / Scheme:**
`Scheme` has `meetingDate`, `committeeName`, `agendaUrl` fields. For decisions mode: `meetingDate` → decision date, `committeeName` → decision maker, `agendaUrl` → decision detail page URL. No schema change needed.

### What Needs to Change

1. **`CouncilConfig`** — add `mode: String` or `decisionsUrl: String?` field (or both)
2. **`Orchestrator`** — branch on council mode, call either existing pipeline or new decisions pipeline
3. **New phases** — `FindDecisionsPhase` (D2: enumerate + filter) + `EnrichDecisionPhase` (D3: fetch detail page + docs)
4. **New LlmResponse variants** — `DecisionListScanned`, `DecisionDetailExtracted` (or reuse/adapt existing ones)
5. **New prompts** — in `Prompts.kt`
6. **`resolveUrls()`** — add cases for new response types
7. **Koin registration** — register new phases

---

## Architectural Options

### Option A: Parallel pipeline branch in `Orchestrator` (RECOMMENDED)

`CouncilConfig` gains a `mode` field (`meetings` | `decisions`). `Orchestrator.processCouncil()` dispatches to either the existing 6-phase meeting pipeline or a new 4-phase decisions pipeline. Both pipelines **co-exist permanently** in the codebase — a council in `config.yaml` with `mode: decisions` runs the decisions pipeline, any council without the flag (default) runs the existing meetings pipeline. Phase 6 (`AnalyzeExtractPhase`) is shared by both.

**Pros:**
- Existing meetings pipeline is entirely untouched — no regression risk
- Any council can be configured for either mode; both modes can run in the same execution
- Clean separation; easy to test each pipeline independently
- `CouncilConfig` `mode` field is self-documenting
- Shared Phase 6 avoids duplication of the most expensive LLM call

**Cons:**
- `Orchestrator` grows a dispatch branch
- Koin registration grows with new phase singletons
- Two pipeline paths to maintain long-term (acceptable given distinct domain logic)

### Option B: Replace phases 1-3 with decisions-oriented phases via config (NOT RECOMMENDED)

Keep the same 6-slot pipeline but allow per-council phase substitution. Phase 1 becomes "find decisions page", Phase 2 becomes "enumerate decisions", Phase 3 is skipped, etc.

**Pros:**
- Preserves the pipeline abstraction

**Cons:**
- The pipeline inputs/outputs are strongly typed; Phase 2 returns `List<Meeting>` which doesn't map cleanly to a list of decisions (decisions need different data)
- Phase 3 (agenda URL) is conceptually absent, requiring nullable gymnastics
- Forces decisions and meetings through the same data types — leaky abstraction

### Option C: Second `Orchestrator` class (`DecisionsOrchestrator`)

Separate orchestrator for decisions mode, injected conditionally.

**Pros:**
- No branching in a single class

**Cons:**
- Duplicates DI setup; two orchestrators need coordinating in `Main.kt`
- Overkill given the shared Phase 6

### Option D: LLM-driven single pipeline (LLM decides how to navigate)

Give Phase 1 both the `siteUrl` and a `decisions` hint; let the LLM figure out if it's a decisions page or meetings page and adjust.

**Pros:**
- Potentially handles edge cases automatically

**Cons:**
- Significantly more complex prompts; harder to test; less reliable
- The existing prompt structure is already tightly tuned for meetings; mixing concerns increases hallucination risk

---

## Feasibility Assessment

| Aspect | Assessment | Notes |
|--------|------------|-------|
| Technical viability | High | ModernGov structure is consistent; existing abstractions fit well |
| Effort estimate | M | ~4 new phases/classes, config change, 3-4 new prompts, Orchestrator branch |
| Risk: date window iteration | Medium | Need to walk multiple 14-day windows; max 5 iterations may be insufficient for 12-month range (26 windows) — needs configurable iteration limit or batching |
| Risk: decision maker filtering | Low-Medium | LLM filtering is reliable; DM URL param is an optional optimisation |
| Risk: detail page variability | Low | `ieDecisionDetails.aspx` is highly consistent across ModernGov councils |
| Risk: existing pipeline regression | Low | Option A keeps pipelines fully separate |

---

## Recommendations for Requirements

1. **Use Option A** (parallel pipeline branch, both modes co-existing permanently). The existing meetings pipeline stays entirely untouched. Councils using the new mode get the decisions pipeline; all existing councils default to meetings. This is the simplest architecture that adds capability without regression risk.

2. **Config shape** — add `mode` field to `CouncilConfig` (default `"meetings"` for backward compatibility) and optionally a `decisionsUrl` field to allow direct URL injection rather than relying on Phase 1 navigation:
   ```yaml
   - name: "Kingston"
     mode: decisions
     decisionsUrl: "https://moderngov.kingston.gov.uk/mgDelegatedDecisions.aspx"
     committees:
       - "Head of Spatial Planning"
       - "Cabinet Member for Transport"
     dateFrom: "2025-03-30"
     dateTo: "2026-04-13"
   ```

3. **Decisions pipeline phases:**
   - **D1: FindDecisionsPage** — navigate from `siteUrl` to the decisions listing URL (skippable if `decisionsUrl` is provided directly)
   - **D2: EnumerateDecisions** — iterate date windows over `dateFrom`–`dateTo`, collect decision list entries matching allowed decision makers; return list of `(title, date, detailUrl, decisionMaker)`
   - **D3: EnrichDecision** — fetch `ieDecisionDetails.aspx?ID=X`, then fetch linked PDFs; produce `TriagedItem` compatible with Phase 6 input
   - **D4: AnalyzeExtract** — unchanged Phase 6

4. **Date window iteration** — the `navigationLoop()` max-iterations cap of 5 is too low for 12-month date ranges (up to 26 two-week windows). D2 needs its own loop with a higher configurable limit (e.g. 50), or should batch the date range into fewer larger windows before iterating.

5. **Decision maker filtering** — use the `committees` config list as the allowed-decision-makers list. Filter should be applied by the LLM in D2 (matching by name/role substring), not solely by the `DM` URL parameter, since DM IDs are council-specific and unknown at config time. The DM parameter could be an optional optimisation for later.

6. **`Scheme` field mapping** — `meetingDate` = decision date, `committeeName` = decision maker name/role, `agendaUrl` = `ieDecisionDetails.aspx` URL. No schema change required.

7. **Reuse `EnrichAgendaItemsPhase` prompt structure** for D3 — the enrichment pattern (fetch detail page → optionally follow linked docs → produce extract) is identical to Phase 5. The prompt needs rewording but the iteration structure is the same.

---

## Open Questions

1. **Date window size** — the Kingston example uses ~14-day windows. Is this a ModernGov default? Does it vary? Should the app construct its own date windows (e.g. month-by-month) rather than following the "Earlier"/"Later" navigation?

2. **DM parameter discovery** — should the app try to discover a council's DM parameter ID (to pre-filter at URL level), or always rely on LLM-based filtering from the full listing? Discovering the ID would require an extra navigation step or council-specific config.

3. **Volume of decisions** — 175 decisions per 14-day window means the LLM receives a large page. Does `ContentExtractor` / `AnnotatedMarkdownConverter` produce output that fits in a single Haiku context window? May need to truncate or use `DS=3` (details view with decision maker column) to allow early filtering without reading all 175 titles.

4. **Non-ModernGov councils** — the issue mentions "decisions pages" generically. Is the scope limited to ModernGov (`mgDelegatedDecisions.aspx`) for now, or does it need to handle bespoke council decision registers too?

5. **`decisionsUrl` vs navigation** — should `decisionsUrl` in config be mandatory for decisions mode (simpler, reliable) or optional (app navigates to it from `siteUrl` via Phase D1)?

6. **Backward compat for `committees` field meaning** — in meetings mode, `committees` = committee names for Phase 1 lookup. In decisions mode, `committees` = allowed decision makers. Should these be separate config keys (`committees` vs `decisionMakers`) to avoid confusion?

7. **`mgofficerdecisions.aspx`** — some councils use a different endpoint for officer decisions. Is this in scope?

---

## Quality Commands

| Type | Command | Source |
|------|---------|--------|
| Build + Test | `./gradlew build` | build.gradle |
| Test only | `./gradlew test` | build.gradle |
| Single test class | `./gradlew test --tests "scraper.ContentExtractorTest"` | CLAUDE.md |
| Run app | `./gradlew run --args="config.yaml llm-credentials.txt"` | CLAUDE.md |
| Lint | Not found | No lint script |
| TypeCheck | Handled by Kotlin compiler in `build` | Gradle |

**Local CI**: `./gradlew build`

---

## Verification Tooling

No automated E2E tooling detected (JVM app, no web server, no Playwright/Cypress).

**Project Type**: CLI / JVM Application
**Verification Strategy**: `./gradlew build` to compile and run all JUnit 5 tests; manual run with a real config for integration verification.

---

## Sources

- [Kingston ModernGov decisions page](https://moderngov.kingston.gov.uk/mgDelegatedDecisions.aspx?&DR=30%2f03%2f2025-13%2f04%2f2026&RP=0&K=0&DM=0&HD=0&DS=2&Next=true&META=mgdelegateddecisions&V=0)
- [Kingston decision detail: Kingston Placemaking Panel](https://moderngov.kingston.gov.uk/ieDecisionDetails.aspx?ID=5528)
- [Kingston decision detail: Responsible Investment Policy](https://moderngov.kingston.gov.uk/ieDecisionDetails.aspx?ID=5524)
- [Rotherham officer decisions (DM-filtered URL)](https://moderngov.rotherham.gov.uk/mgDelegatedDecisions.aspx?XXR=0&RP=0&K=0&V=0&DM=13197PPL&HD=0&DS=2&META=mgofficerdecisions&bcr=1)
- [GitHub issue #27](https://github.com/aifgi/council_cycle/issues/27)
- `/Users/aifgi/src/council_cycle/src/main/kotlin/config/AppConfig.kt`
- `/Users/aifgi/src/council_cycle/src/main/kotlin/orchestrator/Orchestrator.kt`
- `/Users/aifgi/src/council_cycle/src/main/kotlin/orchestrator/LlmResponse.kt`
- `/Users/aifgi/src/council_cycle/src/main/kotlin/orchestrator/Prompts.kt`
- `/Users/aifgi/src/council_cycle/src/main/kotlin/orchestrator/phase/Phase.kt`
- `/Users/aifgi/src/council_cycle/src/main/kotlin/orchestrator/phase/EnrichAgendaItemsPhase.kt`
- `/Users/aifgi/src/council_cycle/src/main/kotlin/orchestrator/phase/AnalyzeExtractPhase.kt`
