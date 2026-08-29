# Mount Quick Summon Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a player select one favorite owned mount in `/mount` and summon it either with sneak + swap-hands or by right-clicking a reusable whistle.

**Architecture:** Store the favorite as one exclusive direct LuckPerms permission under `arc.mounts.favorite.*`. Route menu summons, sneak + swap-hands, and whistle use through one `MountSummonService`, while a Paper listener owns input events and the whistle's persistent item marker.

**Tech Stack:** Kotlin 2.3, Paper API, LuckPerms API, MockBukkit, Kotest/JUnit 5, MockK.

**Spec:** `docs/mounts.md`

## Global Constraints

- The bundled plugin must remain usable without ItemsAdder or non-zero custom model data.
- A favorite must be an unlocked catalog mount at selection and summon time.
- Sneak + swap-hands must preserve vanilla swapping when no favorite is selected or quick summon is disabled.
- The whistle must summon the currently selected favorite, not persist a mount id inside the item.
- Existing world, water, vehicle, session, cooldown, tuning, skin, glow, and ability rules remain owned by `MountSessionController.spawn`.
- No production deployment or restart is part of this change.

---

### Task 1: Favorite permission state

**Files:**
- Modify: `src/main/kotlin/ru/arc/mounts/MountDomain.kt`
- Modify: `src/main/kotlin/ru/arc/mounts/LuckPermsMountOwnership.kt`
- Test: `src/test/kotlin/ru/arc/mounts/MountDomainTest.kt`

**Interfaces:**
- Consumes: direct positive LuckPerms permission nodes already used for tuning and skins.
- Produces: `MountOwnership.favoriteMountId(UUID): String?` and `MountOwnership.setFavoriteMount(UUID, MountDefinition): CompletableFuture<Void>`.

- [x] **Step 1: Write the failing permission-state tests**

```kotlin
"favorite permission uses the global exclusive namespace" {
    favoriteMountPermission("bee") shouldBe "arc.mounts.favorite.bee"
}

"favorite parser ignores malformed and negative direct nodes" {
    directPositiveStringSuffix(nodes, MOUNT_FAVORITE_PERMISSION_PREFIX, MountDefinition::validId) shouldBe "bee"
}
```

- [x] **Step 2: Run the focused test and verify RED**

Run: `./gradlew test --tests ru.arc.mounts.MountDomainTest`

Expected: compilation fails because the favorite permission helpers do not exist.

- [x] **Step 3: Implement exclusive favorite persistence**

```kotlin
const val MOUNT_FAVORITE_PERMISSION_PREFIX = "arc.mounts.favorite."

fun favoriteMountPermission(mountId: String) = "$MOUNT_FAVORITE_PERMISSION_PREFIX$mountId"
```

`LuckPermsMountOwnership.setFavoriteMount` removes every direct node with that exact prefix before adding the selected mount node.

- [x] **Step 4: Run the focused test and verify GREEN**

Run: `./gradlew test --tests ru.arc.mounts.MountDomainTest`

Expected: all `MountDomainTest` cases pass.

### Task 2: Shared summon orchestration

**Files:**
- Create: `src/main/kotlin/ru/arc/mounts/MountSummonService.kt`
- Create: `src/test/kotlin/ru/arc/mounts/MountSummonServiceTest.kt`

**Interfaces:**
- Consumes: `MountOwnership`, `MountCatalog`, `MountModuleConfig`, and `MountSessionController.spawn`.
- Produces: `MountSummonService.summon`, `summonFavorite`, `selectFavorite`, `favoriteMountId`, and typed `MountSummonOutcome` / `MountFavoriteSelectionOutcome` results.

- [x] **Step 1: Write failing tests for favorite resolution and unlocked checks**

```kotlin
service.summonFavorite(player) shouldBe MountSummonOutcome.FAVORITE_NOT_SELECTED
service.selectFavorite(player, lockedMount).join() shouldBe MountFavoriteSelectionOutcome.NOT_UNLOCKED
service.summonFavorite(playerWithOwnedFavorite) shouldBe MountSummonOutcome.SUCCESS
```

- [x] **Step 2: Run the focused test and verify RED**

Run: `./gradlew test --tests ru.arc.mounts.MountSummonServiceTest`

Expected: compilation fails because `MountSummonService` and its outcomes do not exist.

- [x] **Step 3: Implement the minimal shared service**

The service resolves the current profile, copies tuning/appearance/ability values into the existing `sessions.spawn` call, maps every `MountSpawnResult` to one typed outcome, and owns the common player feedback mapping.

- [x] **Step 4: Run the focused test and verify GREEN**

Run: `./gradlew test --tests ru.arc.mounts.MountSummonServiceTest`

Expected: all service cases pass.

### Task 3: Quick inputs and whistle

**Files:**
- Create: `src/main/kotlin/ru/arc/mounts/MountQuickSummonController.kt`
- Create: `src/test/kotlin/ru/arc/mounts/MountQuickSummonControllerTest.kt`
- Modify: `src/main/kotlin/ru/arc/mounts/MountModuleConfig.kt`
- Modify: `src/main/resources/modules/mounts.yml`
- Test: `src/test/kotlin/ru/arc/mounts/MountModuleConfigTest.kt`

**Interfaces:**
- Consumes: `MountSummonService.summonFavorite` and `MountModuleConfig` quick-summon settings.
- Produces: registered handlers for `PlayerSwapHandItemsEvent` and `PlayerInteractEvent`, `giveWhistle(Player)`, and a PDC-marked whistle item.

- [x] **Step 1: Write failing event and config tests**

```kotlin
swapEvent.isCancelled shouldBe true
summonCalls shouldBe 1
controller.isWhistle(controller.createWhistle()) shouldBe true
config.quickSummonSneakSwapHands shouldBe true
config.quickSummonWhistle shouldBe true
```

- [x] **Step 2: Run the focused tests and verify RED**

Run: `./gradlew test --tests ru.arc.mounts.MountQuickSummonControllerTest --tests ru.arc.mounts.MountModuleConfigTest`

Expected: compilation fails because the quick-summon controller and config accessors do not exist.

- [x] **Step 3: Implement both input paths**

Sneak + swap-hands is cancelled only for a permitted player with a saved favorite. Right-click is cancelled only for an ARC whistle identified by `PersistentDataContainer`; `giveWhistle` refuses duplicates and a full inventory.

- [x] **Step 4: Run the focused tests and verify GREEN**

Run: `./gradlew test --tests ru.arc.mounts.MountQuickSummonControllerTest --tests ru.arc.mounts.MountModuleConfigTest`

Expected: all quick-summon and config cases pass.

### Task 4: Mount menu integration

**Files:**
- Modify: `src/main/kotlin/ru/arc/mounts/MountGuiController.kt`
- Modify: `src/main/kotlin/ru/arc/mounts/MountModule.kt`
- Test: `src/test/kotlin/ru/arc/mounts/MountGuiControllerTest.kt`

**Interfaces:**
- Consumes: `MountSummonService` and `MountQuickSummonController.giveWhistle`.
- Produces: favorite and whistle buttons in mount details, favorite status in collection lore, and one shared summon path for existing left-click/detail actions.

- [x] **Step 1: Write failing GUI tests**

```kotlin
plainName(detail.getItem(13)) shouldBe "Выбрать любимым"
controller.onClick(clickEvent(player.openInventory, 13))
summonService.favoriteMountId(player.uniqueId) shouldBe mount.id
plainName(detail.getItem(40)) shouldBe "Получить свисток"
```

- [x] **Step 2: Run the focused GUI test and verify RED**

Run: `./gradlew test --tests ru.arc.mounts.MountGuiControllerTest`

Expected: assertions fail because detail slots 13 and 40 still contain background items.

- [x] **Step 3: Wire the detail actions and shared summoner**

The detail screen renders both buttons, selection completion returns to the main thread before reopening the menu, and existing summon clicks delegate to `MountSummonService`.

- [x] **Step 4: Run the focused GUI test and verify GREEN**

Run: `./gradlew test --tests ru.arc.mounts.MountGuiControllerTest`

Expected: all GUI cases pass.

### Task 5: Documentation and final verification

**Files:**
- Modify: `docs/mounts.md`

**Interfaces:**
- Consumes: the completed user-facing behavior.
- Produces: operator documentation for permission state, controls, whistle recovery, and configuration switches.

- [x] **Step 1: Document the exact behavior and permission node**

Add `arc.mounts.favorite.<mount>` and explain selection, sneak + swap-hands semantics, and the reusable whistle.

- [x] **Step 2: Run the complete verification suite**

Run: `./gradlew test shadowJar`

Expected: build exits 0 with no failed tests.

- [x] **Step 3: Inspect the deliverable**

Run: `git diff --check && git status --short && git diff --stat`

Expected: no whitespace errors; only the scoped ARC source, tests, resource, plan, and mount documentation are changed.

- [ ] **Step 4: Commit and push the verified change**

```bash
git add docs src
git commit -m "Add favorite mount quick summon"
git push origin master
```

Expected: local `HEAD` and `origin/master` resolve to the same new commit. Production remains unchanged.
