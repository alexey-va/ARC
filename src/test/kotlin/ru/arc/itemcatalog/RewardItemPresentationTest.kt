package ru.arc.itemcatalog

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.papermc.paper.datacomponent.DataComponentTypes
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.enchantments.Enchantment
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import ru.arc.paper.testing.MockBukkitTestRuntime

class RewardItemPresentationTest : StringSpec({
    "frames preserve native metadata and do not mutate the reward template" {
        MockBukkitTestRuntime.open().use {
            val identity = NamespacedKey("arc", "reward_identity")
            val native = ItemStack(Material.DIAMOND_SWORD).apply {
                addEnchantment(Enchantment.SHARPNESS, 3)
                editMeta {
                    it.displayName(Component.text("Клинок дозора"))
                    it.lore(listOf(Component.empty(), Component.text("Нативное описание")))
                    it.setCustomModelData(12345)
                    it.persistentDataContainer.set(identity, PersistentDataType.STRING, "unchanged-bearer")
                }
            }
            val entry = RewardCatalogEntry("blade", "Карточка награды", emptyList(), null, emptyList(),
                RewardCatalogSource.Preset("blade"), null)
            for (tier in listOf("common", "uncommon", "rare", "epic", "legendary", "artifact")) {
                val styled = RewardItemPresentation.tooltip(native, entry.copy(tooltipStyle = "lzblocks:tooltip/$tier"))
                styled.getData(DataComponentTypes.TOOLTIP_STYLE) shouldBe Key.key("lzblocks", "tooltip/$tier")
                styled.itemMeta.displayName() shouldBe native.itemMeta.displayName()
                styled.itemMeta.lore() shouldBe native.itemMeta.lore()
                styled.itemMeta.customModelData shouldBe 12345
                styled.itemMeta.persistentDataContainer.get(identity, PersistentDataType.STRING) shouldBe "unchanged-bearer"
                styled.enchantments shouldBe native.enchantments
            }
            native.getData(DataComponentTypes.TOOLTIP_STYLE) shouldBe null
        }
    }

    "missing style leaves the native clone unchanged" {
        // MockBukkit 4.116.3's ItemStackMock copy constructor drops its data-component map.
        // Verify the unconfigured adapter call boundary directly; native clone/serialization
        // preservation requires Paper, not a replacement production cloning implementation.
        val native = mockk<ItemStack>()
        val clone = mockk<ItemStack>()
        every { native.clone() } returns clone
        val entry = RewardCatalogEntry("native", null, emptyList(), null, emptyList(),
            RewardCatalogSource.Preset("native"), null)
        RewardItemPresentation.tooltip(native, entry) shouldBe clone
        verify(exactly = 1) { native.clone() }
        verify(exactly = 0) { clone.setData(DataComponentTypes.TOOLTIP_STYLE, any<Key>()) }
    }
})
