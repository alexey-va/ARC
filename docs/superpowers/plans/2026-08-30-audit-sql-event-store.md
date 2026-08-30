# ARC SQL Audit Event Store Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace ARC's full-history Redis audit snapshots with an idempotent append-only MySQL event store and safely cut production over through dual-write verification.

**Architecture:** `AuditService` emits immutable `AuditEvent` values into an `AuditEventStore`. Redis, SQL, and dual implementations share page, scan, retention, and diagnostics contracts; SQL scans feed a bounded summary accumulator. Production first reads Redis while dual-writing/importing, then switches to SQL without deleting rollback data.

**Tech Stack:** Kotlin 2.3, Java 25, arc-core-sql 2.2.0, MySQL 8, Micrometer/Prometheus, Kotest, JUnit, Testcontainers integration tests, Paper scheduler, ruscrafting-ops deploy scripts.

**Spec:** `docs/superpowers/specs/2026-08-30-audit-sql-event-store-design.md`

## Global Constraints

- Preserve every existing `Transaction.eventId`; retries and migration use `INSERT IGNORE` on that exact ID.
- Never log or expose SQL credentials or serialized audit context.
- SQL mode must not instantiate `RedisAuditRepository` or call `loadAllOnStart`.
- Legacy Redis data remains untouched after cutover until a separately authorized cleanup.
- All JDBC work runs through `SqlRuntime.executor`; Paper threads never block on JDBC.
- Commands and ops output preserve existing user-facing pagination and report fields.

---

### Task 1: Storage configuration and immutable event contract

**Files:**
- Create: `src/main/kotlin/ru/arc/audit/AuditEventStore.kt`
- Modify: `src/main/kotlin/ru/arc/audit/AuditConfig.kt`
- Modify: `src/main/resources/modules/audit.yml`
- Test: `src/test/kotlin/ru/arc/audit/AuditStorageConfigTest.kt`

**Interfaces:**
- Produces: `AuditEvent(playerName: String, transaction: Transaction)`, `AuditPage(records: List<Transaction>, totalRecords: Long)`, `AuditStorageMode`, `AuditStorageStatus`, and `AuditEventStore` methods `append`, `page`, `scan`, `count`, `clearPlayer`, `clearAll`, `prune`, `status`, `close`.
- Produces: `AuditConfig.storageMode`, `mysql`, `migrationOwnerServer`, `migrationBatchSize`, `writeBatchSize`, `writeFlushIntervalMillis`, `maximumPendingEvents`, `cleanupIntervalHours`, `jobsRawRetentionDays`, `retentionDays`, `cleanupDeleteBatchSize`, and `shutdownTimeoutSeconds`.

- [ ] **Step 1: Write failing storage-config and event-contract tests**

  Cover normalized modes, mandatory SQL fields, writer/cleanup bounds, stable
  event IDs, filter-aware page requests, and redacted connection diagnostics.

- [ ] **Step 2: Run the focused tests and verify RED**

  Run: `./gradlew --offline test --tests ru.arc.audit.AuditStorageConfigTest`

  Expected: compilation fails because the storage contract and config getters do not exist.

- [ ] **Step 3: Implement the minimal typed contract and config parsing**

  Parse `storage.mode`, `storage.mysql.*`, `storage.migration.owner-server`,
  `storage.migration.batch-size`, and `storage.shutdown-timeout-seconds`. Construct
  `SqlConnectionConfig` only for dual/SQL modes and keep Redis as the JAR default.

- [ ] **Step 4: Run the focused tests and verify GREEN**

  Run: `./gradlew --offline test --tests ru.arc.audit.AuditStorageConfigTest`

- [ ] **Step 5: Commit the storage contract**

  Run: `git add src/main/kotlin/ru/arc/audit/AuditEventStore.kt src/main/kotlin/ru/arc/audit/AuditConfig.kt src/main/resources/modules/audit.yml src/test/kotlin/ru/arc/audit/AuditStorageConfigTest.kt docs/superpowers && git commit -m "feat: define SQL audit event storage contract"`

### Task 2: MySQL schema, codec, and indexed event operations

**Files:**
- Create: `src/main/kotlin/ru/arc/audit/SqlAuditEventStore.kt`
- Create: `src/main/kotlin/ru/arc/audit/AuditWriteBatcher.kt`
- Create: `src/test/kotlin/ru/arc/audit/AuditSqlCodecTest.kt`
- Create: `src/test/kotlin/ru/arc/audit/AuditWriteBatcherTest.kt`
- Create: `src/test/kotlin/ru/arc/audit/AuditRetentionPolicyTest.kt`
- Create: `src/integrationTest/kotlin/ru/arc/audit/SqlAuditEventStoreIntegrationTest.kt`

**Interfaces:**
- Consumes: `AuditEventStore`, `AuditEvent`, `AuditPage`, `AuditStorageStatus`, `SqlConnectionConfig`.
- Produces: `SqlAuditEventStore.open(config, metrics)`, bounded asynchronous
  `AuditWriteBatcher`, daily Jobs compaction/general retention, and schema version
  `1` under migration namespace `arc_audit`.

- [ ] **Step 1: Write failing codec unit tests**

  Hand-build legacy and enriched transactions; assert SQL row encoding preserves
  amount, occurrences, bounded labels, event ID, timestamps, and context JSON,
  and decoding reconstructs the same observable ledger fields.

- [ ] **Step 2: Run codec tests and verify RED**

  Run: `./gradlew --offline test --tests ru.arc.audit.AuditSqlCodecTest`

- [ ] **Step 3: Write failing asynchronous batching and retention tests**

  Prove producers return without JDBC, one flush writes one batch, a failed batch
  retries unchanged, queue capacity is bounded, structured Jobs payouts retain
  exact money/action totals while coalescing a 60-second window, Jobs compaction
  uses complete UTC days, and general retention cannot delete inside the
  configured window.

- [ ] **Step 4: Run batching and retention tests and verify RED**

  Run: `./gradlew --offline test --tests ru.arc.audit.AuditWriteBatcherTest --tests ru.arc.audit.AuditRetentionPolicyTest`

- [ ] **Step 5: Implement codec, migration DDL, async batch writer, page, scan, count, delete, and daily maintenance**

  Use `INSERT IGNORE`, prepared-statement batches of at most 250 events, a 250 ms
  flush trigger, chronological scan order, player and server/time indexes,
  a bounded 60-second Jobs accumulator, transactional deterministic Jobs daily
  compaction, deletes of at most 10,000 rows, and no raw payload logs.

- [ ] **Step 6: Run unit tests and verify GREEN**

  Run: `./gradlew --offline test --tests ru.arc.audit.AuditSqlCodecTest --tests ru.arc.audit.AuditWriteBatcherTest --tests ru.arc.audit.AuditRetentionPolicyTest`

- [ ] **Step 7: Write real MySQL integration tests**

  Verify duplicate replay inserts once, page/filter ordering, chronological scan,
  cross-server selection, player/all deletion, retention, and reopen migration.

- [ ] **Step 8: Run integration tests on the Docker-capable runner**

  Run: `./gradlew integrationTest --tests ru.arc.audit.SqlAuditEventStoreIntegrationTest`

- [ ] **Step 9: Commit SQL storage**

  Run: `git add src/main/kotlin/ru/arc/audit/SqlAuditEventStore.kt src/test src/integrationTest && git commit -m "feat: persist audit events in MySQL"`

### Task 3: Redis adapter, dual-write, and idempotent migration

**Files:**
- Modify: `src/main/kotlin/ru/arc/audit/AuditRepository.kt`
- Create: `src/main/kotlin/ru/arc/audit/RedisAuditEventStore.kt`
- Create: `src/main/kotlin/ru/arc/audit/DualWriteAuditEventStore.kt`
- Create: `src/main/kotlin/ru/arc/audit/AuditLegacyMigration.kt`
- Test: `src/test/kotlin/ru/arc/audit/DualWriteAuditEventStoreTest.kt`
- Test: `src/test/kotlin/ru/arc/audit/AuditLegacyMigrationTest.kt`

**Interfaces:**
- Consumes: existing `RedisAuditRepository`, `AuditEventStore`, and stable event IDs.
- Produces: identical-event dual append, Redis primary reads in dual mode, and `AuditMigrationReport(scanned, inserted, duplicates, failed, completedAt)`.

- [ ] **Step 1: Write failing dual-write and migration tests**

  Prove both stores receive the same event ID, partial failures remain visible,
  only the owner imports, repeated import is idempotent, and new events racing an
  import do not double-count.

- [ ] **Step 2: Run focused tests and verify RED**

  Run: `./gradlew --offline test --tests ru.arc.audit.DualWriteAuditEventStoreTest --tests ru.arc.audit.AuditLegacyMigrationTest`

- [ ] **Step 3: Implement Redis adapter, dual store, and batched importer**

  Add exact transactions to Redis without generating a second ID, import immutable
  snapshots in configured batches, and keep Redis reads primary until cutover.

- [ ] **Step 4: Run focused tests and verify GREEN**

  Run: `./gradlew --offline test --tests ru.arc.audit.DualWriteAuditEventStoreTest --tests ru.arc.audit.AuditLegacyMigrationTest`

- [ ] **Step 5: Commit migration support**

  Run: `git add src/main src/test && git commit -m "feat: migrate Redis audit snapshots idempotently"`

### Task 4: Bounded summaries and asynchronous service queries

**Files:**
- Modify: `src/main/kotlin/ru/arc/audit/AuditSummary.kt`
- Modify: `src/main/kotlin/ru/arc/audit/AuditService.kt`
- Modify: `src/main/kotlin/ru/arc/audit/AuditManager.kt`
- Modify: `src/main/kotlin/ru/arc/commands/arc/subcommands/AuditSubCommand.kt`
- Modify: `src/main/kotlin/ru/arc/ops/OpsEconomyAuditHandlers.kt`
- Test: `src/test/kotlin/ru/arc/audit/AuditSummaryStreamingTest.kt`
- Modify: `src/test/kotlin/ru/arc/audit/AuditServiceTest.kt`

**Interfaces:**
- Consumes: `AuditEventStore.page` and chronological `scan`.
- Produces: bounded `AuditSummaryAccumulator.accept/finish`, async command delivery, SQL-backed ops summaries, and completion-aware clear operations.

- [ ] **Step 1: Write failing streaming and service tests**

  Feed more records than the requested recent limit and assert retained diagnostic
  state stays bounded while totals, rapid-income evidence, policies, jobs, shops,
  recent events, and existing summary maps remain correct. Assert page queries do
  not call full scans and failures reach the audience.

- [ ] **Step 2: Run focused tests and verify RED**

  Run: `./gradlew --offline test --tests ru.arc.audit.AuditSummaryStreamingTest --tests ru.arc.audit.AuditServiceTest`

- [ ] **Step 3: Extract the bounded accumulator and switch service operations to immutable appends**

  Keep only bounded recent lists and five-minute sliding windows; aggregate all
  other report fields online. Marshal command messages through `TaskScheduler`.

- [ ] **Step 4: Adapt ops handlers and commands to bounded futures**

  Ops waits at most 15 seconds on its HTTP worker; Paper command execution returns
  immediately and sends success only after storage completion.

- [ ] **Step 5: Run focused and existing audit tests**

  Run: `./gradlew --offline test --tests 'ru.arc.audit.*' --tests ru.arc.commands.arc.subcommands.AuditSubCommandTest --tests ru.arc.ops.OpsHttpTest`

- [ ] **Step 6: Commit the service cutover**

  Run: `git add src/main src/test && git commit -m "refactor: stream audit queries without full history"`

### Task 5: Runtime wiring, health, metrics, and source attribution

**Files:**
- Modify: `src/main/kotlin/ru/arc/audit/AuditManager.kt`
- Create: `src/main/kotlin/ru/arc/audit/AuditStorageMetrics.kt`
- Modify: `src/main/kotlin/ru/arc/audit/EconomyAttribution.kt`
- Modify: `src/main/kotlin/ru/arc/ops/OpsEconomyAuditHandlers.kt`
- Modify: `build.gradle.kts`
- Test: `src/test/kotlin/ru/arc/audit/AuditStorageMetricsTest.kt`
- Modify: `src/test/kotlin/ru/arc/audit/EconomyAuditTest.kt`

**Interfaces:**
- Produces: mode-specific store factory, SQL health and migration status in ops,
  Micrometer append/migration/health meters, ArcEcoJobs attribution as `jobs`, and ARC version `1.2.0`.

- [ ] **Step 1: Write failing wiring, metric, and attribution tests**

  Assert SQL mode never creates Redis storage, dual mode exposes migration state,
  metric labels are bounded, and `ru.ruscrafting.ecojobs` resolves to source `jobs`.

- [ ] **Step 2: Run focused tests and verify RED**

  Run: `./gradlew --offline test --tests ru.arc.audit.AuditStorageMetricsTest --tests ru.arc.audit.EconomyAuditTest`

- [ ] **Step 3: Implement runtime factory, diagnostics, meters, and attribution**

  SQL configuration errors fail plugin initialization in SQL/dual modes; Redis
  remains an explicit rollback mode only.

- [ ] **Step 4: Run focused tests and verify GREEN**

  Run: `./gradlew --offline test --tests ru.arc.audit.AuditStorageMetricsTest --tests ru.arc.audit.EconomyAuditTest`

- [ ] **Step 5: Commit runtime wiring**

  Run: `git add src/main src/test build.gradle.kts && git commit -m "feat: expose SQL audit health and metrics"`

### Task 6: Production configuration and Grafana panels

**Files:**
- Modify: `ruscrafting-ops/{classic,classic_survival,parkour}/plugins/ARC/modules/audit.yml`
- Modify: `ruscrafting-ops/server-scripts/grafana/provisioning/dashboards/json/ruscrafting/ruscrafting-gameplay.json`
- Modify: `ruscrafting-ops/server-scripts/grafana/test_dashboards.py`

**Interfaces:**
- Consumes: ARC `1.2.0` storage config and Prometheus metrics.
- Produces: initial `dual` configs with survival as migration owner and dashboard panels for write rate, failures, latency, SQL readiness, mode, and migration progress.

- [ ] **Step 1: Add dashboard assertions that fail for missing audit panels**

  Run: `python3 -m unittest server-scripts.grafana.test_dashboards`

- [ ] **Step 2: Apply safe dual-mode config and dashboard panels**

  Reuse the existing shared MySQL endpoint and credentials without printing them.
  Parkour receives the same shared database settings even though ArcEcoJobs is absent.

- [ ] **Step 3: Validate configs and dashboards**

  Run: `./scripts/mc validate`

  Run: `python3 -m unittest server-scripts.grafana.test_dashboards`

- [ ] **Step 4: Commit and push ops configuration**

  Run: `git add classic classic_survival parkour server-scripts/grafana && git commit -m "ops: stage SQL economy audit migration" && git push origin HEAD:main`

### Task 7: Release verification, push, dual rollout, cutover, and profiling

**Files:**
- No new source files; generated artifact: `build/libs/ARC-1.2.0.jar`.

**Interfaces:**
- Produces: pushed ARC `master`, green CI, active production `1.2.0`, verified SQL audit counts/totals, SQL-only runtime, Grafana provisioning, and fresh Spark evidence.

- [ ] **Step 1: Run the complete local verification gate**

  Run: `./gradlew --offline clean test shadowJar`

  Run: `./scripts/verify_consumer_architecture.py`

  Run: `git diff --check`

- [ ] **Step 2: Commit final source, push ARC master, and wait for CI**

  Rebase only onto the fetched remote head, rerun affected verification after any
  conflict resolution, push without force, and require both unit/package and MySQL
  integration jobs to succeed.

- [ ] **Step 3: Deploy dual mode and one ARC artifact to all Paper nodes**

  Use the normal ops deploy transaction, restart once, and prove all nodes load
  `ARC 1.2.0`, become ready, and report dual storage health.

- [ ] **Step 4: Verify migration before cutover**

  Compare Redis snapshot records with SQL events and compare per-server records,
  operations, minted, burned, transfer, adjustment, and observed-net totals. Abort
  cutover on any mismatch or persistence failure.

- [ ] **Step 5: Switch ops configs to SQL mode, push, deploy, and restart once**

  Prove no audit `CachedRepository` is registered, SQL schema/health/count are
  ready, commands return indexed pages, and new economy events appear once.

- [ ] **Step 6: Provision Grafana and run post-cutover Spark profiles**

  Apply the existing Grafana migration script, verify dashboard readback, run CPU
  and allocation profiles on classic, survival, and parkour, upload them to
  spark.lucko.me, and compare audit allocation stacks against the baseline.

- [ ] **Step 7: Record exact evidence**

  Record source commits, CI URLs, artifact SHA-256, deploy IDs, live JAR hashes,
  PIDs/readiness, migration report, SQL counts/totals, dashboard status, and six
  Spark profile URLs. Do not delete legacy Redis audit data.
