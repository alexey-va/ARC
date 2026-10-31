package ru.arc.enchanting

import de.tr7zw.changeme.nbtapi.iface.ReadableItemNBT
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import ru.arc.paper.testing.MockBukkitTestRuntime

class EnchantmentLootboxValidationTest : StringSpec({
    "only one non-air item with the AE root key is accepted" {
        MockBukkitTestRuntime.open().use {
            var reads = 0
            val reader: (ItemStack) -> String? = { reads++; "ae" }
            for (item in listOf(null, ItemStack(Material.AIR), ItemStack(Material.CHEST, 2))) {
                isEnchantmentLootbox(item, reader) shouldBe false
            }
            reads shouldBe 0
            val item = ItemStack(Material.CHEST)
            isEnchantmentLootbox(item, reader) shouldBe true
            reads shouldBe 1
            for (key in listOf(null, "", "sf", "AE")) {
                isEnchantmentLootbox(item) { key } shouldBe false
            }
            item.amount shouldBe 1
        }
    }

    "reader exceptions and linkage failures deny issuance while VM failures propagate" {
        MockBukkitTestRuntime.open().use {
            val item = ItemStack(Material.CHEST)
            isEnchantmentLootbox(item) { error("Unreadable custom data") } shouldBe false
            isEnchantmentLootbox(item) { throw NoClassDefFoundError("NBT API unavailable") } shouldBe false
            shouldThrow<OutOfMemoryError> {
                isEnchantmentLootbox(item) { throw OutOfMemoryError("Fatal reader sentinel") }
            }
        }
    }

    "the root reader uses the exact treasure key independently from an ItemMeta PDC-only marker" {
        MockBukkitTestRuntime.open().use {
            val item = ItemStack(Material.IRON_INGOT).apply {
                editMeta { it.persistentDataContainer.set(NamespacedKey("arc", "treasure_key"), PersistentDataType.STRING, "ae") }
            }
            val data = mockk<ReadableItemNBT>()
            var rootKey = ""
            every { data.getString("arc:treasure_key") } answers { rootKey }
            val reader: (ItemStack) -> String? = { readEnchantmentLootboxRootKey(data) }
            isEnchantmentLootbox(item, reader) shouldBe false
            rootKey = "ae"
            isEnchantmentLootbox(item, reader) shouldBe true
            every { data.getString("arc:treasure_key") } throws IllegalStateException("Unreadable custom data")
            isEnchantmentLootbox(item, reader) shouldBe false
            verify(exactly = 3) { data.getString("arc:treasure_key") }
            verify(exactly = 0) { data.getCompound(any()) }
        }
    }
})
