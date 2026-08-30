# ARC SQL Audit Event Store Design

## Problem

`AuditData` is an append-only financial history represented as a mutable cached
entity. Every mutation marks the whole entity dirty; the Redis repository then
serializes the entire player history, publishes an invalidation, and makes the
other Paper nodes deserialize and merge the same history. Production allocation
profiles show this snapshot fan-out dominates allocation pressure.

## Goal

Persist each economy record once as an idempotent SQL event, keep financial
queries bounded, retain the existing audit command and ops report contracts,
and remove the full-history Redis cache after a verified dual-write migration.

## Storage contract

The primary table is `arc_audit_events`. `event_id` is the immutable primary
key already carried by `Transaction`. Every row stores the player, bounded
economy labels, timestamps, amount, occurrence count, reason, origin, and the
optional `EconomyLedgerContext` as JSON text. Indexes cover player pagination,
retention, server/time summaries, and source/time summaries.

Writes use `INSERT IGNORE`; replaying migration batches or a dual-write retry
cannot duplicate an event. MySQL migrations use the existing `arc-core-sql`
runtime and checksum-protected `MySqlMigrator` under namespace `arc_audit`.

## Runtime modes

- `redis`: compatibility and rollback mode. Reads and writes use the existing
  Redis snapshot repository.
- `dual`: one event is written to SQL and Redis with the same `event_id`; reads
  remain on Redis while the owner node imports the legacy snapshot.
- `sql`: reads and writes use MySQL only. No audit Redis repository is created,
  so no `loadAllOnStart`, JSON snapshot save, invalidation, or merge occurs.

Only the configured migration owner imports Redis. Import is idempotent and
reports scanned, inserted, duplicate, and failed counts. Legacy Redis data is
retained untouched as rollback evidence after cutover.

## Query model

`/arc audit` uses indexed SQL count plus `LIMIT/OFFSET`; it never loads a full
player history. Ops summaries stream rows in chronological order into a bounded
accumulator. The accumulator retains aggregate maps, sliding rapid-income
windows, and only the requested number of recent records; it does not collect
the entire result set.

Retention is time-based. The SQL store deletes rows older than
`max-age-seconds` in bounded batches. The old heap-oriented weight limits remain
accepted in Redis mode but do not constrain SQL history.

## Failure and lifecycle rules

- Database work runs only on the bounded `SqlRuntime` executor.
- Append failures increment existing persistence-failure telemetry and remain
  visible in runtime health.
- Dual mode attempts both writes and reports partial failures explicitly.
- Commands deliver completion or failure messages on the Paper scheduler.
- Shutdown waits for in-flight appends for a bounded interval, then closes SQL.
- SQL mode refuses startup when its required database configuration is invalid;
  it never silently falls back to an in-memory financial ledger.

## Observability

ARC exposes low-cardinality metrics for append outcome, append latency, SQL
health, migration progress, and retained legacy mode. The authenticated ops
economy response includes storage mode, schema version, health, event count,
and the last migration report. Grafana consumes ARC Prometheus metrics; Grafana
is not the audit system of record.

## Rollout

1. Deploy one ARC release with `dual` mode on all Paper nodes.
2. Let the survival owner import the legacy Redis snapshot.
3. Compare Redis and SQL record counts and per-server amount/operation totals.
4. Switch all nodes to `sql` and restart once.
5. Prove active version, SQL health, no audit Redis repository, stable TPS, and
   materially lower allocation rate with fresh Spark profiles.

