package ru.arc.graves

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import ru.arc.core.BoundedAsyncExecutor
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

class LegacyGravesArchiveTest : FunSpec({
    test("checkpoint seals exact imported records without transferring ownership while GravesX is present") {
        LegacyHandoffFixture().use { f ->
            val record = legacyRecord()
            val legacy = legacySource(listOf(record))
            val archive = f.archive(legacy)

            archive.checkpointOnShutdown().join()
            archive.activateSealed().join()

            f.store.loadAll().join() shouldBe listOf(record)
            f.sealed() shouldBe true
            verify(exactly = 1) { legacy.exportLoadedPlayerGraves() }
        }
    }

    test("a new startup with GravesX invalidates the old seal before another startup can activate it") {
        LegacyHandoffFixture().use { f ->
            val record = legacyRecord()
            f.archive(legacySource(listOf(record))).checkpointOnShutdown().join()
            f.sealed() shouldBe true
            val nextLegacy = legacySource(listOf(record.copy(items = listOf("BAUG"))))

            f.archive(nextLegacy).activateSealed().join()
            f.sealed() shouldBe false
            f.archive().activateSealed().join()

            f.store.loadAll().join() shouldBe listOf(record)
            verify(exactly = 0) { nextLegacy.exportLoadedPlayerGraves() }
        }
    }

    test("startup without GravesX activates exact sealed metadata and items once under the original id") {
        LegacyHandoffFixture().use { f ->
            val record = legacyRecord()
            f.archive(legacySource(listOf(record))).checkpointOnShutdown().join()
            val nativeStartup = f.archive()

            nativeStartup.activateSealed().join()
            val activated = record.copy(revision = 1, phase = NativeGravePhase.ACTIVE)
            f.store.loadAll().join() shouldBe listOf(activated)
            f.sealed() shouldBe false

            nativeStartup.activateSealed().join()
            f.store.loadAll().join() shouldBe listOf(activated)
        }
    }

    test("partial activation retry recognizes activated and expired hashes without applying them twice") {
        LegacyHandoffFixture().use { f ->
            val live = legacyRecord()
            val expired = legacyRecord().copy(expiresAt = LEGACY_CREATED + 1_000)
            val retry = legacyRecord()
            f.archive(legacySource(listOf(live, expired, retry))).checkpointOnShutdown().join()
            var reject = true
            val failing = object : NativeGraveRepository by f.store {
                override fun replace(expected: NativeGraveRecord, next: NativeGraveRecord): CompletableFuture<Boolean> {
                    if (expected.id == retry.id && reject) {
                        reject = false
                        return CompletableFuture.failedFuture(IllegalStateException("activation retry fixture"))
                    }
                    return f.store.replace(expected, next)
                }
            }

            shouldThrow<CompletionException> { f.archive(repository = failing).activateSealed().join() }
            val active = live.copy(revision = 1, phase = NativeGravePhase.ACTIVE)
            val tombstone = expired.copy(revision = 1, phase = NativeGravePhase.CLAIMED, items = emptyList(), experience = 0)
            f.store.loadAll().join().associateBy { it.id } shouldBe listOf(active, tombstone, retry).associateBy { it.id }
            f.sealed() shouldBe true

            f.archive().activateSealed().join()
            f.store.loadAll().join().associateBy { it.id } shouldBe
                listOf(active, tombstone, retry.copy(revision = 1, phase = NativeGravePhase.ACTIVE)).associateBy { it.id }
            f.sealed() shouldBe false
        }
    }

    test("changed items or metadata at the same revision fail the seal check and preserve imported ownership") {
        LegacyHandoffFixture().use { f ->
            val record = legacyRecord()
            f.archive(legacySource(listOf(record))).checkpointOnShutdown().join()
            listOf(record.copy(items = listOf("BAUG")), record.copy(x = record.x + 5, experience = record.experience + 1)).forEach { changed ->
                InMemoryNativeGraveRepository().use { changedStore ->
                    changedStore.insert(changed).join() shouldBe true

                    val failure = shouldThrow<CompletionException> {
                        f.archive(repository = changedStore).activateSealed().join()
                    }

                    failure.cause!!.message!!.contains("payload differs") shouldBe true
                    changedStore.loadAll().join() shouldBe listOf(changed)
                    f.sealed() shouldBe true
                }
            }
        }
    }
})

private class LegacyHandoffFixture : AutoCloseable {
    private val root: Path = Files.createTempDirectory("arc-legacy-grave-handoff-")
    private val worker = BoundedAsyncExecutor("legacy-grave-handoff-test", 1, 16)
    val store = InMemoryNativeGraveRepository()

    fun archive(legacy: GravesXAccess? = null, repository: NativeGraveRepository = store) = LegacyGravesArchive(
        root, "classic_survival", legacy, CompletableFuture.completedFuture(repository), worker,
    )

    fun sealed(): Boolean = Files.exists(root.resolve("data/native-graves-legacy-handoff.json"))

    override fun close() {
        worker.closeAsync().join()
        store.closeAsync().join()
        root.toFile().deleteRecursively()
    }
}

private fun legacySource(records: List<NativeGraveRecord>): GravesXAccess = mockk {
    every { exportLoadedPlayerGraves() } returns records
}

private const val LEGACY_CREATED = 1_700_000_000_000L

private fun legacyRecord() = NativeGraveRecord(
    id = UUID.randomUUID(), ownerId = UUID.randomUUID(), ownerName = "LegacyOwner", server = "classic_survival",
    world = "world_nether", x = 97, y = 70, z = -123, createdAt = LEGACY_CREATED, expiresAt = Long.MAX_VALUE,
    experience = 43, items = listOf("AQID", "BAUGBw=="), phase = NativeGravePhase.IMPORTED,
)
