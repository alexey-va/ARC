package ru.arc.landsui

import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import me.angeschossen.lands.api.LandsIntegration
import me.angeschossen.lands.api.land.Land
import me.angeschossen.lands.api.player.LandPlayer
import me.angeschossen.lands.api.player.Selection
import me.angeschossen.lands.api.player.claiming.ClaimResult
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.Tasks
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.concurrent.CompletableFuture

class ClaimBlockToolTest {
    private fun settings() = LandsUiSettings(
        true,
        12,
        mapOf(
            "claim-place-working" to "working",
            "claim-place-failed" to "failed",
            "claim-place-done" to "done",
            "claim-place-selection-active" to "selection",
        ),
    )

    @org.junit.jupiter.api.Test
    fun `selected existing land claims free chunk without creating land and keeps item on success and failure`() {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("claim-block-tool-test")
            Tasks.install(BukkitTaskScheduler(plugin))
            val player = paper.addPlayer("ClaimOwner")
            val item = ItemStack(Material.GOLD_BLOCK, 5)
            player.inventory.setItemInMainHand(item)
            val target = player.location.clone()
            val lands = mockk<LandsIntegration>()
            val landPlayer = mockk<LandPlayer>(relaxed = true)
            val selected = mockk<Land>(relaxed = true)
            every { lands.getWorld(player.world) } returns mockk(relaxed = true)
            every { lands.getLandPlayer(player.uniqueId) } returns landPlayer
            every { landPlayer.selection } returns null
            every { landPlayer.getEditLand(false) } returns selected
            every { selected.exists() } returns true
            every { selected.defaultArea.hasRoleFlag(landPlayer, any(), Material.GRASS_BLOCK, false) } returns true
            every { lands.getLandByUnloadedChunk(target.world, target.blockX shr 4, target.blockZ shr 4) } returns null

            val firstClaim = CompletableFuture<ClaimResult>()
            val secondClaim = CompletableFuture<ClaimResult>()
            val firstSelection = mockk<Selection>(relaxed = true)
            val secondSelection = mockk<Selection>(relaxed = true)
            mockkStatic(Selection::class, Land::class)
            try {
                every { Selection.of(landPlayer, false, false, true) } returnsMany listOf(firstSelection, secondSelection)
                every { firstSelection.claim(selected, false, true) } returns firstClaim
                every { secondSelection.claim(selected, false, true) } returns secondClaim

                // Reproduce the runtime's broken Bukkit Player overload; only LandPlayer is valid.
                every { selected.defaultArea.hasRoleFlag(player, any(), any(), any()) } throws ClassCastException("Player is not LandPlayer")
                val tool = ClaimBlockTool(settings(), lands)
                try {
                    // A failed preflight must release pending state so the next click can retry.
                    every { selected.defaultArea.hasRoleFlag(landPlayer, any(), Material.GRASS_BLOCK, false) } throws IllegalStateException("preflight failed")
                    tool.place(player, target, radius = 1)
                    paper.performTicks(3)
                    verify(exactly = 0) { firstSelection.claim(selected, false, true) }
                    every { selected.defaultArea.hasRoleFlag(landPlayer, any(), Material.GRASS_BLOCK, false) } returns true
                    tool.place(player, target, radius = 1)
                    paper.performTicks(3)
                    verify(exactly = 1) { firstSelection.claim(selected, false, true) }
                    verify(exactly = 0) { Land.of(any(), any(), any(), any(), any(), any()) }

                    // A second request while the first native claim is pending is ignored.
                    tool.place(player, target, radius = 1)
                    paper.performTicks(3)
                    verify(exactly = 1) { firstSelection.claim(selected, false, true) }
                    player.inventory.itemInMainHand.amount shouldBe 5

                    firstClaim.complete(ClaimResult.SUCCESS)
                    paper.performTicks(3)
                    player.inventory.itemInMainHand.amount shouldBe 5

                    tool.place(player, target, radius = 1)
                    paper.performTicks(3)
                    verify(exactly = 1) { secondSelection.claim(selected, false, true) }
                    secondClaim.complete(ClaimResult.FAILED)
                    paper.performTicks(3)
                    player.inventory.itemInMainHand.amount shouldBe 5
                } finally {
                    tool.close()
                }
            } finally {
                unmockkStatic(Selection::class, Land::class)
                Tasks.reset()
            }
        }
    }
}
