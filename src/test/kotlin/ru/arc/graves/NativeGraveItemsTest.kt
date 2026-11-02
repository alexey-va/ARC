package ru.arc.graves

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import ru.arc.paper.testing.MockBukkitTestRuntime
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import net.kyori.adventure.text.Component

class NativeGraveItemsTest : FunSpec({
    test("experience thresholds and float progress retain exact whole experience points") {
        listOf(0, 1, 6, 7, 100, 352, 394, 1395, 1507, 100_000, Int.MAX_VALUE).forEach { points ->
            val (level, progress) = NativeGraveItems.experienceState(points)
            NativeGraveItems.experiencePoints(level, progress) shouldBe points
        }
        NativeGraveItems.experiencePoints(16, 0f) shouldBe 352
        NativeGraveItems.experiencePoints(17, 0f) shouldBe 394
        NativeGraveItems.experiencePoints(31, 0f) shouldBe 1507
    }

    test("death removes only dropped amounts and pickup keeps metadata and overflow") {
        MockBukkitTestRuntime.open().use { paper ->
            val player = paper.addPlayer("GraveOwner")
            val named = ItemStack(Material.DIAMOND, 17).apply {
                editMeta { it.displayName(Component.text("Personal death item")) }
            }
            player.inventory.setItem(0, named)
            player.inventory.setItem(1, ItemStack(Material.EMERALD, 3))
            player.level = 17
            val before = NativeGraveItems.capture(player)
            val after = NativeGraveItems.afterDeath(before, listOf(named.clone().also { it.amount = 12 }), false)
            NativeGraveItems.decode(requireNotNull(after.slots[0])).amount shouldBe 5
            after.slots[1] shouldBe before.slots[1]
            after.level shouldBe 0
            NativeGraveItems.afterDeath(before, listOf(named), true).level shouldBe 17

            for (slot in 0..35) player.inventory.setItem(slot, ItemStack(Material.COBBLESTONE, 64))
            player.inventory.setItem(4, named.clone().also { it.amount = 60 })
            val original = NativeGraveItems.encode(named)
            val pickup = NativeGraveItems.pickup(NativeGraveItems.capture(player), listOf(original), 9)
            val received = NativeGraveItems.decode(requireNotNull(pickup.after.slots[4]))
            received.amount shouldBe 64
            received.itemMeta.displayName() shouldBe named.itemMeta.displayName()
            pickup.remaining.map { NativeGraveItems.decode(it).amount } shouldBe listOf(13)
            NativeGraveItems.experiencePoints(pickup.after.level, pickup.after.progress) shouldBe 403
        }
    }
})
