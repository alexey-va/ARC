# Global Help Hub Implementation Plan

**Goal:** Build a compact dynamic ARC `/help` hub covering current tasks, activities, players, technology, settings and situational recovery.

**Architecture:** Keep Paper dialog assembly in focused controller methods while moving command availability, recommendation priority, player filtering and typed command construction into pure Kotlin models. Extend the existing gateway only for current-node state and delegate settlement mutation to Lands UI so land selection remains explicit.

**Tech Stack:** Kotlin 2.3, Java 25, Paper 1.21.11 native dialogs through arc-core 2.4.5, Kotest, MockBukkit.

**Spec:** `docs/superpowers/specs/2026-09-04-global-help-hub-design.md`

## Global Constraints

- Root contains exactly ten task-oriented destinations in two columns.
- Dynamic lists remain bounded and omit unavailable integrations.
- Player names, messages and payments use typed validated builders.
- Payment is confirmed before dispatch; settlement invite re-resolves the selected land.
- Player-facing text lives in `modules/help-center.yml` and has no bold button labels.
- Git publication and production activation remain separate gates.

---

### Task 1: Pure navigation and action model

**Files:**
- Modify: `src/main/kotlin/ru/arc/helpcenter/HelpCenterModel.kt`
- Test: `src/test/kotlin/ru/arc/helpcenter/HelpCenterPlannerTest.kt`

**Interfaces:**
- Produces: `HelpCenterFeature`, new `HelpCenterPage` values, `HelpCenterPlanner.recommendations`, `HelpCenterPlanner.players`, and typed player command builders.

- [ ] Add failing tests for root destinations, recommendation ordering, plugin filtering, player search and command input rejection.
- [ ] Run `./gradlew test --tests 'ru.arc.helpcenter.HelpCenterPlannerTest'` and confirm the new tests fail for missing behavior.
- [ ] Implement the minimum pure model and builders required by those tests.
- [ ] Rerun the focused test and confirm it passes.

### Task 2: Gateway state and settlement invite route

**Files:**
- Modify: `src/main/kotlin/ru/arc/helpcenter/HelpCenterGateway.kt`
- Modify: `src/main/kotlin/ru/arc/landsui/LandsUiController.kt`
- Modify: `src/main/kotlin/ru/arc/landsui/LandsUiModule.kt`
- Test: `src/test/kotlin/ru/arc/landsui/LandsUiModelTest.kt`

**Interfaces:**
- Consumes: typed player names from Task 1.
- Produces: feature availability, proxy-wide online-player snapshots from ARC's player manager, current chat mode, and `LandsUiModule.openInvite(player, targetName)`.

- [ ] Add a failing planner test proving invite candidates remain explicitly land-scoped.
- [ ] Run the focused Lands UI test and observe the missing planner behavior.
- [ ] Add the land chooser and fresh select-and-execute dispatch path.
- [ ] Rerun the focused Lands UI test.

### Task 3: Focused dialog controllers

**Files:**
- Create: `src/main/kotlin/ru/arc/helpcenter/HelpCenterCatalog.kt`
- Create: `src/main/kotlin/ru/arc/helpcenter/HelpCenterPlayerDialogs.kt`
- Modify: `src/main/kotlin/ru/arc/helpcenter/HelpCenterController.kt`
- Modify: `src/main/kotlin/ru/arc/helpcenter/HelpCenterModule.kt`
- Test: `src/test/kotlin/ru/arc/helpcenter/HelpCenterConfigTest.kt`

**Interfaces:**
- Consumes: gateway state and pure planners from Tasks 1-2.
- Produces: the ten-button root plus Now, Activities, Players, Technology, Settings and Recovery dialog flows.

- [ ] Add failing configuration and catalog tests for every page, command, tooltip and no-bold button contract.
- [ ] Run focused help-center tests and confirm failure from the missing keys/routes.
- [ ] Implement the catalog and focused dialog flows with bounded buttons and explicit back navigation.
- [ ] Rerun focused help-center tests.

### Task 4: Locale and visual catalog

**Files:**
- Modify: `src/main/resources/modules/help-center.yml`
- Modify: `visual-preview.yml`

**Interfaces:**
- Consumes: every visible key from Task 3.
- Produces: complete Russian copy and source-driven native-dialog preview variants.

- [ ] Add all new localized keys with stable semantic colors and non-bold buttons.
- [ ] Extend the manifest for populated, empty, error, payment confirmation and integration-filtered screens.
- [ ] Run `./scripts/render-visual-preview` and fix all unresolved keys, automatic wraps and visual hierarchy defects.

### Task 5: Full verification and publication

**Files:**
- Modify: `build.gradle.kts` only for the release version.

**Interfaces:**
- Produces: one verified ARC JAR and Git commit on the configured trunk.

- [ ] Run focused help-center and Lands UI tests.
- [ ] Run `./gradlew test shadowJar` without local integration tests.
- [ ] Run the complete visual preview and inspect the generated dialog pages.
- [ ] Review `git diff --check`, exact diff, archive identity and SHA-256.
- [ ] Bump the patch version, repeat the final package gate, commit owned paths and non-force push `origin/master`.
- [ ] Report production deployment and player smoke as not performed unless separately authorized.
