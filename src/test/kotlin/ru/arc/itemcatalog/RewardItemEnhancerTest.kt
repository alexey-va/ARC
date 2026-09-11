package ru.arc.itemcatalog

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.enchantments.Enchantment
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import ru.arc.paper.testing.MockBukkitTestRuntime

class RewardItemEnhancerTest : StringSpec({
    "themed enchants preserve the native model and custom identity without changing the source" {
        MockBukkitTestRuntime.open().use {
            val base = ItemStack(Material.DIAMOND_SWORD).apply {
                editMeta { meta ->
                    meta.displayName(Component.text("Меч Вована"))
                    meta.lore(listOf(Component.text("Собственная история")))
                    meta.setCustomModelData(10017)
                    meta.persistentDataContainer.set(NamespacedKey("itemsadder", "id"), PersistentDataType.STRING, "vladimir_sword")
                }
            }
            val after = requireNotNull(RewardItemEnhancer.enrich(base, mapOf("sharpness" to 4, "unbreaking" to 3)))
            after.getEnchantmentLevel(Enchantment.SHARPNESS) shouldBe 4
            after.itemMeta.displayName() shouldBe base.itemMeta.displayName()
            after.itemMeta.lore() shouldBe base.itemMeta.lore()
            after.itemMeta.customModelData shouldBe 10017
            after.itemMeta.persistentDataContainer.get(NamespacedKey("itemsadder", "id"), PersistentDataType.STRING) shouldBe "vladimir_sword"
            base.enchantments.isEmpty() shouldBe true
        }
    }

    "invalid or conflicting rewards fail closed and never weaken an existing item" {
        MockBukkitTestRuntime.open().use {
            val sword = ItemStack(Material.DIAMOND_SWORD)
            for (invalid in listOf(
                mapOf("sharpness" to 6),
                mapOf("sharpness" to 4, "smite" to 4),
                mapOf("sharpness" to 4, "minecraft:sharpness" to 4),
                mapOf("future_unknown" to 1),
                mapOf("protection" to 4),
            )) RewardItemEnhancer.enrich(sword, invalid) shouldBe null
            sword.enchantments.isEmpty() shouldBe true
            sword.addEnchantment(Enchantment.SHARPNESS, 5)
            RewardItemEnhancer.enrich(sword, mapOf("sharpness" to 4))?.getEnchantmentLevel(Enchantment.SHARPNESS) shouldBe 5
            RewardItemEnhancer.enrich(ItemStack(Material.PAPER), mapOf("sharpness" to 1)) shouldBe null
        }
    }
})
