package ru.arc.hooks

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import net.william278.huskhomes.event.HomeCreateEvent
import net.william278.huskhomes.position.Home
import net.william278.huskhomes.position.World as HuskWorld
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Player
import ru.arc.core.TestTaskScheduler
import ru.arc.onboarding.OnboardingService
import java.util.UUID
import java.util.concurrent.CompletableFuture

class HuskHomeCreationConfirmationTest : FreeSpec({
    "an absent or old home is retried without blocking and the exact persisted result confirms once" {
        HomeConfirmationFixture().use { fixture ->
            fixture.observe()
            fixture.scheduler.tick()
            fixture.reads.size shouldBe 1
            fixture.confirmed.size shouldBe 0
            var otherGameplayRan = false
            fixture.scheduler.runSync { otherGameplayRan = true }
            fixture.scheduler.executeImmediate()
            otherGameplayRan shouldBe true
            fixture.reads.single().isDone shouldBe false

            fixture.complete(null)
            fixture.scheduler.tick(9)
            fixture.reads.size shouldBe 1
            fixture.scheduler.tick()
            fixture.complete(fixture.home(fixture.expected.copy(x = 999.0)))
            fixture.scheduler.tick(10)
            val persisted = fixture.home()
            fixture.complete(persisted)
            fixture.scheduler.tick(100)
            fixture.reads.size shouldBe 3
            fixture.confirmed shouldBe listOf(persisted)
            fixture.diagnostics shouldBe emptyList()
        }
    }

    "a newer sethome supersedes an in-flight read even when the same home uses different case" {
        HomeConfirmationFixture().use { fixture ->
            fixture.observe()
            fixture.scheduler.tick()
            val obsolete = fixture.reads.single()
            val replacement = fixture.expected.copy(x = 64.0)
            fixture.observe(name = "HOME", expected = replacement)
            fixture.scheduler.tick()
            obsolete.complete(fixture.home())
            fixture.scheduler.executeImmediate()
            fixture.confirmed shouldBe emptyList()
            fixture.complete(fixture.home())
            fixture.scheduler.tick(10)
            val persisted = fixture.home(replacement)
            fixture.complete(persisted)
            fixture.confirmed shouldBe listOf(persisted)
            fixture.reads.size shouldBe 3
            fixture.diagnostics shouldBe emptyList()
        }
    }

    "quit player replacement and hook close discard late reads and scheduled retries" {
        listOf("quit", "offline", "replacement", "close").forEach { exit ->
            HomeConfirmationFixture().use { fixture ->
                fixture.observe()
                fixture.scheduler.tick()
                when (exit) {
                    "quit" -> fixture.confirmation.forget(fixture.playerId)
                    "offline" -> fixture.online = false
                    "replacement" -> fixture.currentPlayer = mockk()
                    "close" -> fixture.confirmation.close()
                }
                fixture.complete(fixture.home())
                fixture.scheduler.tick(100)
                fixture.confirmed shouldBe emptyList()
                fixture.reads.size shouldBe 1
                fixture.diagnostics shouldBe emptyList()
                fixture.scheduler.pendingCount() shouldBe 0
            }
        }
        HomeConfirmationFixture().use { fixture ->
            fixture.observe()
            fixture.confirmation.close()
            fixture.scheduler.tick(100)
            fixture.reads shouldBe emptyList()
        }
    }

    "missing homes and read failures stop after twenty serial attempts and report only once" {
        listOf(false, true).forEach { failReads ->
            HomeConfirmationFixture().use { fixture ->
                fixture.observe()
                val failure = IllegalStateException("Synthetic read failure")
                repeat(20) { index ->
                    fixture.scheduler.tick(if (index == 0) 1 else 10)
                    fixture.reads.size shouldBe index + 1
                    if (failReads) fixture.reads.last().completeExceptionally(failure)
                    else fixture.reads.last().complete(null)
                    fixture.scheduler.executeImmediate()
                }
                fixture.scheduler.tick(100)
                fixture.reads.size shouldBe 20
                fixture.confirmed shouldBe emptyList()
                fixture.diagnostics.size shouldBe 1
                fixture.diagnostics.single().first.contains("attempts=20") shouldBe true
                fixture.diagnostics.single().first.contains("home=home") shouldBe true
                fixture.diagnostics.single().second shouldBe if (failReads) failure else null
                fixture.scheduler.pendingCount() shouldBe 0
            }
        }
    }

    "the event keeps legacy respawn without a quest and forwards the original run after a delayed confirmation" {
        mockkStatic(Bukkit::class)
        mockkObject(OnboardingService)
        try {
            listOf<String?>(null, UUID.randomUUID().toString()).forEach { capturedRun ->
                HomeConfirmationFixture().use { fixture ->
                    var currentRun = capturedRun
                    val recordedRuns = mutableListOf<String?>()
                    val world = mockk<World> { every { name } returns "vanilla" }
                    every { Bukkit.getWorld("vanilla") } returns world
                    every { OnboardingService.currentRunId(fixture.playerId) } answers { currentRun }
                    every { OnboardingService.recordHomeCreated(fixture.player, "vanilla", 32, 48, any(), any()) } answers {
                        recordedRuns += arg<String?>(5)
                    }
                    justRun { fixture.player.setRespawnLocation(any<Location>(), true) }
                    val event = mockk<HomeCreateEvent> {
                        every { owner.uuid } returns fixture.playerId
                        every { name } returns "home"
                        every { position } returns fixture.home()
                    }
                    HuskHomesHook(fixture.confirmation) { _, _ -> }.use { hook ->
                        hook.homeCreate(event)
                        fixture.scheduler.tick()
                        fixture.complete(null)
                        currentRun = UUID.randomUUID().toString()
                        fixture.scheduler.tick(10)
                        fixture.complete(fixture.home())
                        recordedRuns shouldBe listOf(capturedRun)
                        verify(exactly = 1) { fixture.player.setRespawnLocation(any<Location>(), true) }
                    }
                }
            }
        } finally {
            unmockkObject(OnboardingService)
            unmockkStatic(Bukkit::class)
        }
    }
})

private class HomeConfirmationFixture : AutoCloseable {
    val scheduler = TestTaskScheduler()
    val playerId = UUID.randomUUID()
    var online = true
    val player = mockk<Player> {
        every { uniqueId } returns playerId
        every { name } returns "HomeConfirmationTest"
        every { isOnline } answers { online }
    }
    var currentPlayer: Player? = player
    val expected = HomePositionSnapshot("survival", "vanilla", 32.0, 64.0, 48.0)
    val reads = mutableListOf<CompletableFuture<Home?>>()
    val confirmed = mutableListOf<Home>()
    val diagnostics = mutableListOf<Pair<String, Throwable?>>()
    val confirmation = HuskHomeCreationConfirmation(
        scheduler = { scheduler },
        livePlayer = { if (it == playerId) currentPlayer else null },
        readHome = { _, _ -> CompletableFuture<Home?>().also(reads::add) },
        diagnostic = { message, failure -> diagnostics += message to failure },
    )

    fun observe(name: String = "home", expected: HomePositionSnapshot = this.expected) =
        confirmation.observe(playerId, name, expected, null) { _, home -> confirmed += home }

    fun complete(home: Home?) {
        reads.last().complete(home)
        scheduler.executeImmediate()
    }

    fun home(position: HomePositionSnapshot = expected): Home = mockk {
        every { server } returns position.server
        every { world } returns HuskWorld.from(position.world)
        every { x } returns position.x
        every { y } returns position.y
        every { z } returns position.z
        every { yaw } returns 0f
        every { pitch } returns 0f
    }

    override fun close() = confirmation.close()
}
