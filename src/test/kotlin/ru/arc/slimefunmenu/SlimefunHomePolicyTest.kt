package ru.arc.slimefunmenu

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import net.william278.huskhomes.event.HomeCreateEvent
import net.william278.huskhomes.position.Position
import net.william278.huskhomes.user.CommandUser
import net.william278.huskhomes.user.User
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import ru.arc.config.ArcRuntimeProfile
import ru.arc.paper.testing.MockBukkitTestRuntime

class SlimefunHomePolicyTest : FunSpec({
    test("matches direct, plugin-namespaced and root-subcommand home creation routes only") {
        listOf(
            "/sethome",
            "/sethome cabin",
            "/cmi:sethome cabin",
            "/huskhomes:sethome cabin",
            "/cmi sethome cabin",
            "/cmi:cmi sethome cabin",
            "/huskhomes sethome cabin",
            "/huskhomes:huskhomes sethome cabin",
            "/hh sethome cabin",
        ).forEach { SlimefunHomePolicy.isHomeCreationCommand(it) shouldBe true }

        listOf(
            "/home",
            "/huskhomes:home cabin",
            "/huskhomes:homes",
            "/cmi home cabin",
            "/is sethome",
            "/island sethome",
            "/spawn",
        ).forEach { SlimefunHomePolicy.isHomeCreationCommand(it) shouldBe false }
    }

    test("cancels home commands and native HuskHomes creation only on Slimefun, then unregisters") {
        MockBukkitTestRuntime.open().use { runtime ->
            val plugin = runtime.createSimplePlugin("ArcHomePolicyTest")
            val player = runtime.addPlayer("SkyTester")
            var profile = ArcRuntimeProfile.FULL
            val policy = SlimefunHomePolicy(plugin, { profile }, huskHomesEnabled = { true })

            val allowedOnFull = runtime.callEvent(PlayerCommandPreprocessEvent(player, "/sethome"))
            allowedOnFull.isCancelled shouldBe false

            val fullProfileHomeEvent = runtime.callEvent(homeCreateEvent(player))
            fullProfileHomeEvent.isCancelled shouldBe false

            profile = ArcRuntimeProfile.SLIMEFUN
            listOf("/sethome cabin", "/cmi sethome cabin", "/huskhomes:sethome cabin").forEach { command ->
                val event = runtime.callEvent(PlayerCommandPreprocessEvent(player, command))
                event.isCancelled shouldBe true
                PlainTextComponentSerializer.plainText().serialize(checkNotNull(player.nextComponentMessage())) shouldBe
                    SlimefunHomePolicy.BLOCKED_TEXT
            }

            val islandHome = runtime.callEvent(PlayerCommandPreprocessEvent(player, "/is home"))
            islandHome.isCancelled shouldBe false
            val homeEvent = runtime.callEvent(homeCreateEvent(player))
            homeEvent.isCancelled shouldBe true
            PlainTextComponentSerializer.plainText().serialize(checkNotNull(player.nextComponentMessage())) shouldBe
                SlimefunHomePolicy.BLOCKED_TEXT

            policy.close()
            runtime.callEvent(PlayerCommandPreprocessEvent(player, "/sethome")).isCancelled shouldBe false
            runtime.callEvent(homeCreateEvent(player)).isCancelled shouldBe false
        }
    }

    test("does not register the HuskHomes event guard when HuskHomes is unavailable") {
        MockBukkitTestRuntime.open().use { runtime ->
            val plugin = runtime.createSimplePlugin("ArcHomePolicyWithoutHusk")
            val player = runtime.addPlayer("SkyTester")
            val policy = SlimefunHomePolicy(plugin, { ArcRuntimeProfile.SLIMEFUN }, huskHomesEnabled = { false })

            runtime.callEvent(homeCreateEvent(player)).isCancelled shouldBe false
            policy.close()
        }
    }
})

private fun homeCreateEvent(player: Player): HomeCreateEvent {
    val creator = mockk<CommandUser> {
        every { audience } returns player
    }
    return HomeCreateEvent(
        User.of(player.uniqueId, player.name),
        "cabin",
        mockk<Position>(relaxed = true),
        creator,
    )
}
