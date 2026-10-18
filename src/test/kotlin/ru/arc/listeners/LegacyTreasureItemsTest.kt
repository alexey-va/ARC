package ru.arc.listeners

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.util.Logging
import ru.arc.util.withCustomModelData

class LegacyTreasureItemsTest : StringSpec({
    beforeSpec {
        Logging.disableLokiAppender = true
        Logging.bootstrapForTests()
    }

    "all archived names require the matching material model and legacy use lore" {
        val examples = listOf(
            legacy(Material.IRON_INGOT, 12033, "Шкатулка с чарами") to "ae_lootbox",
            legacy(Material.IRON_INGOT, 12033, "Токен элитных чар") to "ae_lootbox",
            legacy(Material.IRON_INGOT, 73026, "Лутбокс слаймфана") to "sf_lootbox",
            legacy(Material.STICK, 11135, "Горсть монет") to "money_handful",
            legacy(Material.STICK, 11136, "Кучка монет") to "money_pile",
            legacy(Material.STICK, 11137, "Куча монет") to "money_heap",
            legacy(Material.STICK, 11138, "Мешок монет") to "money_bag",
        )
        examples.forEach { (item, preset) ->
            LegacyTreasureItems.target(item)?.preset shouldBe preset
            LegacyTreasureItems.target(item.copy(model = 1)) shouldBe null
            LegacyTreasureItems.target(item.copy(material = Material.PAPER)) shouldBe null
            LegacyTreasureItems.target(item.copy(name = "Переименованный предмет")) shouldBe null
            LegacyTreasureItems.target(item.copy(lore = emptyList())) shouldBe null
        }
    }

    "live untagged AE stack and coin pile match with colored Adventure names and lore" {
        MockBukkitTestRuntime.open().use {
            val samples = listOf(
                oldStack(Material.IRON_INGOT, 12033, "Шкатулка с чарами", 2) to "ae_lootbox",
                oldStack(Material.STICK, 11136, "Кучка монет", 4) to "money_pile",
            )
            samples.forEach { (stack, preset) ->
                val fingerprint = requireNotNull(LegacyTreasureItems.fingerprint(stack, emptyMap()))
                LegacyTreasureItems.target(fingerprint)?.preset shouldBe preset
                fingerprint.customData shouldBe emptyMap()
            }
        }
    }

    "compatible native markers identify old tagged items without relying on their current names" {
        val ae = legacy(Material.IRON_INGOT, 12033, "Переименованный токен").copy(lore = emptyList())
        LegacyTreasureItems.target(ae.copy(customData = mapOf("ae_token" to "true")))?.preset shouldBe "ae_lootbox"
        LegacyTreasureItems.target(ae.copy(customData = mapOf("ae_token" to "1", "arc:treasure_key" to "ae")))
            ?.preset shouldBe "ae_lootbox"
        val sf = legacy(Material.IRON_INGOT, 73026, "Старый тайник").copy(lore = emptyList())
        LegacyTreasureItems.target(sf.copy(customData = mapOf("slimefun_token" to "1")))?.preset shouldBe "sf_lootbox"
        val coins = legacy(Material.STICK, 11136, "Старые монеты").copy(lore = emptyList(), customData = mapOf(
            "money_token_mean" to "6500", "money_token_sigma" to "2000", "money_token_minimum" to "3000",
        ))
        LegacyTreasureItems.target(coins)?.preset shouldBe "money_pile"
        LegacyTreasureItems.target(coins.copy(customData = mapOf("arc:treasure_key" to "mediummoney")))
            ?.preset shouldBe "money_pile"
    }

    "markerless sea lanterns are excluded while historical native SF sea lanterns remain eligible" {
        val lantern = legacy(Material.SEA_LANTERN, 10000, "Лутбокс слаймфана")
        LegacyTreasureItems.target(lantern) shouldBe null
        LegacyTreasureItems.target(lantern.copy(customData = mapOf("arc:treasure_key" to "sf")))
            ?.preset shouldBe "sf_lootbox"
    }

    "decorative ItemsAdder models renamed ordinary items and foreign metadata remain untouched" {
        val ae = legacy(Material.IRON_INGOT, 12033, "Шкатулка с чарами")
        val decorations = listOf(
            ae.copy(name = "Фантастический сундук", lore = emptyList()),
            ae.copy(name = "set_16_i_3", lore = emptyList()),
            ae.copy(lore = emptyList()),
            ae.copy(foreignMetadata = true),
            ae.copy(customData = mapOf("itemsadder" to null)),
            ae.copy(customData = mapOf("PublicBukkitValues" to null)),
            ae.copy(customData = mapOf("arc:reward_id" to "special")),
            ae.copy(customData = mapOf("arc:voucher" to "special")),
        )
        decorations.forEach { LegacyTreasureItems.target(it) shouldBe null }
    }

    "foreign token types custom payouts and advanced master or explicit-roll rewards retain their contracts" {
        val sf = legacy(Material.IRON_INGOT, 73026, "Лутбокс слаймфана")
        val incompatible = listOf(
            sf.copy(customData = mapOf("arc:treasure_key" to "sf_advanced", "slimefun_token" to "1")),
            sf.copy(customData = mapOf("arc:treasure_key" to "sf_jackpot", "slimefun_token" to "1")),
            sf.copy(customData = mapOf("arc:treasure_key" to "sf", "arc:treasure_rolls_min" to "1")),
            sf.copy(customData = mapOf("arc:treasure_key" to "sf", "arc:treasure_rolls_max" to "5")),
            sf.copy(customData = mapOf("slimefun_token" to "0")),
            sf.copy(customData = mapOf("ae_token" to "true")),
            legacy(Material.IRON_INGOT, 12033, "Шкатулка с чарами").copy(customData = mapOf("ae_token" to "false")),
            legacy(Material.STICK, 11136, "Кучка монет").copy(customData = mapOf("money_token_mean" to "999999")),
            legacy(Material.IRON_INGOT, 73024, "Шкатулка с чарами"),
            legacy(Material.IRON_INGOT, 73029, "Лутбокс слаймфана"),
        )
        incompatible.forEach { LegacyTreasureItems.target(it) shouldBe null }
    }

    "canonical replacement preserves complete stack counts and repeated normalization changes nothing" {
        MockBukkitTestRuntime.open().use {
            listOf(1, 2, 4, 64).forEach { amount ->
                val original = oldStack(Material.IRON_INGOT, 12033, "Шкатулка с чарами", amount)
                val snapshot = original.clone()
                val target = requireNotNull(LegacyTreasureItems.target(legacy(Material.IRON_INGOT, 12033, "Шкатулка с чарами")))
                val canonical = ItemStack(Material.IRON_INGOT, amount).withCustomModelData(73023).apply {
                    editMeta { meta -> meta.displayName(Component.text("Текущий шаблон")) }
                }
                var requests = 0
                val resolve = { preset: String, requested: Int ->
                    preset shouldBe "ae_lootbox"
                    requested shouldBe amount
                    requests++
                    Result.success(listOf(canonical.clone()))
                }
                val replacement = requireNotNull(LegacyTreasureItems.replacement(original, target, resolve) { "ae" })
                replacement.amount shouldBe amount
                replacement shouldBe canonical
                original shouldBe snapshot
                LegacyTreasureItems.replacement(replacement, target, resolve) { "ae" } shouldBe null
                requests shouldBe 2
            }
        }
    }

    "failed missing bundled split wrong-amount or wrong-key factories never change the original" {
        MockBukkitTestRuntime.open().use {
            val original = oldStack(Material.STICK, 11136, "Кучка монет", 4)
            val snapshot = original.clone()
            val target = requireNotNull(LegacyTreasureItems.target(legacy(Material.STICK, 11136, "Кучка монет")))
            val invalid = listOf<Result<List<ItemStack>>>(
                Result.failure(IllegalStateException("Preset unavailable")),
                Result.success(emptyList()),
                Result.success(listOf(ItemStack(Material.STICK, 4), ItemStack(Material.STICK, 4))),
                Result.success(listOf(ItemStack(Material.STICK, 1))),
                Result.success(listOf(ItemStack(Material.AIR, 4))),
                Result.success(listOf(ItemStack(Material.DIAMOND_SWORD, 4))),
            )
            invalid.forEach { result ->
                LegacyTreasureItems.replacement(original, target, { _, _ -> result }) { "mediummoney" } shouldBe null
                original shouldBe snapshot
            }
            LegacyTreasureItems.replacement(original, target, { _, _ -> throw IllegalStateException("Factory threw") })
                { "mediummoney" } shouldBe null
            LegacyTreasureItems.replacement(original, target, { _, _ -> Result.success(listOf(ItemStack(Material.STICK, 4))) })
                { "sf_advanced" } shouldBe null
            original shouldBe snapshot
        }
    }

    "oversized legacy stacks are preserved instead of accepting preset amount clamping" {
        MockBukkitTestRuntime.open().use {
            val original = oldStack(Material.STICK, 11136, "Кучка монет", 65)
            val target = requireNotNull(LegacyTreasureItems.target(legacy(Material.STICK, 11136, "Кучка монет")))
            var requests = 0
            LegacyTreasureItems.replacement(original, target, { _, _ ->
                requests++
                Result.success(listOf(ItemStack(Material.STICK, 64)))
            }) { "mediummoney" } shouldBe null
            requests shouldBe 0
            original.amount shouldBe 65
        }
    }
})

private fun legacy(material: Material, model: Int, name: String) =
    LegacyTreasureFingerprint(material, model, name, listOf("Нажмите ПКМ для использования"))

private fun oldStack(material: Material, model: Int, name: String, amount: Int) =
    ItemStack(material, amount).withCustomModelData(model).apply {
        editMeta { meta ->
            meta.displayName(Component.text(name, NamedTextColor.YELLOW).decorate(TextDecoration.BOLD))
            meta.lore(listOf(Component.text("Нажмите ", NamedTextColor.GRAY)
                .append(Component.text("ПКМ", NamedTextColor.GOLD))
                .append(Component.text(" для использования", NamedTextColor.GRAY))))
        }
    }
