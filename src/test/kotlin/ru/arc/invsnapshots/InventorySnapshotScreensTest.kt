package ru.arc.invsnapshots

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import ru.arc.commands.arc.CommandConfig
import ru.arc.gui.ArcMenuConfiguration
import ru.arc.gui.ArcMenuSchema
import ru.arc.menu.MenuTemplateId
import ru.arc.paper.menu.PaperMenuItemFactory
import ru.arc.paper.menu.PaperMenuItemRenderContext
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID

class InventorySnapshotScreensTest : FunSpec({
    beforeEach {
        mockkObject(CommandConfig)
        every { CommandConfig.get(any(), any(), any<TagResolver>()) } answers {
            MiniMessage.miniMessage().deserialize(secondArg(), thirdArg<TagResolver>())
        }
    }
    afterEach { unmockkObject(CommandConfig) }

    test("native dialog pages expose every retained snapshot once with valid actions and Moscow timestamps") {
        MockBukkitTestRuntime.open().use {
            val snapshots = List(19) { snapshotWith(0, ItemStack(Material.STONE)) }
            val history = SnapshotHistory(UUID.randomUUID(), "<red>Owner", snapshots)
            val screens = (0..2).map { InventorySnapshotScreens.list(history, it, {}, {}) }
            screens.map { it.buttons.count { button -> button.id.value.startsWith("snapshot_") } } shouldBe listOf(8, 8, 3)
            val text = PlainTextComponentSerializer.plainText()
            text.serialize(screens[0].body.single().text).contains("<red>Owner") shouldBe true
            text.serialize(screens[0].buttons.first().label).contains("10.11.2026 11:30:00") shouldBe true
            InventorySnapshotScreens.compactTimestamp(snapshots.first()) shouldBe "10.11 11:30"
            screens[0].buttons.any { it.id.value == "previous" } shouldBe false
            screens[2].buttons.any { it.id.value == "next" } shouldBe false
            listOf("loading", "failed", "empty").forEach { InventorySnapshotScreens.notice(it, "Message").id shouldBe "inventory_snapshots" }
        }
    }

    test("snapshot layout retains all original slots including armor offhand and hotbar and copy hint is visible") {
        MockBukkitTestRuntime.open().use {
            val config = ArcMenuConfiguration.loadResource(javaClass.classLoader)
            val layout = config.catalog.require(ArcMenuSchema.INVENTORY_SNAPSHOT)
            val physical = layout.region(ArcMenuSchema.SNAPSHOT_SLOTS).map { it.index }
            physical.size shouldBe 41
            InventorySnapshotScreens.SLOT_ORDER.toSet() shouldBe (0..40).toSet()
            physical.zip(InventorySnapshotScreens.SLOT_ORDER).toMap().let { mapping ->
                mapping[0] shouldBe 39
                mapping[3] shouldBe 36
                mapping[7] shouldBe 40
                mapping[9] shouldBe 9
                mapping[36] shouldBe 0
                mapping[44] shouldBe 8
            }
            val values = listOf("player", "date", "server", "cause", "id").associateWith { Component.text(it) }
            val template = config.template(MenuTemplateId.of("snapshot-summary"))
            fun lore(copy: Boolean): String = PaperMenuItemFactory().create(template, PaperMenuItemRenderContext(
                values = values, flags = if (copy) setOf("copy") else emptySet(),
            )).itemMeta.lore()!!.joinToString("\n") { PlainTextComponentSerializer.plainText().serialize(it) }
            lore(true).contains("полностью пустой слот") shouldBe true
            lore(true).contains("ЛКМ по предмету") shouldBe true
            lore(false).contains("Только просмотр") shouldBe true
            lore(false).contains("ЛКМ по предмету") shouldBe false
        }
    }

    test("preview footer does not leak into the copied item") {
        MockBukkitTestRuntime.open().use {
            val original = ItemStack(Material.MACE).apply { editMeta { meta -> meta.lore(listOf(Component.text("Original"))) } }
            val snapshot = snapshotWith(4, original)
            val preview = InventorySnapshotScreens.preview(snapshot, 4, true)!!
            preview.itemMeta.lore()!!.size shouldBe 4
            snapshot.item(4) shouldBe original
            val text = preview.itemMeta.lore()!!.joinToString { PlainTextComponentSerializer.plainText().serialize(it) }
            text.contains("Хотбар 5 (4)") shouldBe true
            text.contains("скопировать стак себе") shouldBe true
        }
    }
})
