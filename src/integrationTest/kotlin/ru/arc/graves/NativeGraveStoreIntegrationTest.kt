package ru.arc.graves

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import ru.arc.sql.SqlConnectionConfig
import ru.arc.sql.SqlSslMode
import ru.arc.testing.containers.MySqlTestService
import ru.arc.testing.containers.MySqlTestSettings
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

class NativeGraveStoreIntegrationTest : FreeSpec({
    "shared migrations, exact CAS, pending-owner exclusion, tombstones and explicit corruption survive fresh clients" {
        MySqlTestService.start(MySqlTestSettings(database = "arc_native_graves_test")).use { mysql ->
            val config = SqlConnectionConfig(
                host = mysql.endpoint.host,
                port = mysql.endpoint.port,
                database = mysql.endpoint.database,
                username = mysql.endpoint.username,
                password = mysql.endpoint.password,
                sslMode = SqlSslMode.DISABLED,
                minimumIdle = 0,
                maximumPoolSize = 2,
            )
            val openingFirst = CompletableFuture.supplyAsync { MySqlNativeGraveRepository.open(config) }
            val openingSecond = CompletableFuture.supplyAsync { MySqlNativeGraveRepository.open(config) }
            val first = openingFirst.join()
            val second = openingSecond.join()
            try {
                val sequenceOwner = UUID.randomUUID()
                val sequenceOperation = UUID.randomUUID()
                val firstReservation = first.reserveSequence(sequenceOperation, sequenceOwner)
                val concurrentReservation = second.reserveSequence(sequenceOperation, sequenceOwner)
                CompletableFuture.allOf(firstReservation, concurrentReservation).join()
                val firstSequence = firstReservation.join()
                (firstSequence > 0) shouldBe true
                concurrentReservation.join() shouldBe firstSequence
                second.reserveSequence(sequenceOperation, sequenceOwner).join() shouldBe firstSequence
                shouldThrow<CompletionException> { second.reserveSequence(sequenceOperation, UUID.randomUUID()).join() }
                val nextSequence = second.reserveSequence(UUID.randomUUID(), sequenceOwner).join()
                (nextSequence > firstSequence) shouldBe true
                first.reserveSequence(sequenceOperation, sequenceOwner).join() shouldBe firstSequence

                val original = integrationGrave().copy(ownerId = sequenceOwner)
                first.insert(original).join() shouldBe true
                second.insert(original).join() shouldBe true
                second.insert(original.copy(experience = 10)).join() shouldBe false
                val firstAttempt = first.replace(original, original.copy(experience = 10, revision = 1))
                val secondAttempt = second.replace(original, original.copy(experience = 20, revision = 1))
                CompletableFuture.allOf(firstAttempt, secondAttempt).join()
                listOf(firstAttempt.join(), secondAttempt.join()).count { it } shouldBe 1
                val current = second.loadAll().join().single { it.id == original.id }
                first.replace(current.copy(experience = 99), current.copy(revision = 2)).join() shouldBe false

                val pending = integrationGrave().copy(phase = NativeGravePhase.PREPARED, operation = integrationOperation())
                val duplicateOwner = pending.copy(id = UUID.randomUUID(), operation = integrationOperation())
                first.insert(pending).join() shouldBe true
                second.insert(duplicateOwner).join() shouldBe false
                val localScope = pending.copy(id = UUID.randomUUID(), server = "parkour",
                    operation = integrationOperation().copy(server = "parkour", inventoryScope = "local:parkour"))
                second.insert(localScope).join() shouldBe true
                first.insert(localScope.copy(id = UUID.randomUUID(), operation = localScope.operation!!.copy(id = UUID.randomUUID()))).join() shouldBe false
                second.loadAll().join().single { it.id == pending.id } shouldBe pending
                val acknowledged = pending.copy(revision = 1, phase = NativeGravePhase.ACTIVE, operation = null)
                first.replace(pending, acknowledged).join() shouldBe true
                second.insert(duplicateOwner).join() shouldBe true
                val claim = acknowledged.copy(
                    revision = 2, phase = NativeGravePhase.CLAIMING,
                    operation = integrationOperation().copy(kind = NativeGraveOperationKind.CLAIM),
                )
                first.replace(acknowledged, claim).join() shouldBe false
                second.loadAll().join().single { it.id == acknowledged.id } shouldBe acknowledged

                val review = duplicateOwner.copy(revision = 1, phase = NativeGravePhase.REVIEW)
                second.replace(duplicateOwner, review).join() shouldBe true
                first.replace(acknowledged, claim).join() shouldBe true

                val tombstone = current.copy(
                    revision = 2, phase = NativeGravePhase.CLAIMED, experience = 0, items = emptyList(),
                    completedSequence = nextSequence, checkpoint = integrationInventory(),
                )
                first.loadWatermarks(emptySet()).join() shouldBe emptyMap()
                second.replace(current, tombstone).join() shouldBe true
                val watermark = NativeGraveWatermark(sequenceOwner, nextSequence, integrationInventory())
                val older = integrationGrave().copy(ownerId = sequenceOwner)
                first.insert(older).join() shouldBe true
                val olderCompleted = older.copy(
                    revision = 1, completedSequence = firstSequence,
                    checkpoint = NativeGraveInventory(List(41) { null }, 0, 0f, 0),
                )
                first.replace(older, olderCompleted).join() shouldBe true
                val failedSequence = second.reserveSequence(UUID.randomUUID(), sequenceOwner).join()
                second.replace(
                    olderCompleted.copy(experience = 99),
                    olderCompleted.copy(revision = 2, completedSequence = failedSequence),
                ).join() shouldBe false
                val ownerBatch = LinkedHashSet<UUID>().apply { repeat(256) { add(UUID.randomUUID()) }; add(sequenceOwner) }
                first.loadWatermarks(ownerBatch).join() shouldBe mapOf(sequenceOwner to watermark)

                val rejected = integrationGrave().copy(ownerId = sequenceOwner)
                first.insert(rejected).join() shouldBe true
                mysql.endpoint.connect().use { database ->
                    database.createStatement().use {
                        it.execute("ALTER TABLE arc_native_grave_watermarks ADD CONSTRAINT reject_native_grave_checkpoint CHECK (`sequence` < $failedSequence)")
                    }
                }
                try {
                    shouldThrow<CompletionException> {
                        first.replace(rejected, rejected.copy(revision = 1, completedSequence = failedSequence, checkpoint = integrationInventory())).join()
                    }
                    second.loadAll().join().single { it.id == rejected.id } shouldBe rejected
                    second.loadWatermarks(setOf(sequenceOwner)).join() shouldBe mapOf(sequenceOwner to watermark)
                } finally {
                    mysql.endpoint.connect().use { database ->
                        database.createStatement().use { it.execute("ALTER TABLE arc_native_grave_watermarks DROP CHECK reject_native_grave_checkpoint") }
                    }
                }

                val remoteOwner = UUID.randomUUID()
                val local = queryGrave()
                val unlimited = queryGrave().copy(expiresAt = -1)
                val remote = queryGrave().copy(server = "query_remote", ownerId = remoteOwner)
                val unrelated = queryGrave().copy(server = "query_remote")
                val expired = queryGrave().copy(expiresAt = 2_000)
                val pendingQuery = queryGrave(NativeGravePhase.PREPARED).copy(expiresAt = 2_000)
                val claimingQuery = queryGrave(NativeGravePhase.CLAIMING).copy(expiresAt = 2_000)
                val reviewQuery = queryGrave(NativeGravePhase.REVIEW).copy(expiresAt = 2_000)
                val imported = queryGrave(NativeGravePhase.IMPORTED).copy(expiresAt = 2_000)
                val claimed = queryGrave(NativeGravePhase.CLAIMED)
                listOf(local, unlimited, remote, unrelated, expired, pendingQuery, claimingQuery, reviewQuery, imported, claimed).forEach {
                    second.insert(it).join() shouldBe true
                }
                val requestedOwners = LinkedHashSet<UUID>().apply { repeat(256) { add(UUID.randomUUID()) }; add(remoteOwner) }
                first.loadCurrent("query_backend", requestedOwners, 2_000).join().map { it.id }.toSet() shouldBe
                    setOf(local, unlimited, remote, pendingQuery, claimingQuery, reviewQuery, imported).map { it.id }.toSet()
                first.loadActive(2_000).join().map { it.id }.toSet() shouldBe
                    setOf(local, unlimited, remote, unrelated).map { it.id }.toSet()
                first.loadActive(2_000, "query_remote").join().map { it.id }.toSet() shouldBe
                    setOf(remote.id, unrelated.id)
                first.findActive(unrelated.id, 2_000).join() shouldBe unrelated
                first.findActive(expired.id, 2_000).join() shouldBe null
                first.findActive(reviewQuery.id, 2_000).join() shouldBe null

                val reopening = MySqlNativeGraveRepository.open(config)
                try {
                    val restored = reopening.loadAll().join()
                    restored.single { it.id == tombstone.id } shouldBe tombstone
                    restored.single { it.id == duplicateOwner.id } shouldBe review
                    reopening.loadWatermarks(setOf(sequenceOwner)).join() shouldBe mapOf(sequenceOwner to watermark)
                    reopening.reserveSequence(sequenceOperation, sequenceOwner).join() shouldBe firstSequence
                    (reopening.reserveSequence(UUID.randomUUID(), sequenceOwner).join() > nextSequence) shouldBe true
                } finally { reopening.closeAsync().join() }

                mysql.endpoint.connect().use { database ->
                    val corrupt = "{\"id\":\"${original.id}\"}".toByteArray()
                    database.prepareStatement("UPDATE arc_native_graves SET payload = ?, payload_hash = ? WHERE id = ?").use { statement ->
                        statement.setBytes(1, corrupt)
                        statement.setString(2, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(corrupt)))
                        statement.setString(3, original.id.toString())
                        statement.executeUpdate() shouldBe 1
                    }
                }
                shouldThrow<CompletionException> { first.loadAll().join() }.cause!!.message!!.contains("corrupt") shouldBe true
                mysql.endpoint.connect().use { database ->
                    database.prepareStatement("UPDATE arc_native_grave_watermarks SET checkpoint_hash = ? WHERE owner_id = ?").use { statement ->
                        statement.setString(1, "0".repeat(64))
                        statement.setString(2, sequenceOwner.toString())
                        statement.executeUpdate() shouldBe 1
                    }
                }
                shouldThrow<CompletionException> { second.loadWatermarks(setOf(sequenceOwner)).join() }.cause!!.message!!.contains("corrupt") shouldBe true
            } finally {
                CompletableFuture.allOf(first.closeAsync(), second.closeAsync()).join()
            }
        }
    }
})

private fun integrationOperation(): NativeGraveOperation {
    val before = integrationInventory()
    return NativeGraveOperation(
        UUID.randomUUID(), "classic", NativeGraveOperationKind.CREATE,
        before, NativeGraveInventory(List(41) { null }, 0, 0f, 0),
    )
}

private fun integrationInventory() = NativeGraveInventory(List(41) { if (it == 40) "AQID" else null }, 4, 0.5f, 43)

private fun queryGrave(phase: NativeGravePhase = NativeGravePhase.ACTIVE): NativeGraveRecord = integrationGrave().copy(
    server = "query_backend", expiresAt = 3_000, phase = phase,
    operation = when (phase) {
        NativeGravePhase.PREPARED, NativeGravePhase.REVIEW -> integrationOperation()
        NativeGravePhase.CLAIMING -> integrationOperation().copy(kind = NativeGraveOperationKind.CLAIM)
        else -> null
    },
    items = if (phase == NativeGravePhase.CLAIMED) emptyList() else listOf("AQID"),
    experience = if (phase == NativeGravePhase.CLAIMED) 0 else 7,
)

private fun integrationGrave() = NativeGraveRecord(
    id = UUID.randomUUID(), ownerId = UUID.randomUUID(), ownerName = "Player", server = "classic", world = "world",
    x = 10, y = 64, z = -10, createdAt = 1_000, expiresAt = 2_000, experience = 7, items = listOf("AQID"),
)
