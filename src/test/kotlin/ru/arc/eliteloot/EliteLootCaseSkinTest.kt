package ru.arc.eliteloot

import com.magmaguy.elitemobs.api.utils.EliteItemManager
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.*
import org.bukkit.Color
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.attribute.Attribute
import org.bukkit.attribute.AttributeModifier
import org.bukkit.inventory.EquipmentSlotGroup
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.Damageable
import org.bukkit.inventory.meta.LeatherArmorMeta
import org.bukkit.persistence.PersistentDataType
import ru.arc.config.Config
import ru.arc.paper.testing.MockBukkitTestRuntime

class EliteLootCaseSkinTest : FreeSpec({
    "spawn preparation applies an in-place skin before pickup without changing the source reward" {
        MockBukkitTestRuntime.open().use {
            mockkStatic(EliteItemManager::class)
            try {
                every { EliteItemManager.isEliteMobsItem(any()) } returns true
                every { EliteItemManager.getRoundedItemLevel(any()) } returns 43
                val original = ItemStack(Material.DIAMOND_CHESTPLATE)
                val key = NamespacedKey("elitemobs", "soulbind")
                original.editMeta { it.persistentDataContainer.set(key, PersistentDataType.STRING, "owner") }
                val processor = mockk<EliteLootProcessor>()
                every { processor.processEliteLoot(any(), false) } answers {
                    firstArg<ItemStack>().also { it.editMeta { meta -> meta.setCustomModelData(123) } }
                }
                val prepared = prepareEliteDrop(original, processor)
                prepared.itemMeta.customModelData shouldBe 123
                prepared.getData(io.papermc.paper.datacomponent.DataComponentTypes.TOOLTIP_STYLE) shouldBe net.kyori.adventure.key.Key.key("lzblocks", "tooltip/rare")
                prepared.itemMeta.persistentDataContainer.get(key, PersistentDataType.STRING) shouldBe "owner"
                original.itemMeta.hasCustomModelData() shouldBe false
                prepared.type shouldBe Material.DIAMOND_CHESTPLATE
                verify(exactly = 1) { processor.processEliteLoot(any(), false) }
            } finally { unmockkStatic(EliteItemManager::class) }
        }
    }

    "case armor bypasses pickup disable and zero chance while preserving player metadata and protection" {
        MockBukkitTestRuntime.open().use {
            mockkStatic(EliteItemManager::class)
            try {
                val config = mockk<Config>()
                every { config.bool("replace-skins", true) } returns false
                every { config.bool("clear-lore", false) } returns false
                every { config.bool("append-attributes", false) } returns false
                every { config.real("replace-chance", 0.4) } returns 0.0
                val pool = mockk<DecorPool>()
                every { pool.randomItem() } returns DecorItem(Material.LEATHER_CHESTPLATE, 1.0, 11000, Color.RED)
                every { EliteItemManager.isEliteMobsItem(any()) } returns true
                val item = ItemStack(Material.DIAMOND_CHESTPLATE)
                val ownerKey = NamespacedKey("elitemobs", "soulbind")
                item.editMeta { meta ->
                    meta.persistentDataContainer.set(ownerKey, PersistentDataType.STRING, "owner")
                    meta.addAttributeModifier(Attribute.ARMOR, AttributeModifier(NamespacedKey("test", "armor"), 8.0, AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.CHEST))
                    (meta as Damageable).setMaxDamage(700)
                }
                val skin = ItemStack(Material.LEATHER_CHESTPLATE)
                val model = io.papermc.paper.datacomponent.item.CustomModelData.customModelData().addFloat(11000f).build()
                skin.setData(io.papermc.paper.datacomponent.DataComponentTypes.ITEM_MODEL, net.kyori.adventure.key.Key.key("minecraft:leather_chestplate"))
                skin.setData(io.papermc.paper.datacomponent.DataComponentTypes.CUSTOM_MODEL_DATA, model)
                skin.setData(io.papermc.paper.datacomponent.DataComponentTypes.DYED_COLOR, io.papermc.paper.datacomponent.item.DyedItemColor.dyedItemColor(Color.RED))
                val processor = EliteLootProcessor(config, selectDecor = { pool.randomItem() }, template = { skin })
                processor.processEliteLoot(item.clone())!!.type shouldBe Material.DIAMOND_CHESTPLATE
                val result = processor.processEliteLoot(item, caseReward = true)!!
                result.type shouldBe Material.DIAMOND_CHESTPLATE
                result.getData(io.papermc.paper.datacomponent.DataComponentTypes.CUSTOM_MODEL_DATA)!!.floats() shouldBe listOf(11000f)
                result.getData(io.papermc.paper.datacomponent.DataComponentTypes.DYED_COLOR)!!.color() shouldBe Color.RED
                result.itemMeta.persistentDataContainer.get(ownerKey, PersistentDataType.STRING) shouldBe "owner"
                result.itemMeta.getAttributeModifiers(Attribute.ARMOR)!!.single().amount shouldBe 8.0
                (result.itemMeta as Damageable).maxDamage shouldBe 700
            } finally { unmockkStatic(EliteItemManager::class) }
        }
    }
})
