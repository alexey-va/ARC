package ru.arc.graves

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import ru.arc.paper.testing.MockBukkitTestRuntime
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import net.kyori.adventure.text.Component
import ru.arc.paper.playerstate.NativePaperItemStackBinaryCodec

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

    test("native-equivalent binary variants match while slots amount metadata PDC and exact XP remain strict") {
        MockBukkitTestRuntime.open().use { paper ->
            val player = paper.addPlayer("GraveVariant")
            val key = NamespacedKey("arc", "grave-test")
            val item = ItemStack(Material.PAPER, 2).apply {
                editMeta { meta ->
                    meta.displayName(Component.text("Test key"))
                    meta.persistentDataContainer.set(key, PersistentDataType.STRING, "original")
                }
            }
            player.inventory.setItem(0, item)
            player.level = 4
            player.exp = .5f
            player.totalExperience = 37
            // The real Paper probe proves this transport behavior; stable MockBukkit bytes cannot represent it.
            val encoded = mutableListOf<ItemStack>()
            mockkObject(NativePaperItemStackBinaryCodec)
            every { NativePaperItemStackBinaryCodec.encodeItem(any()) } answers {
                val id = encoded.size
                encoded += firstArg<ItemStack>().clone()
                id.toString().toByteArray(Charsets.UTF_8)
            }
            every { NativePaperItemStackBinaryCodec.decodeItem(any()) } answers {
                encoded[firstArg<ByteArray>().toString(Charsets.UTF_8).toInt()].clone()
            }
            try {
                val expected = NativeGraveItems.capture(player)
                NativeGraveItems.apply(player, expected)
                val actual = NativeGraveItems.capture(player)
                (actual == expected) shouldBe false
                NativeGraveInventoryPayload.encode(actual).contentEquals(NativeGraveInventoryPayload.encode(expected)) shouldBe false
                NativeGraveItems.matches(actual, expected) shouldBe true

                fun changed(stack: ItemStack) = actual.copy(slots = actual.slots.toMutableList().also {
                    it[0] = NativeGraveItems.encode(stack)
                })
                NativeGraveItems.matches(changed(item.clone().also { it.amount++ }), expected) shouldBe false
                NativeGraveItems.matches(changed(item.clone().apply {
                    editMeta { it.displayName(Component.text("Changed key")) }
                }), expected) shouldBe false
                NativeGraveItems.matches(changed(item.clone().apply {
                    editMeta { it.persistentDataContainer.set(key, PersistentDataType.STRING, "changed") }
                }), expected) shouldBe false
                NativeGraveItems.matches(actual.copy(slots = actual.slots.toMutableList().also { it[0] = null }), expected) shouldBe false
                NativeGraveItems.matches(actual.copy(slots = actual.slots.dropLast(1)), expected) shouldBe false
                NativeGraveItems.matches(actual.copy(level = actual.level + 1), expected) shouldBe false
                NativeGraveItems.matches(actual.copy(progress = Float.fromBits(actual.progress.toBits() + 1)), expected) shouldBe false
                NativeGraveItems.matches(actual.copy(totalExperience = actual.totalExperience + 1), expected) shouldBe false
                val fractional = expected.copy(level = 23, progress = .1234567f, totalExperience = 137)
                val noXp = NativeGraveItems.pickup(fractional, emptyList(), 0).after
                noXp.level shouldBe fractional.level
                noXp.progress.toBits() shouldBe fractional.progress.toBits()
                noXp.totalExperience shouldBe fractional.totalExperience
                NativeGraveItems.matches(noXp, fractional) shouldBe true
            } finally {
                unmockkObject(NativePaperItemStackBinaryCodec)
            }
        }
    }
})
