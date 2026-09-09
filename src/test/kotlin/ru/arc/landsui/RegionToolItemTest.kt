package ru.arc.landsui

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Material
import ru.arc.config.ConfigManager
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.nio.file.Files

class RegionToolItemTest : FreeSpec({
    "creates a branded marker item and rejects debug-stick lookalikes" {
        MockBukkitTestRuntime.open().use {
            val root = Files.createTempDirectory("arc-region-tool")
            try {
                ConfigManager.clear()
                val settings = LandsUiConfig.load(root).snapshot()
                val item = RegionToolItem.create(settings)

                item.type shouldBe Material.DEBUG_STICK
                RegionToolItem.matches(item) shouldBe true
                RegionToolItem.matches(ItemStackLookalike.debugStick()) shouldBe false
                RegionToolItem.matches(item.clone().also { it.type = Material.STICK }) shouldBe false
                PlainTextComponentSerializer.plainText().serialize(checkNotNull(item.itemMeta.displayName())) shouldBe "Мультитул регионов"
                item.itemMeta.lore()?.map(PlainTextComponentSerializer.plainText()::serialize) shouldBe listOf(
                    "ЛКМ по земле — первый угол",
                    "ПКМ по земле — второй угол",
                    "Shift + ЛКМ по кнопке — создать",
                    "Shift + ПКМ — сбросить выделение",
                )
                checkNotNull(item.itemMeta.displayName()).decoration(TextDecoration.ITALIC) shouldBe TextDecoration.State.FALSE
            } finally {
                ConfigManager.clear()
                root.toFile().deleteRecursively()
            }
        }
    }
})

private object ItemStackLookalike {
    fun debugStick() = org.bukkit.inventory.ItemStack(Material.DEBUG_STICK)
}
