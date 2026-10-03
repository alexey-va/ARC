package ru.arc.treasurechests

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.slot
import io.mockk.unmockkConstructor
import io.mockk.verify
import net.kyori.adventure.text.Component
import org.bukkit.FluidCollisionMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.block.BlockFace
import org.bukkit.entity.Item
import org.bukkit.entity.Player
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.util.RayTraceResult
import org.bukkit.util.Vector
import org.mockbukkit.mockbukkit.world.WorldMock
import ru.arc.config.TestConfig
import ru.arc.core.BukkitTaskScheduler
import ru.arc.paper.display.PacketBlockDisplay
import ru.arc.paper.display.PacketItemDisplay
import ru.arc.paper.display.PaperPacketDisplays
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.concurrent.ConcurrentHashMap

class TreasureHuntGrappleLifecycleTest : DescribeSpec({
    describe("spawn grapple item lifecycle") {
        it("grants one tagged hook to an Origin player without changing ordinary items") {
            MockBukkitTestRuntime.open().use { runtime ->
                val harness = harness(runtime)
                val origin = runtime.addSimpleWorld("rc_origin_spawn")
                val otherWorld = runtime.addSimpleWorld("survival")
                val player = runtime.addPlayer("origin-player")
                val otherPlayer = runtime.addPlayer("other-player")
                move(player, origin)
                move(otherPlayer, otherWorld)
                player.inventory.setItem(0, ItemStack(Material.DIAMOND, 4))
                player.inventory.setItem(1, ItemStack(Material.EMERALD, 2))
                val before = hotbarSnapshot(player)

                startHunt(harness.grapple, origin)

                isHook(player.inventory.getItem(2), harness.plugin) shouldBe true
                player.inventory.getItem(2)?.amount shouldBe 1
                taggedCount(player, harness.plugin) shouldBe 1
                hotbarSnapshot(player).take(2) shouldBe before.take(2)
                taggedCount(otherPlayer, harness.plugin) shouldBe 0
                harness.grapple.close()
            }
        }

        it("preserves a full hotbar and grants after a slot becomes free") {
            MockBukkitTestRuntime.open().use { runtime ->
                val harness = harness(runtime)
                val origin = runtime.addSimpleWorld("rc_origin_spawn")
                val player = runtime.addPlayer("full-hotbar")
                move(player, origin)
                fillHotbar(player)
                val before = hotbarSnapshot(player)

                startHunt(harness.grapple, origin)

                hotbarSnapshot(player) shouldBe before
                taggedCount(player, harness.plugin) shouldBe 0
                player.inventory.setItem(5, null)
                runtime.performTicks(20)

                isHook(player.inventory.getItem(5), harness.plugin) shouldBe true
                taggedCount(player, harness.plugin) shouldBe 1
                hotbarExcept(player, 5) shouldBe beforeExcept(before, 5)
                harness.grapple.close()
            }
        }

        it("removes only its hook on disable reload and when the active hunt stops") {
            MockBukkitTestRuntime.open().use { runtime ->
                val harness = harness(runtime)
                val origin = runtime.addSimpleWorld("rc_origin_spawn")
                val player = runtime.addPlayer("reload-player")
                move(player, origin)
                player.inventory.setItem(0, ItemStack(Material.DIAMOND, 3))
                startHunt(harness.grapple, origin)

                harness.grapple.reloadConfig(settings(enabled = false))

                taggedCount(player, harness.plugin) shouldBe 0
                player.inventory.getItem(0) shouldBe ItemStack(Material.DIAMOND, 3)
                harness.grapple.reloadConfig(settings())
                taggedCount(player, harness.plugin) shouldBe 1
                harness.grapple.onActiveHuntsChanged(emptyList())

                taggedCount(player, harness.plugin) shouldBe 0
                player.inventory.getItem(0) shouldBe ItemStack(Material.DIAMOND, 3)
                harness.grapple.close()
            }
        }

        it("cancels dropping the hook but leaves ordinary item drops alone") {
            MockBukkitTestRuntime.open().use { runtime ->
                val harness = harness(runtime)
                val origin = runtime.addSimpleWorld("rc_origin_spawn")
                val player = runtime.addPlayer("drop-player")
                move(player, origin)
                startHunt(harness.grapple, origin)
                val hook = requireNotNull(player.inventory.getItem(0))
                val protectedDrop = droppedItem(hook)
                val ordinaryDrop = droppedItem(ItemStack(Material.DIAMOND))

                runtime.callEvent(PlayerDropItemEvent(player, protectedDrop)).isCancelled shouldBe true
                runtime.callEvent(PlayerDropItemEvent(player, ordinaryDrop)).isCancelled shouldBe false
                harness.grapple.close()
            }
        }

        it("removes the hook after leaving Origin and preserves other inventory") {
            MockBukkitTestRuntime.open().use { runtime ->
                val harness = harness(runtime)
                val origin = runtime.addSimpleWorld("rc_origin_spawn")
                val elsewhere = runtime.addSimpleWorld("survival")
                val player = runtime.addPlayer("traveller")
                move(player, origin)
                player.inventory.setItem(0, ItemStack(Material.DIAMOND, 2))
                startHunt(harness.grapple, origin)
                move(player, elsewhere)

                harness.grapple.onWorldChange(PlayerChangedWorldEvent(player, origin))

                taggedCount(player, harness.plugin) shouldBe 0
                player.inventory.getItem(0) shouldBe ItemStack(Material.DIAMOND, 2)
                harness.grapple.close()
            }
        }

        it("removes the departing player's hook on quit without touching ordinary items") {
            MockBukkitTestRuntime.open().use { runtime ->
                val harness = harness(runtime)
                val origin = runtime.addSimpleWorld("rc_origin_spawn")
                val player = runtime.addPlayer("departing-player")
                move(player, origin)
                player.inventory.setItem(0, ItemStack(Material.DIAMOND, 2))
                startHunt(harness.grapple, origin)

                runtime.callEvent(PlayerQuitEvent(player, Component.empty()))

                taggedCount(player, harness.plugin) shouldBe 0
                player.inventory.getItem(0) shouldBe ItemStack(Material.DIAMOND, 2)
                harness.grapple.close()
            }
        }

        it("retargets an in-flight shot after a short interval and removes the old displays") {
            withGrappleBlockRayTrace(hitDistance = 24.0) { runtime ->
                val harness = harness(runtime)
                val origin = runtime.addSimpleWorld("rc_origin_spawn")
                val player = runtime.addPlayer("flight-redirect")
                move(player, origin)
                standOnGround(origin)
                startHunt(harness.grapple, origin)
                val hook = requireNotNull(player.inventory.itemInMainHand)
                look(player, Vector(0.0, 0.0, -1.0))

                harness.grapple.onInteract(rightClick(player, hook))
                harness.spawnedHeads.size shouldBe 1
                val oldHead = harness.spawnedHeads.single()
                val oldCable = harness.spawnedCable.toList()

                runtime.performTicks(3)
                harness.grapple.onInteract(rightClick(player, hook))
                harness.spawnedHeads.size shouldBe 1

                runtime.performTicks(1)
                look(player, Vector(1.0, 0.0, 0.0))
                harness.grapple.onInteract(rightClick(player, hook))

                harness.spawnedHeads.size shouldBe 2
                harness.spawnedCable.size shouldBe oldCable.size * 2
                verify(exactly = 1) { oldHead.remove() }
                oldCable.forEach { display -> verify(exactly = 1) { display.remove() } }

                runtime.performTicks(1)
                val redirectedHeadPosition = slot<Location>()
                verify { harness.spawnedHeads[1].teleport(capture(redirectedHeadPosition)) }
                (redirectedHeadPosition.captured.x > player.location.x) shouldBe true
                harness.grapple.close()
            }
        }

        it("retargets a pulling shot without clearing the player's current velocity") {
            withGrappleBlockRayTrace(hitDistance = 5.0) { runtime ->
                val harness = harness(runtime)
                val origin = runtime.addSimpleWorld("rc_origin_spawn")
                val player = runtime.addPlayer("pull-redirect")
                move(player, origin)
                standOnGround(origin)
                startHunt(harness.grapple, origin)
                val hook = requireNotNull(player.inventory.itemInMainHand)
                look(player, Vector(0.0, 0.0, -1.0))

                harness.grapple.onInteract(rightClick(player, hook))
                runtime.performTicks(3)
                (player.velocity.length() > 0.0) shouldBe true
                runtime.performTicks(1)
                val oldHead = harness.spawnedHeads.single()
                val oldCable = harness.spawnedCable.toList()
                look(player, Vector(1.0, 0.0, 0.0))
                val momentum = Vector(0.31, 0.42, -0.27)
                player.velocity = momentum.clone()

                harness.grapple.onInteract(rightClick(player, hook))

                player.velocity shouldBe momentum
                harness.spawnedHeads.size shouldBe 2
                verify(exactly = 1) { oldHead.remove() }
                oldCable.forEach { display -> verify(exactly = 1) { display.remove() } }
                harness.grapple.close()
            }
        }
    }
})

private data class GrappleHarness(
    val plugin: TreasureHuntGrappleLifecycleTestPlugin,
    val grapple: TreasureHuntGrapple,
    val spawnedHeads: MutableList<PacketItemDisplay>,
    val spawnedCable: MutableList<PacketBlockDisplay>,
)

private fun harness(runtime: MockBukkitTestRuntime): GrappleHarness {
    val plugin = runtime.loadSimplePlugin(TreasureHuntGrappleLifecycleTestPlugin::class.java)
    val spawnedHeads = mutableListOf<PacketItemDisplay>()
    val spawnedCable = mutableListOf<PacketBlockDisplay>()
    val displays = mockk<PaperPacketDisplays>(relaxed = true)
    every { displays.spawnItem(any(), any()) } answers {
        mockk<PacketItemDisplay>(relaxed = true).also { spawnedHeads.add(it) }
    }
    every { displays.spawnBlock(any(), any()) } answers {
        mockk<PacketBlockDisplay>(relaxed = true).also { spawnedCable.add(it) }
    }
    val grapple = TreasureHuntGrapple(
        plugin = plugin,
        scheduler = BukkitTaskScheduler(plugin),
        displays = displays,
    )
    grapple.reloadConfig(settings())
    return GrappleHarness(plugin, grapple, spawnedHeads, spawnedCable)
}

private fun settings(enabled: Boolean = true): TreasureHuntGrappleSettings =
    TreasureHuntGrappleSettings.load(TestConfig(mapOf("grapple.enabled" to enabled)).section("grapple"))

private fun startHunt(grapple: TreasureHuntGrapple, world: World) {
    val config = TreasureHuntConfig.simple("grapple-test", "unused", ChestType.vanilla("unused"))
    grapple.onActiveHuntsChanged(
        listOf(ActiveHunt(config, world, ConcurrentHashMap(), totalChests = 0, startTime = 0L)),
    )
}

private fun move(player: Player, world: World) {
    player.teleport(Location(world, 0.5, 64.0, 0.5))
}

private fun look(player: Player, direction: Vector) {
    player.teleport(player.location.apply { setDirection(direction) })
}

private fun standOnGround(world: World) {
    (-1..1).forEach { x ->
        (-1..1).forEach { z -> world.getBlockAt(x, 63, z).type = Material.STONE }
    }
}

private fun rightClick(player: Player, hook: ItemStack) = PlayerInteractEvent(
    player,
    Action.RIGHT_CLICK_AIR,
    hook,
    null,
    BlockFace.SELF,
    EquipmentSlot.HAND,
)

private fun withGrappleBlockRayTrace(
    hitDistance: Double,
    block: (MockBukkitTestRuntime) -> Unit,
) {
    mockkConstructor(WorldMock::class)
    try {
        every {
            anyConstructed<WorldMock>().rayTraceBlocks(
                any<Location>(),
                any<Vector>(),
                any<Double>(),
                any<FluidCollisionMode>(),
                any<Boolean>(),
            )
        } answers {
            val maxDistance = thirdArg<Double>()
            if (maxDistance < hitDistance) {
                null
            } else {
                val start = firstArg<Location>()
                val direction = secondArg<Vector>().clone().normalize()
                val hitPosition = start.toVector().add(direction.multiply(hitDistance))
                val world = requireNotNull(start.world)
                val block = world.getBlockAt(hitPosition.blockX, hitPosition.blockY, hitPosition.blockZ)
                block.type = Material.STONE
                RayTraceResult(hitPosition, block, BlockFace.UP)
            }
        }
        MockBukkitTestRuntime.open().use { runtime -> block(runtime) }
    } finally {
        unmockkConstructor(WorldMock::class)
    }
}

private fun fillHotbar(player: Player) {
    listOf(
        Material.STONE,
        Material.DIRT,
        Material.COBBLESTONE,
        Material.GRANITE,
        Material.ANDESITE,
        Material.DIORITE,
        Material.DEEPSLATE,
        Material.CALCITE,
        Material.TUFF,
    ).forEachIndexed { slot, material -> player.inventory.setItem(slot, ItemStack(material, slot + 1)) }
}

private fun hotbarSnapshot(player: Player): List<ItemStack?> =
    (0..8).map { player.inventory.getItem(it)?.clone() }

private fun hotbarExcept(player: Player, omittedSlot: Int): List<ItemStack?> =
    (0..8).filter { it != omittedSlot }.map { player.inventory.getItem(it)?.clone() }

private fun beforeExcept(items: List<ItemStack?>, omittedSlot: Int): List<ItemStack?> =
    items.filterIndexed { slot, _ -> slot != omittedSlot }

private fun isHook(item: ItemStack?, plugin: JavaPlugin): Boolean =
    item?.itemMeta?.persistentDataContainer?.get(
        NamespacedKey(plugin, "treasure_hunt_grapple"),
        PersistentDataType.BYTE,
    ) == 1.toByte()

private fun taggedCount(player: Player, plugin: JavaPlugin): Int =
    player.inventory.contents.count { isHook(it, plugin) }

private fun droppedItem(stack: ItemStack): Item = mockk(relaxed = true) {
    every { itemStack } returns stack
}

open class TreasureHuntGrappleLifecycleTestPlugin : JavaPlugin()
