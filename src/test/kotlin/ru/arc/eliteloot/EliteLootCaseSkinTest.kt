package ru.arc.eliteloot

import com.magmaguy.elitemobs.api.utils.EliteItemManager
import com.magmaguy.elitemobs.config.ItemSettingsConfig
import com.magmaguy.elitemobs.skills.SkillType
import com.magmaguy.elitemobs.skills.WeaponIdentityResolver
import io.papermc.paper.datacomponent.DataComponentTypes
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
    "a recognized FMM staff receives a crossbow model without changing its weapon data" {
        MockBukkitTestRuntime.open().use {
            mockkStatic(EliteItemManager::class, WeaponIdentityResolver::class)
            try {
                every { EliteItemManager.isEliteMobsItem(any()) } returns true
                every { WeaponIdentityResolver.progressionSkill(any()) } returns SkillType.STAVES
                val config = mockk<Config>()
                every { config.bool("replace-skins", true) } returns true
                every { config.real("replace-chance", 0.9) } returns 1.0
                val fmmKey = NamespacedKey("freeminecraftmodels", "fmm_item_id")
                val ownerKey = NamespacedKey("elitemobs", "soulbind")
                val original = ItemStack(Material.WOODEN_SPEAR)
                original.setData(DataComponentTypes.ITEM_MODEL, net.kyori.adventure.key.Key.key("freeminecraftmodels:display/fmm_default_arcane_staff"))
                original.editMeta {
                    it.itemModel = NamespacedKey("freeminecraftmodels", "display/fmm_default_arcane_staff")
                    it.persistentDataContainer.set(fmmKey, PersistentDataType.STRING, "fmm_default_arcane_staff")
                    it.persistentDataContainer.set(ownerKey, PersistentDataType.STRING, "owner")
                    it.addAttributeModifier(Attribute.ATTACK_DAMAGE, AttributeModifier(NamespacedKey("test", "staff_damage"), 13.0, AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.MAINHAND))
                    (it as Damageable).damage = 7
                }
                val donor = ItemStack(Material.CROSSBOW)
                donor.setData(DataComponentTypes.CUSTOM_MODEL_DATA, io.papermc.paper.datacomponent.item.CustomModelData.customModelData().addFloat(10001f).build())
                var selections = 0
                val processor = EliteLootProcessor(config, selectDecor = {
                    it shouldBe LootType.STAFF
                    selections++
                    DecorItem(Material.CROSSBOW, 1.0, 10001, iaNamespace = "3dfantasyweaponscit", iaId = "night_staff")
                }, template = { donor })
                processor.processEliteLoot(original) shouldBe original
                original.type shouldBe Material.WOODEN_SPEAR
                original.getData(DataComponentTypes.ITEM_MODEL) shouldBe net.kyori.adventure.key.Key.key("minecraft:crossbow")
                original.getData(DataComponentTypes.CUSTOM_MODEL_DATA)!!.floats() shouldBe listOf(10001f)
                original.itemMeta.persistentDataContainer.get(fmmKey, PersistentDataType.STRING) shouldBe "fmm_default_arcane_staff"
                original.itemMeta.persistentDataContainer.get(ownerKey, PersistentDataType.STRING) shouldBe "owner"
                original.itemMeta.getAttributeModifiers(Attribute.ATTACK_DAMAGE)!!.single().amount shouldBe 13.0
                (original.itemMeta as Damageable).damage shouldBe 7
                processor.processEliteLoot(original) shouldBe original
                selections shouldBe 1
            } finally { unmockkStatic(EliteItemManager::class, WeaponIdentityResolver::class) }
        }
    }

    "staff routing requires explicit FMM identity and leaves wands and existing sword skins alone" {
        MockBukkitTestRuntime.open().use {
            mockkStatic(EliteItemManager::class, WeaponIdentityResolver::class)
            try {
                every { EliteItemManager.isEliteMobsItem(any()) } returns true
                every { WeaponIdentityResolver.progressionSkill(any()) } returns SkillType.WANDS
                EliteLootManager.toLootType(ItemStack(Material.WOODEN_SPEAR)) shouldBe null
                val wand = ItemStack(Material.BLAZE_ROD)
                wand.editMeta { it.persistentDataContainer.set(NamespacedKey("freeminecraftmodels", "fmm_item_id"), PersistentDataType.STRING, "fmm_default_arcane_wand") }
                EliteLootManager.toLootType(wand) shouldBe null
                val config = mockk<Config>()
                every { config.bool("replace-skins", true) } returns true
                val sword = ItemStack(Material.DIAMOND_SWORD)
                sword.editMeta { it.setCustomModelData(999) }
                sword.setData(DataComponentTypes.ITEM_MODEL, net.kyori.adventure.key.Key.key("example:existing_sword"))
                val processor = EliteLootProcessor(config, selectDecor = { error("An existing sword model must not enter the pool") })
                processor.processEliteLoot(sword) shouldBe sword
                sword.getData(DataComponentTypes.ITEM_MODEL) shouldBe net.kyori.adventure.key.Key.key("example:existing_sword")
            } finally { unmockkStatic(EliteItemManager::class, WeaponIdentityResolver::class) }
        }
    }

    "spawn preparation applies an in-place skin before pickup without changing the source reward" {
        MockBukkitTestRuntime.open().use {
            mockkStatic(EliteItemManager::class, ItemSettingsConfig::class)
            try {
                every { EliteItemManager.isEliteMobsItem(any()) } returns true
                every { EliteItemManager.getRoundedItemLevel(any()) } returns 43
                every { ItemSettingsConfig.getWeaponEntry() } returns ""
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
            } finally { unmockkStatic(EliteItemManager::class, ItemSettingsConfig::class) }
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
