---
spec: decisions-page
phase: tasks
created: 2026-04-13
---

# Tasks: Decisions Page Pipeline

## Phase 1: TDD Implementation

Focus: Test-driven implementation. Write test and implementation together per component.

---

- [x] 1.1 Add `DEFAULT_D2_MAX_ITERATIONS` constant + update `AppConfig` + migrate configs

  - **Do**:
    1. In `Phase.kt`, add `const val DEFAULT_D2_MAX_ITERATIONS = 50` after `DEFAULT_TRIAGE_MAX_ITERATIONS`
    2. In `AppConfig.kt`, replace `CouncilConfig` with the new shape:
       - Rename `siteUrl: String` → `meetingsUrl: String? = null`
       - Add `mode: String = "meetings"`, `decisionsUrl: String? = null`, `decisionMakers: List<String> = emptyList()`
       - Keep `committees`, `dateFrom`, `dateTo` unchanged
    3. Rename `siteUrl:` → `meetingsUrl:` in `config.yaml` (if the file exists in the repo)
    4. Rename `siteUrl:` → `meetingsUrl:` in `config.example.yaml`; add example decisions-mode council block (see design.md Config Schema)
  - **Files**:
    - `src/main/kotlin/orchestrator/phase/Phase.kt`
    - `src/main/kotlin/config/AppConfig.kt`
    - `config.yaml` (only if present)
    - `config.example.yaml`
  - **Done when**: `./gradlew build` passes; `CouncilConfig` has all 8 fields; `DEFAULT_D2_MAX_ITERATIONS` is 50
  - **Verify**: `./gradlew build 2>&1 | tail -5`
  - **Commit**: `feat(config): rename siteUrl to meetingsUrl, add mode/decisionsUrl/decisionMakers fields`
  - _Requirements: FR-1, FR-2, FR-3, AC-1.1, AC-1.2, AC-6.3_

---

- [x] 1.2 Add `DecisionEntry` + three new `LlmResponse` variants + extend `resolveUrls()`

  - **Do**:
    1. In `LlmResponse.kt`, add `DecisionEntry` data class (fields: `title`, `decisionDate`, `detailUrl`, `decisionMaker?`) after the existing top-level data classes
    2. Add three new sealed variants inside the `LlmResponse` sealed interface:
       - `DecisionsPageScanned(decisions: List<DecisionEntry>, nextUrl: String? = null)` with `@SerialName("decisions_page_scanned")`
       - `DecisionFetch(urls: List<String>, extract: TriagedItem? = null, reason: String)` with `@SerialName("decision_fetch")`
       - `DecisionEnriched(item: TriagedItem, decisionMaker: String? = null)` with `@SerialName("decision_enriched")`
    3. Extend the exhaustive `when` in `resolveUrls()` with three new branches (see design.md resolveUrls additions)
  - **Files**: `src/main/kotlin/orchestrator/LlmResponse.kt`
  - **Done when**: `./gradlew build` passes (exhaustive `when` enforces all branches); all three variants present with correct `@SerialName` annotations
  - **Verify**: `./gradlew build 2>&1 | tail -5`
  - **Commit**: `feat(llm): add DecisionEntry and decisions pipeline LlmResponse variants`
  - _Requirements: FR-14, AC-5.4_

---

- [ ] 1.3 [VERIFY] Quality checkpoint after data/model changes

  - **Do**: Run full build+test
  - **Verify**: `./gradlew build 2>&1 | tail -10`
  - **Done when**: Build succeeds, zero test failures
  - **Commit**: `chore(build): pass quality checkpoint after model changes` (only if fixes needed)

---

- [x] 1.4 Add `buildFindDecisionsPrompt()` and `buildEnrichDecisionPrompt()` to `Prompts.kt`

  - **Do**:
    1. Add `buildFindDecisionsPrompt(decisionMakers: List<String>, dateFrom: String, dateTo: String, pageContent: String): SplitPrompt`
       - System part: static instructions for scanning a ModernGov decisions listing page; include `TOPICS_STRING` and `EXCLUDED_TOPICS_STRING`; case-insensitive substring matching rules for decision makers; date range filtering; `nextUrl` pagination rules; `decisions_page_scanned` JSON schema (see design.md)
       - User part: dynamic — decision makers list, date range, `pageContent`
    2. Add `buildEnrichDecisionPrompt(decision: DecisionEntry, currentExtract: TriagedItem?, pageContent: String): SplitPrompt`
       - System part: static instructions for extracting decision detail; include `TOPICS_STRING` / `EXCLUDED_TOPICS_STRING`; fields to extract; document-skipping rule (drawings/plans/maps); AC-3.3 instruction to always return decision maker name exactly as found; `decision_fetch` and `decision_enriched` JSON schemas (see design.md)
       - User part: dynamic — decision title, detail URL, optional accumulated extract, `pageContent`
  - **Files**: `src/main/kotlin/orchestrator/Prompts.kt`
  - **Done when**: Both functions exist, compile, and return `SplitPrompt`; system parts reference `TOPICS_STRING` / `EXCLUDED_TOPICS_STRING`
  - **Verify**: `./gradlew build 2>&1 | tail -5`
  - **Commit**: `feat(prompts): add buildFindDecisionsPrompt and buildEnrichDecisionPrompt`
  - _Requirements: FR-9, FR-17, FR-18, FR-19, AC-3.6, AC-3.7_

---

- [x] 1.5 Create `FindDecisionsPhase` with tests

  - **Do**:
    1. Create `FindDecisionsPhase.kt`:
       - `data class FindDecisionsInput(decisionsUrl, decisionMakers, dateFrom, dateTo)`
       - Class extends `BasePhase<FindDecisionsInput, List<DecisionEntry>>`; `override val name = "Find decisions"`
       - Constructor: `webScraper`, `llmClient`, `lightModel: String = DEFAULT_LIGHT_MODEL`, `maxIterations: Int = DEFAULT_D2_MAX_ITERATIONS`
       - `doExecute()` implements own accumulation loop (see design.md loop structure)
       - Returns `emptyList()` when no matches (orchestrator handles this); returns `null` only on parse failure or unexpected response type
       - Logs `WARN` when max iterations reached mid-range (see design.md wording)
    2. Create `FindDecisionsPhaseTest.kt` with 6 tests (same `MockLlmClient` + Ktor `MockEngine` pattern as `OrchestratorTest`):
       - `returns empty list when no decisions match`
       - `returns decisions from single page`
       - `accumulates decisions across multiple pages`
       - `stops at max iterations and returns partial results`
       - `returns null on unexpected response type`
       - `returns null on LLM parse failure`
  - **Files**:
    - `src/main/kotlin/orchestrator/phase/FindDecisionsPhase.kt`
    - `src/test/kotlin/orchestrator/FindDecisionsPhaseTest.kt`
  - **Done when**: All 6 tests pass; `./gradlew test --tests "orchestrator.FindDecisionsPhaseTest"` exits 0
  - **Verify**: `./gradlew test --tests "orchestrator.FindDecisionsPhaseTest" 2>&1 | tail -15`
  - **Commit**: `feat(phase): add FindDecisionsPhase with accumulation loop and unit tests`
  - _Requirements: FR-7, FR-8, FR-9, AC-2.1, AC-2.2, AC-2.3, AC-2.4, AC-2.5, AC-3.1, AC-3.5_

---

- [ ] 1.6 Create `EnrichDecisionPhase` with tests

  - **Do**:
    1. Create `EnrichDecisionPhase.kt`:
       - `data class EnrichDecisionInput(decision: DecisionEntry)`
       - Class extends `BasePhase<EnrichDecisionInput, LlmResponse.DecisionEnriched>`; `override val name = "Enrich decision"`
       - Constructor: `webScraper`, `llmClient`, `lightModel: String = DEFAULT_LIGHT_MODEL`, `maxIterations: Int = DEFAULT_TRIAGE_MAX_ITERATIONS`
       - `doExecute()` implements fetch loop (see design.md loop structure); returns `null` after max iterations
       - `releaseDocument()` NOT called explicitly — `BasePhase.execute()` handles it via `trackedUrls`
    2. Create `EnrichDecisionPhaseTest.kt` with 6 tests:
       - `returns DecisionEnriched with item and decisionMaker on immediate decision_enriched response`
       - `fetches additional document and returns enriched on second call`
       - `carries partial extract forward into next fetch prompt`
       - `returns null when detail page fetch fails on first iteration`
       - `returns null when max iterations reached without decision_enriched`
       - `returns null on unexpected response type`
  - **Files**:
    - `src/main/kotlin/orchestrator/phase/EnrichDecisionPhase.kt`
    - `src/test/kotlin/orchestrator/EnrichDecisionPhaseTest.kt`
  - **Done when**: All 6 tests pass; `./gradlew test --tests "orchestrator.EnrichDecisionPhaseTest"` exits 0
  - **Verify**: `./gradlew test --tests "orchestrator.EnrichDecisionPhaseTest" 2>&1 | tail -15`
  - **Commit**: `feat(phase): add EnrichDecisionPhase with fetch loop and unit tests`
  - _Requirements: FR-10, FR-11, FR-12, FR-13, AC-3.3, AC-3.4, AC-4.1, AC-4.2, AC-4.3, AC-4.4, AC-4.5, AC-4.6, AC-4.7_

---

- [ ] 1.7 [VERIFY] Quality checkpoint after new phases

  - **Do**: Run full build+test
  - **Verify**: `./gradlew build 2>&1 | tail -10`
  - **Done when**: Build succeeds, zero test failures
  - **Commit**: `chore(build): pass quality checkpoint after phase implementations` (only if fixes needed)

---

- [ ] 1.8 Update `Orchestrator.kt` + fix `OrchestratorTest.kt` regression

  - **Do**:
    1. In `Orchestrator.kt`:
       - Add `findDecisionsPhase: FindDecisionsPhase` and `enrichDecisionPhase: EnrichDecisionPhase` as new constructor params (before `resultProcessor`)
       - Rename existing `processCouncil()` body to `private suspend fun processCouncilMeetings(council: CouncilConfig)`; change `council.siteUrl` → `council.meetingsUrl!!` inside it
       - Add new `suspend fun processCouncil(council: CouncilConfig)` dispatcher (see design.md)
       - Add `private suspend fun processCouncilDecisions(council: CouncilConfig)` (full implementation from design.md, including `decisionMakerLabel` fallback chain and synthetic `Meeting`)
    2. In `OrchestratorTest.kt`, apply two mechanical renames only:
       - Rename `siteUrl = "..."` → `meetingsUrl = "..."` in every `CouncilConfig(...)` constructor call
       - Update `makeOrchestrator()` to add two new stub params for `findDecisionsPhase` and `enrichDecisionPhase` — create minimal no-op stubs inline (e.g., anonymous `object` instances that return `emptyList()` / `null`)
  - **Files**:
    - `src/main/kotlin/orchestrator/Orchestrator.kt`
    - `src/test/kotlin/orchestrator/OrchestratorTest.kt`
  - **Done when**: `./gradlew test --tests "orchestrator.OrchestratorTest"` passes with zero failures (no behavioural regressions)
  - **Verify**: `./gradlew test --tests "orchestrator.OrchestratorTest" 2>&1 | tail -15`
  - **Commit**: `feat(orchestrator): add decisions pipeline dispatch and processCouncilDecisions`
  - _Requirements: FR-6, FR-7, FR-15, AC-1.1, AC-1.2, AC-5.1, AC-5.2, AC-5.3, AC-5.5, AC-6.1, AC-6.3_

---

- [ ] 1.9 Add startup validation + Koin wiring in `Main.kt`

  - **Do**:
    1. After `loadConfig()` in `main()`, insert the `validationErrors` block from design.md:
       - For `mode == "decisions"`: error if `decisionsUrl` is null/blank, error if `decisionMakers` is empty
       - For meetings mode: error if `meetingsUrl` is null/blank
       - Log each error; return early (fail fast) if any errors present
    2. In `orchestratorModule`, add:
       - `single { FindDecisionsPhase(get(), get()) }`
       - `single { EnrichDecisionPhase(get(), get()) }`
    3. Update the `Orchestrator(...)` call to pass 9 arguments in order: `findCommitteePages, findMeetings, findAgenda, identifyAgendaItems, enrichAgendaItems, analyzeExtract, findDecisions, enrichDecision, resultProcessor`
  - **Files**: `src/main/kotlin/Main.kt`
  - **Done when**: `./gradlew build` passes; Koin wiring compiles; startup validation logic is present
  - **Verify**: `./gradlew build 2>&1 | tail -5`
  - **Commit**: `feat(main): add startup validation and register FindDecisionsPhase/EnrichDecisionPhase in Koin`
  - _Requirements: FR-4, FR-5, FR-16, AC-1.3, AC-1.4_

---

## Phase 2: Integration Testing

Focus: Orchestrator-level tests for decisions pipeline dispatch and field mapping.

---

- [ ] 2.1 Write `OrchestratorDecisionsTest` — all 7 test cases

  - **Do**:
    Create `OrchestratorDecisionsTest.kt` with 7 tests (does NOT modify `OrchestratorTest`):
    1. `dispatches to decisions pipeline for mode=decisions` — `findDecisionsPhase` mock called; meetings phases not called
    2. `dispatches to meetings pipeline for mode=meetings` — meetings phase called; decisions phases not called
    3. `dispatches to meetings pipeline when mode field absent` — default `"meetings"` → meetings pipeline
    4. `decisions pipeline produces schemes with correct field mapping` — `Scheme.meetingDate`, `.committeeName`, `.agendaUrl` populated correctly from `DecisionEntry` fields
    5. `decisions pipeline uses decisionMaker from DecisionEnriched for committeeName fallback chain` — `enriched.decisionMaker` non-null → used; falls back to `decision.decisionMaker` when null
    6. `decisions pipeline skips decisions where enrich phase returns null` — enrich mock returns `null` for one decision; others processed
    7. `decisions pipeline logs info and skips when find phase returns empty list` — empty list from find phase → info log, no processor call

    Construct `Orchestrator` directly with controlled `MockLlmClient` + `MockEngine` phase instances.
  - **Files**: `src/test/kotlin/orchestrator/OrchestratorDecisionsTest.kt`
  - **Done when**: All 7 tests pass; `./gradlew test --tests "orchestrator.OrchestratorDecisionsTest"` exits 0
  - **Verify**: `./gradlew test --tests "orchestrator.OrchestratorDecisionsTest" 2>&1 | tail -15`
  - **Commit**: `test(orchestrator): add OrchestratorDecisionsTest for dispatch and field mapping`
  - _Requirements: FR-6, FR-15, AC-1.1, AC-1.2, AC-1.5, AC-3.5, AC-5.1, AC-5.2, AC-5.3, AC-6.1_

---

- [ ] 2.2 [VERIFY] Full regression check — all tests

  - **Do**: Run full test suite
  - **Verify**: `./gradlew test 2>&1 | tail -20`
  - **Done when**: Zero failures across all test classes including `OrchestratorTest`, `FindDecisionsPhaseTest`, `EnrichDecisionPhaseTest`, `OrchestratorDecisionsTest`
  - **Commit**: `chore(test): pass full regression check` (only if fixes needed)

---

## Phase 3: Quality Gates

---

- [ ] V4 [VERIFY] Full local CI: build + all tests

  - **Do**: Run complete build + test suite
  - **Verify**: `./gradlew build 2>&1 | tail -20`
  - **Done when**: `BUILD SUCCESSFUL`, zero test failures
  - **Commit**: `chore(build): pass full local build` (if fixes needed)

---

- [ ] V5 [VERIFY] AC checklist

  - **Do**: For each AC in requirements.md, verify by grepping code and/or running tests:
    - AC-1.1–1.5: `grep -n "meetingsUrl\|decisionsUrl\|decisionMakers\|mode.*meetings" src/main/kotlin/config/AppConfig.kt`
    - AC-1.3–1.4: `grep -n "validationErrors\|isNullOrBlank\|isEmpty" src/main/kotlin/Main.kt`
    - AC-2.1–2.5: `./gradlew test --tests "orchestrator.FindDecisionsPhaseTest"`
    - AC-4.1–4.7: `./gradlew test --tests "orchestrator.EnrichDecisionPhaseTest"`
    - AC-5.1–5.3: `./gradlew test --tests "orchestrator.OrchestratorDecisionsTest"`
    - AC-6.1–6.4: `./gradlew test --tests "orchestrator.OrchestratorTest"`
  - **Verify**: `./gradlew test 2>&1 | grep -E "BUILD|tests|failures|errors"`
  - **Done when**: All acceptance criteria confirmed met via automated checks
  - **Commit**: None

---

- [ ] VE1 [VERIFY] E2E build check: all new tests compile and pass

  - **Do**: Run the full build which compiles + runs all tests
  - **Verify**: `./gradlew build 2>&1 | grep -E "BUILD|FindDecisions|EnrichDecision|OrchestratorDecisions"`
  - **Done when**: `BUILD SUCCESSFUL`; output shows new test classes executed
  - **Commit**: None

- [ ] VE2 [VERIFY] E2E cleanup

  - **Do**: No server or artifacts to clean up for this CLI/JVM project
  - **Verify**: `echo VE2_PASS`
  - **Done when**: Always passes
  - **Commit**: None

---

## Phase 4: PR Lifecycle

---

- [ ] 4.1 Create PR

  - **Do**:
    1. Verify on feature branch: `git branch --show-current` (must not be `main`)
    2. Push branch: `git push -u origin $(git branch --show-current)`
    3. Create PR: `gh pr create --title "Add decisions-page pipeline (D2/D3 phases)" --body "$(cat <<'EOF'
## Summary
- Adds decisions pipeline mode: new FindDecisionsPhase (D2) and EnrichDecisionPhase (D3) phases
- Renames CouncilConfig.siteUrl → meetingsUrl; adds mode, decisionsUrl, decisionMakers fields
- Orchestrator.processCouncil() dispatches to decisions or meetings pipeline per council
- Startup validation fails fast if mode=decisions config is missing decisionsUrl or decisionMakers

## Test plan
- [ ] FindDecisionsPhaseTest (6 tests): accumulation loop, pagination, error cases
- [ ] EnrichDecisionPhaseTest (6 tests): fetch loop, extract forwarding, error cases
- [ ] OrchestratorDecisionsTest (7 tests): dispatch, field mapping, skip/fallback behaviour
- [ ] OrchestratorTest: all existing tests still pass (mechanical renames only)
- [ ] ./gradlew build passes with zero failures

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"`
  - **Verify**: `gh pr view --json url -q .url`
  - **Done when**: PR created; URL returned
  - **Commit**: None

---

- [ ] 4.2 Resolve review comments (if any)

  - **Do**:
    1. Check for review comments: `gh pr view --comments`
    2. Address each comment with targeted code changes
    3. Push fixes: `git push`
    4. Re-verify build: `./gradlew build 2>&1 | tail -5`
  - **Verify**: `gh pr view --json reviewDecision -q .reviewDecision`
  - **Done when**: No unresolved review threads; `./gradlew build` still passes
  - **Commit**: `fix(decisions): address review comments`

---

**Total tasks**: 16
