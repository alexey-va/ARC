package ru.arc.hooks.elitemobs

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

class DungeonBestiaryTest : FreeSpec({
    "kill credit requires finite positive damage from an online non-spectator dungeon member" {
        bestiaryKillCredit(1.0, online = true, survival = true, sameWorld = true, member = true) shouldBe true

        listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach { damage ->
            bestiaryKillCredit(damage, online = true, survival = true, sameWorld = true, member = true) shouldBe false
        }
        bestiaryKillCredit(1.0, online = false, survival = true, sameWorld = true, member = true) shouldBe false
        bestiaryKillCredit(1.0, online = true, survival = false, sameWorld = true, member = true) shouldBe false
        bestiaryKillCredit(1.0, online = true, survival = true, sameWorld = false, member = true) shouldBe false
        bestiaryKillCredit(1.0, online = true, survival = true, sameWorld = true, member = false) shouldBe false
    }

    "waits for durable discovery before notifying or loading and deduplicates repeated discoveries" {
        MockBukkitTestRuntime.open().use { paper ->
            val player = paper.addPlayer("bestiary-pending")
            val mob = bestiaryEntry("crypt_hydra.yml")
            val store = mockk<DungeonBestiaryProgressStore>()
            val stored = CompletableFuture<Boolean>()
            every { store.discover(player.uniqueId, mob.id) } returns stored
            every { store.load(player.uniqueId) } returns CompletableFuture.completedFuture(setOf(mob.id))
            val scheduler = TestTaskScheduler()
            val bestiary = DungeonBestiary(testDungeon(), { store }, LifecycleTaskScope(scheduler))

            try {
                bestiary.discover(player, mob)
                // A duplicate event while the durable write is pending must share the same write.
                bestiary.discover(player, mob)
                val loaded = bestiary.loadProgress(player.uniqueId)

                verify(exactly = 1) { store.discover(player.uniqueId, mob.id) }
                verify(exactly = 0) { store.load(player.uniqueId) }
                loaded.isDone shouldBe false
                player.nextComponentMessage() shouldBe null

                stored.complete(true)
                scheduler.executeImmediate()
                loaded.join() shouldBe setOf(mob.id)
                PlainTextComponentSerializer.plainText().serialize(requireNotNull(player.nextComponentMessage())) shouldBe
                    "bestiary.discovered"
                player.nextComponentMessage() shouldBe null

                bestiary.discover(player, mob)
                verify(exactly = 1) { store.discover(player.uniqueId, mob.id) }
                verify(exactly = 1) { store.load(player.uniqueId) }
            } finally {
                bestiary.close()
            }
        }
    }

    "retries storage failures without announcing discovery or claiming progress" {
        MockBukkitTestRuntime.open().use { paper ->
            val player = paper.addPlayer("bestiary-failure")
            val mob = bestiaryEntry("crypt_hydra.yml")
            val store = mockk<DungeonBestiaryProgressStore>()
            every { store.discover(player.uniqueId, mob.id) } returnsMany List(3) {
                CompletableFuture.failedFuture<Boolean>(IllegalStateException("storage unavailable"))
            }
            every { store.load(player.uniqueId) } returns CompletableFuture.completedFuture(emptySet())
            val scheduler = TestTaskScheduler()
            val bestiary = DungeonBestiary(testDungeon(), { store }, LifecycleTaskScope(scheduler))

            try {
                bestiary.discover(player, mob)
                scheduler.executeImmediate()
                val loaded = bestiary.loadProgress(player.uniqueId)
                loaded.isDone shouldBe false
                player.nextComponentMessage() shouldBe null
                verify(exactly = 1) { store.discover(player.uniqueId, mob.id) }

                scheduler.tick(39)
                verify(exactly = 1) { store.discover(player.uniqueId, mob.id) }
                scheduler.tick(1)
                verify(exactly = 2) { store.discover(player.uniqueId, mob.id) }
                scheduler.executeImmediate()
                player.nextComponentMessage() shouldBe null

                scheduler.tick(199)
                verify(exactly = 2) { store.discover(player.uniqueId, mob.id) }
                scheduler.tick(1)
                verify(exactly = 3) { store.discover(player.uniqueId, mob.id) }
                scheduler.executeImmediate()
                loaded.isCompletedExceptionally shouldBe true
                shouldThrow<CompletionException> { loaded.join() }
                PlainTextComponentSerializer.plainText().serialize(requireNotNull(player.nextComponentMessage())) shouldBe
                    "bestiary.save-failed"
                player.nextComponentMessage() shouldBe null
                verify(exactly = 0) { store.load(player.uniqueId) }
            } finally {
                bestiary.close()
            }
        }
    }

    "close fails pending progress reads and fences late Redis completions" {
        MockBukkitTestRuntime.open().use { paper ->
            val player = paper.addPlayer("bestiary-close")
            val mob = bestiaryEntry("crypt_hydra.yml")
            val store = mockk<DungeonBestiaryProgressStore>()
            val stored = CompletableFuture<Boolean>()
            every { store.discover(player.uniqueId, mob.id) } returns stored
            every { store.load(player.uniqueId) } returns CompletableFuture.completedFuture(setOf(mob.id))
            val bestiary = DungeonBestiary(testDungeon(), { store }, LifecycleTaskScope(TestTaskScheduler()))

            bestiary.discover(player, mob)
            val loaded = bestiary.loadProgress(player.uniqueId)
            bestiary.close()

            loaded.isCompletedExceptionally shouldBe true
            stored.complete(true)
            player.nextComponentMessage() shouldBe null
            verify(exactly = 0) { store.load(player.uniqueId) }
        }
    }


    "panel progress stays unknown until a full load and merges a newer local discovery" {
        MockBukkitTestRuntime.open().use { paper ->
            val player = paper.addPlayer("bestiary-progress-merge")
            val mob = bestiaryEntry("crypt_hydra.yml")
            val other = bestiaryEntry("crypt_wraith.yml")
            val store = mockk<DungeonBestiaryProgressStore>()
            val loaded = CompletableFuture<Set<String>>()
            val stored = CompletableFuture<Boolean>()
            every { store.load(player.uniqueId) } returns loaded
            every { store.discover(player.uniqueId, mob.id) } returns stored
            val scheduler = TestTaskScheduler()
            val bestiary = DungeonBestiary(
                testDungeon(), { store }, LifecycleTaskScope(scheduler),
                catalogEntries = { listOf(mob, mob, other) },
            )

            try {
                bestiary.progress(player.uniqueId, "crypt_dungeon") shouldBe DungeonBestiaryProgress(null, 2)
                bestiary.progress(player.uniqueId, "crypt_dungeon") shouldBe DungeonBestiaryProgress(null, 2)
                verify(exactly = 1) { store.load(player.uniqueId) }

                bestiary.discover(player, mob)
                stored.complete(true)
                scheduler.executeImmediate()
                // The local kill cache is only a lower bound, so the panel remains unknown.
                bestiary.progress(player.uniqueId, "crypt_dungeon") shouldBe DungeonBestiaryProgress(null, 2)

                loaded.complete(emptySet())
                scheduler.executeImmediate()
                bestiary.progress(player.uniqueId, "crypt_dungeon") shouldBe DungeonBestiaryProgress(1, 2)
            } finally {
                bestiary.close()
            }
        }
    }

    "failed panel preloads are throttled and a later snapshot retries" {
        MockBukkitTestRuntime.open().use { paper ->
            val player = paper.addPlayer("bestiary-progress-retry")
            val mob = bestiaryEntry("crypt_hydra.yml")
            val store = mockk<DungeonBestiaryProgressStore>()
            val retryRead = CompletableFuture<Set<String>>()
            every { store.load(player.uniqueId) } returnsMany listOf(
                CompletableFuture.failedFuture(IllegalStateException("storage unavailable")),
                retryRead,
            )
            var now = 0L
            val scheduler = TestTaskScheduler()
            val bestiary = DungeonBestiary(
                testDungeon(), { store }, LifecycleTaskScope(scheduler),
                catalogEntries = { listOf(mob) }, nowMillis = { now },
            )

            try {
                bestiary.progress(player.uniqueId, "crypt_dungeon") shouldBe DungeonBestiaryProgress(null, 1)
                scheduler.executeImmediate()
                bestiary.progress(player.uniqueId, "crypt_dungeon") shouldBe DungeonBestiaryProgress(null, 1)
                now = 29_999L
                bestiary.progress(player.uniqueId, "crypt_dungeon") shouldBe DungeonBestiaryProgress(null, 1)
                verify(exactly = 1) { store.load(player.uniqueId) }

                now = 30_000L
                bestiary.progress(player.uniqueId, "crypt_dungeon") shouldBe DungeonBestiaryProgress(null, 1)
                verify(exactly = 2) { store.load(player.uniqueId) }
                retryRead.complete(setOf(mob.id, "removed_from_catalog.yml"))
                scheduler.executeImmediate()
                bestiary.progress(player.uniqueId, "crypt_dungeon") shouldBe DungeonBestiaryProgress(1, 1)
            } finally {
                bestiary.close()
            }
        }
    }

    "manual retry bypasses preload backoff and missing storage leaves the panel usable" {
        MockBukkitTestRuntime.open().use { paper ->
            val player = paper.addPlayer("bestiary-manual-retry")
            val mob = bestiaryEntry("crypt_hydra.yml")
            val store = mockk<DungeonBestiaryProgressStore>()
            every { store.load(player.uniqueId) } returnsMany listOf(
                CompletableFuture.failedFuture(IllegalStateException("storage unavailable")),
                CompletableFuture.completedFuture(setOf(mob.id)),
            )
            val scheduler = TestTaskScheduler()
            val bestiary = DungeonBestiary(testDungeon(), { store }, LifecycleTaskScope(scheduler),
                catalogEntries = { listOf(mob) }, nowMillis = { 0L })
            try {
                bestiary.preload(player.uniqueId)
                scheduler.executeImmediate()
                val retry = bestiary.loadProgress(player.uniqueId)
                scheduler.executeImmediate()
                retry.join() shouldBe setOf(mob.id)
                verify(exactly = 2) { store.load(player.uniqueId) }
            } finally { bestiary.close() }
            var availableStore: DungeonBestiaryProgressStore? = null
            DungeonBestiary(testDungeon(), { availableStore }, LifecycleTaskScope(scheduler), catalogEntries = { listOf(mob) }).use {
                it.progress(player.uniqueId, "crypt") shouldBe DungeonBestiaryProgress(null, 1)
                it.discover(player, mob)
                player.nextComponentMessage() shouldBe null
                availableStore = store
                it.preload(player.uniqueId)
                scheduler.executeImmediate()
                it.progress(player.uniqueId, "crypt") shouldBe DungeonBestiaryProgress(1, 1)
            }
        }
    }

    "quit and close fence stale progress callbacks" {
        MockBukkitTestRuntime.open().use { paper ->
            val player = paper.addPlayer("bestiary-progress-stale")
            val mob = bestiaryEntry("crypt_hydra.yml")
            val store = mockk<DungeonBestiaryProgressStore>()
            val quitRead = CompletableFuture<Set<String>>()
            val closeRead = CompletableFuture<Set<String>>()
            every { store.load(player.uniqueId) } returnsMany listOf(quitRead, closeRead)
            val scheduler = TestTaskScheduler()
            val bestiary = DungeonBestiary(
                testDungeon(), { store }, LifecycleTaskScope(scheduler), catalogEntries = { listOf(mob) },
            )

            try {
                bestiary.progress(player.uniqueId, "crypt_dungeon") shouldBe DungeonBestiaryProgress(null, 1)
                bestiary.forget(player.uniqueId)
                quitRead.complete(setOf(mob.id))
                scheduler.executeImmediate()

                bestiary.progress(player.uniqueId, "crypt_dungeon") shouldBe DungeonBestiaryProgress(null, 1)
                verify(exactly = 2) { store.load(player.uniqueId) }
                bestiary.close()
                closeRead.complete(setOf(mob.id))
                scheduler.executeImmediate()
                bestiary.loadProgress(player.uniqueId).isCompletedExceptionally shouldBe true
                verify(exactly = 2) { store.load(player.uniqueId) }
            } finally {
                bestiary.close()
            }
        }
    }
})

private fun bestiaryEntry(id: String): BestiaryMob = BestiaryMob(
    id, "Криптовая гидра", "Босс", "40", emptyList(), emptyList(), emptyList(),
)

private fun testDungeon(): EMDungeonQol = mockk<EMDungeonQol>(relaxed = true).also { dungeon ->
    every { dungeon.text(any(), any(), *anyVararg()) } answers { Component.text(firstArg<String>()) }
}
