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
            val bestiary = DungeonBestiary(testDungeon(), store, LifecycleTaskScope(scheduler))

            try {
                bestiary.discover(player, mob)
                // Quit clears the local cache; the in-flight durable write still owns this key.
                bestiary.forget(player.uniqueId)
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
            val bestiary = DungeonBestiary(testDungeon(), store, LifecycleTaskScope(scheduler))

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
            val bestiary = DungeonBestiary(testDungeon(), store, LifecycleTaskScope(TestTaskScheduler()))

            bestiary.discover(player, mob)
            val loaded = bestiary.loadProgress(player.uniqueId)
            bestiary.close()

            loaded.isCompletedExceptionally shouldBe true
            stored.complete(true)
            player.nextComponentMessage() shouldBe null
            verify(exactly = 0) { store.load(player.uniqueId) }
        }
    }
})

private fun bestiaryEntry(id: String): BestiaryMob = BestiaryMob(
    id, "Криптовая гидра", "Босс", "40", emptyList(), emptyList(), emptyList(),
)

private fun testDungeon(): EMDungeonQol = mockk<EMDungeonQol>(relaxed = true).also { dungeon ->
    every { dungeon.text(any(), any(), *anyVararg()) } answers { Component.text(firstArg<String>()) }
}
