package ru.arc.homemarkers

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.event.player.PlayerToggleSneakEvent
import org.bukkit.event.player.PlayerQuitEvent
import ru.arc.core.Tasks
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.concurrent.CompletableFuture

class HomeMarkerControllerTest : StringSpec({
    "sneak transition updates input immediately using the event state and ignores cancelled transitions" {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("HomeMarkersTest")
            val player = paper.addPlayer("Owner")
            val scheduler = TestTaskScheduler()
            val gateway = mockk<HomeMarkerGateway>()
            val scene = mockk<HomeMarkerScene>(relaxed = true)
            Tasks.withScheduler(scheduler) {
                HomeMarkerController(plugin, HomeMarkerSettings(), gateway, scene).use {
                    player.isSneaking shouldBe false
                    plugin.server.pluginManager.callEvent(PlayerToggleSneakEvent(player, true))
                    verify(exactly = 1) { scene.updateSneaking(player, true) }
                    val cancelled = PlayerToggleSneakEvent(player, false).apply { isCancelled = true }
                    plugin.server.pluginManager.callEvent(cancelled)
                    verify(exactly = 0) { scene.updateSneaking(player, false) }
                }
            }
        }
    }

    "late home lookup cannot restore a departed player's private scene" {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("HomeMarkersTest")
            val player = paper.addPlayer("Owner")
            val scheduler = TestTaskScheduler()
            val gateway = mockk<HomeMarkerGateway>()
            val scene = mockk<HomeMarkerScene>(relaxed = true)
            val pending = CompletableFuture<List<HomeMarker>>()
            val marker = HomeMarker(HomeMarkerKind.HOME, "home", player.uniqueId, "Home", player.world.name, 0.0, 64.0, 0.0)
            every { gateway.load(player) } returns pending
            Tasks.withScheduler(scheduler) {
                HomeMarkerController(plugin, HomeMarkerSettings(), gateway, scene).use { controller ->
                    scheduler.tick()
                    controller.onQuit(PlayerQuitEvent(player, net.kyori.adventure.text.Component.empty()))
                    pending.complete(listOf(marker))
                    scheduler.executeImmediate()
                    verify(exactly = 0) { scene.reconcile(player, listOf(marker)) }
                    verify { scene.clear(player.uniqueId) }
                }
                scheduler.timerCount() shouldBe 0
            }
        }
    }

    "a privacy change opens the fresh home state while a moved point is rejected" {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("HomeMarkersTest")
            val player = paper.addPlayer("Owner")
            player.isSneaking = true
            val scheduler = TestTaskScheduler()
            val gateway = mockk<HomeMarkerGateway>()
            val scene = mockk<HomeMarkerScene>(relaxed = true)
            val marker = HomeMarker(HomeMarkerKind.HOME, "home", player.uniqueId, "Home", player.world.name, 0.0, 64.0, 0.0)
            val publicMarker = marker.copy(isPublic = true)
            every { gateway.load(player) } returnsMany listOf(
                CompletableFuture.completedFuture(listOf(marker)),
                CompletableFuture.completedFuture(listOf(publicMarker)),
                CompletableFuture.completedFuture(listOf(publicMarker.copy(x = 2.0))),
            )
            every { gateway.openSettings(player, publicMarker) } returns true
            Tasks.withScheduler(scheduler) {
                HomeMarkerController(plugin, HomeMarkerSettings(), gateway, scene).use { controller ->
                    scheduler.tick()
                    scheduler.executeImmediate()
                    controller.activate(player, marker)
                    scheduler.executeImmediate()
                    verify(exactly = 1) { gateway.openSettings(player, publicMarker) }

                    controller.activate(player, publicMarker)
                    scheduler.executeImmediate()
                    verify(exactly = 1) { gateway.openSettings(any(), any()) }
                }
            }
        }
    }

    "accepted Shift click survives posture changes but still requires a fresh unchanged point" {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("HomeMarkersTest")
            val player = paper.addPlayer("Owner")
            player.isSneaking = true
            val scheduler = TestTaskScheduler()
            val gateway = mockk<HomeMarkerGateway>()
            val scene = mockk<HomeMarkerScene>(relaxed = true)
            val marker = HomeMarker(HomeMarkerKind.HOME, "home", player.uniqueId, "Home", player.world.name, 0.0, 64.0, 0.0)
            val initial = CompletableFuture.completedFuture(listOf(marker))
            val removed = CompletableFuture.completedFuture(emptyList<HomeMarker>())
            val acceptedClick = CompletableFuture<List<HomeMarker>>()
            every { gateway.load(player) } returnsMany listOf(initial, removed, acceptedClick)
            every { gateway.openSettings(player, marker) } returns true
            Tasks.withScheduler(scheduler) {
                HomeMarkerController(plugin, HomeMarkerSettings(), gateway, scene).use { controller ->
                    scheduler.tick()
                    scheduler.executeImmediate()
                    controller.activate(player, marker)
                    scheduler.executeImmediate()
                    verify(exactly = 0) { gateway.openSettings(any(), any()) }

                    controller.activate(player, marker)
                    player.isSneaking = false
                    player.teleport(player.location.apply { yaw = 180f; pitch = -80f })
                    acceptedClick.complete(listOf(marker))
                    scheduler.executeImmediate()
                    verify(exactly = 1) { gateway.openSettings(player, marker) }

                    controller.activate(player, marker)
                    verify(exactly = 3) { gateway.load(player) }
                }
            }
        }
    }
})
