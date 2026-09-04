# Smart Help Hub Expansion Implementation Plan

> **For Codex:** Execute this plan in the current session. Keep production deployment and activation as separate authorization gates.

**Goal:** Add the complete approved smart-hub feature set to ARC's native dialog menu while retaining a compact root and truthful plugin state.

**Architecture:** Add pure planners for parameterized search, goals, context, diagnostics, and personalization. Add a bounded Redis-backed preference store and extend the gateway with exact Paper snapshots. Keep dialog rendering in small controller collaborators rather than expanding the existing controller indefinitely.

**Tech Stack:** Kotlin 2.3, Java 25, Paper 1.21.11 API, arc-core 2.4.5 dialog and Redis primitives, Lands API, HuskHomes API, ItemsAdder API, JUnit 5, MockK/Mockito.

---

### Task 1: Pure behavior and contracts

**Files:**
- Create: `src/main/kotlin/ru/arc/helpcenter/HelpCenterSmartQuery.kt`
- Create: `src/main/kotlin/ru/arc/helpcenter/HelpCenterHubPlanner.kt`
- Test: `src/test/kotlin/ru/arc/helpcenter/HelpCenterSmartQueryTest.kt`
- Test: `src/test/kotlin/ru/arc/helpcenter/HelpCenterHubPlannerTest.kt`

1. Write failing tests for exact home/player extraction, ambiguous fallback, goal routing, item actions, and diagnostics.
2. Run the focused tests and confirm failure.
3. Implement the smallest pure models and planners.
4. Run focused tests and confirm success.

### Task 2: Bounded personalization

**Files:**
- Create: `src/main/kotlin/ru/arc/helpcenter/HelpCenterPreferences.kt`
- Test: `src/test/kotlin/ru/arc/helpcenter/HelpCenterPreferencesTest.kt`

1. Write failing tests for four favorites, six unique recents, stable-ID validation, and toggle behavior.
2. Implement in-memory behavior plus an arc-core `RedisHashUpdater` adapter.
3. Verify focused tests without starting Testcontainers.

### Task 3: Exact runtime snapshots

**Files:**
- Modify: `src/main/kotlin/ru/arc/helpcenter/HelpCenterGateway.kt`
- Modify: `src/main/kotlin/ru/arc/onboarding/OnboardingService.kt`
- Test: `src/test/kotlin/ru/arc/helpcenter/BukkitHelpCenterGatewayTest.kt`

1. Add held-item, location, Lands, feature, and pending-onboarding snapshot contracts.
2. Identify ItemsAdder items only through `CustomStack.byItemStack` and Lands only through its typed API.
3. Keep unavailable integrations nullable and add focused gateway tests.

### Task 4: Dialog surfaces and navigation

**Files:**
- Create: `src/main/kotlin/ru/arc/helpcenter/HelpCenterHubScreens.kt`
- Modify: `src/main/kotlin/ru/arc/helpcenter/HelpCenterController.kt`
- Modify: `src/main/kotlin/ru/arc/helpcenter/HelpCenterModel.kt`
- Modify: `src/main/kotlin/ru/arc/helpcenter/HelpCenterModule.kt`
- Modify: `src/main/kotlin/ru/arc/helpcenter/HelpCenterConfig.kt`
- Modify: `src/main/resources/modules/help-center.yml`
- Test: `src/test/kotlin/ru/arc/helpcenter/HelpCenterConfigTest.kt`
- Test: `src/test/kotlin/ru/arc/helpcenter/HelpCenterPlannerTest.kt`

1. Add action center, favorites/recent, goals, held item, context, request shortcuts, and symptom diagnostics under existing root sections.
2. Add parameterized-search routing and confirmation screens.
3. Apply restrained, semantically consistent copy and colors.
4. Keep all action IDs and input IDs within arc-core bounds.

### Task 5: Verification, mirrors, and publication

**Files:**
- Modify: `src/main/resources/plugin.yml`
- Modify: runtime `plugins/ARC/modules/help-center.yml` mirrors where configured
- Modify: visual manifest/dump inputs if required by repository tooling

1. Run focused tests, then `./gradlew test shadowJar verifyPluginArtifact` using the supported local workflow.
2. Run the plugin visual dump and inspect all new screens for overflow, duplicate navigation, and inconsistent weight/color.
3. Mirror the validated bundled config byte-for-byte to both active server profiles.
4. Review diff and artifact contents; record hashes.
5. Commit and push the exact ARC and runtime-config paths to their configured trunks.
6. Report production deployment, restart, readiness, and player smoke as not performed unless separately authorized.
