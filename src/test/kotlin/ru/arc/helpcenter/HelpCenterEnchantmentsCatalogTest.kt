package ru.arc.helpcenter

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import ru.arc.config.ConfigManager
import java.nio.file.Files

class HelpCenterEnchantmentsCatalogTest : StringSpec({
    "description stays available when AE display lore contains only the enchantment name" {
        val enchantment = HelpCenterEnchantmentsCatalog.fromApiData(
            id = "field_surgeon",
            displayLore = listOf("&aПолевой хирург"),
            description = "&7Сажает семена при обработке земли.",
            maxLevelDescription = "&7На максимальном уровне действие срабатывает чаще.",
            materials = setOf("minecraft:diamond_hoe"),
            group = "SIMPLE",
            maxLevel = 4,
        )

        enchantment.name shouldBe "Полевой хирург"
        enchantment.description shouldBe "Сажает семена при обработке земли."
        enchantment.maxLevelDescription shouldBe "На максимальном уровне действие срабатывает чаще."
        enchantment.maxLevel shouldBe 4
        enchantment.materials shouldBe setOf("DIAMOND_HOE")
    }

    "equipment filters separate weapon and tool-specific enchantments" {
        val entries = listOf(
            enchantment("sword_only", "DIAMOND_SWORD"),
            enchantment("axe_only", "DIAMOND_AXE"),
            enchantment("mace_only", "MACE"),
            enchantment("trident_only", "TRIDENT"),
            enchantment("crossbow_only", "CROSSBOW"),
            enchantment("fishing_only", "FISHING_ROD"),
            enchantment("hoe_only", "DIAMOND_HOE"),
        )
        val catalog = HelpCenterEnchantmentsCatalog(available = true, entries = entries)

        catalog.search("", HelpCenterEnchantmentsEquipment.SWORD).map { it.id } shouldBe listOf("sword_only")
        catalog.search("", HelpCenterEnchantmentsEquipment.AXE).map { it.id } shouldBe listOf("axe_only")
        catalog.search("", HelpCenterEnchantmentsEquipment.MACE).map { it.id } shouldBe listOf("mace_only")
        catalog.search("", HelpCenterEnchantmentsEquipment.TRIDENT).map { it.id } shouldBe listOf("trident_only")
        catalog.search("", HelpCenterEnchantmentsEquipment.CROSSBOW).map { it.id } shouldBe listOf("crossbow_only")
        catalog.search("", HelpCenterEnchantmentsEquipment.FISHING_ROD).map { it.id } shouldBe listOf("fishing_only")
        catalog.search("seed", HelpCenterEnchantmentsEquipment.HOE).map { it.id } shouldBe listOf("hoe_only")
    }

    "bundled guide has special item mechanics and no unsupported crate command" {
        val root = Files.createTempDirectory("arc-enchantments-guide")
        try {
            ConfigManager.clear()
            val guide = HelpCenterEnchantmentsGuideConfig.load(root).snapshot()

            guide.text("ui.title").contains("Зачарования") shouldBe true
            guide.text("ui.special-black-body").contains("шансом успешного применения этой книги") shouldBe true
            guide.text("ui.special-black-body").contains("ЛКМ") shouldBe true
            guide.text("ui.special-soul-gem-body").contains("/withdrawsouls число") shouldBe true
            guide.text("ui.acquisition-body").contains("/ecia") shouldBe false
            guide.text("ui.special-title").contains("AE") shouldBe false
        } finally {
            ConfigManager.clear()
            root.toFile().deleteRecursively()
        }
    }
})

private fun enchantment(id: String, material: String) = HelpCenterEnchantmentsCatalog.fromApiData(
    id = id,
    displayLore = listOf(id),
    description = "Counts seed actions.",
    maxLevelDescription = "Counts seed actions.",
    materials = setOf(material),
    group = "SIMPLE",
    maxLevel = 1,
)
