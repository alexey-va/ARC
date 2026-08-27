package ru.arc.autobuild.gui

import com.github.stefvanschie.inventoryframework.adventuresupport.TextHolder
import com.github.stefvanschie.inventoryframework.gui.GuiItem
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui
import com.github.stefvanschie.inventoryframework.pane.OutlinePane
import com.github.stefvanschie.inventoryframework.pane.Pane
import com.github.stefvanschie.inventoryframework.pane.StaticPane
import com.github.stefvanschie.inventoryframework.pane.util.Slot
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.ItemStack
import ru.arc.ARC
import ru.arc.autobuild.BuildBookCodec
import ru.arc.autobuild.BuildBookData
import ru.arc.autobuild.BuildBookSettings
import ru.arc.autobuild.BuildBookTransform
import ru.arc.autobuild.BuildingManager
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.gui.GuiItems
import ru.arc.util.GuiUtils
import ru.arc.util.TextUtil

object BuildBookEditorGui {
    private val config: Config get() = ConfigManager.ofModule(ARC.instance.dataPath, "auto-build.yml")

    fun open(player: Player) {
        if (!player.hasPermission("arc.build.book.edit")) {
            player.sendMessage(text("build-book.editor.no-permission"))
            return
        }
        val data = BuildBookCodec.read(player.inventory.itemInMainHand)
        if (data == null || BuildingManager.getBuilding(data.buildingId) == null) {
            player.sendMessage(text("build-book.editor.invalid"))
            return
        }
        create(player, data).show(player)
    }

    private fun create(player: Player, data: BuildBookData): ChestGui {
        val gui = ChestGui(
            4,
            TextHolder.deserialize(TextUtil.toLegacy(config.string("build-book.editor.title"))),
            ARC.instance,
        )
        gui.addPane(
            Slot.fromXY(0, 0),
            OutlinePane(9, 4, Pane.Priority.LOWEST).apply {
                addItem(GuiUtils.background())
                setRepeat(true)
            },
        )
        gui.addPane(
            Slot.fromXY(0, 0),
            StaticPane(9, 4).apply {
                addItem(overview(data), 4, 0)
                addItem(axisItem("axis-x", Material.REDSTONE_TORCH, data.transform.offsetX) { click ->
                    data.transform.offset(dx = click.delta())
                }, 1, 1)
                addItem(axisItem("axis-y", Material.SCAFFOLDING, data.transform.offsetY) { click ->
                    data.transform.offset(dy = click.delta())
                }, 3, 1)
                addItem(axisItem("axis-z", Material.RECOVERY_COMPASS, data.transform.offsetZ) { click ->
                    data.transform.offset(dz = click.delta())
                }, 5, 1)
                addItem(rotationItem(data), 7, 1)
                addItem(actionItem("reset", Material.REPEATER) { BuildBookTransform() }, 2, 3)
                addItem(
                    actionItem("close", Material.LIME_DYE) {
                        player.closeInventory()
                        null
                    },
                    6,
                    3,
                )
            },
        )
        gui.setOnTopClick { it.isCancelled = true }
        gui.setOnBottomClick { it.isCancelled = true }
        return gui
    }

    private fun overview(data: BuildBookData): GuiItem = item(
        Material.BOOK,
        config.component("build-book.editor.overview.name", "<#92bed8><bold><name>") {
            tag("name", Component.text(data.title))
        },
        config.componentList("build-book.editor.overview.lore") {
            tag("name", Component.text(data.title))
            tag("rotation", Component.text(data.transform.rotation))
            tag("offset_x", Component.text(data.transform.offsetX))
            tag("offset_y", Component.text(data.transform.offsetY))
            tag("offset_z", Component.text(data.transform.offsetZ))
        },
    )

    private fun axisItem(
        path: String,
        material: Material,
        value: Int,
        change: (InventoryClickEvent) -> BuildBookTransform,
    ): GuiItem = item(
        material,
        text("build-book.editor.$path.name"),
        config.componentList("build-book.editor.$path.lore") { tag("value", Component.text(value)) },
    ) { event -> applyChange(event, change(event)) }

    private fun rotationItem(data: BuildBookData): GuiItem = item(
        Material.CLOCK,
        text("build-book.editor.rotation.name"),
        config.componentList("build-book.editor.rotation.lore") {
            tag("value", Component.text(data.transform.rotation))
        },
    ) { event ->
        val delta = if (event.isRightClick) 90 else -90
        applyChange(event, data.transform.rotate(delta))
    }

    private fun actionItem(
        path: String,
        material: Material,
        change: () -> BuildBookTransform?,
    ): GuiItem = item(
        material,
        text("build-book.editor.$path.name"),
        config.componentList("build-book.editor.$path.lore"),
    ) { event -> change()?.let { applyChange(event, it) } }

    private fun applyChange(event: InventoryClickEvent, nextTransform: BuildBookTransform) {
        event.isCancelled = true
        val player = event.whoClicked as? Player ?: return
        val held = player.inventory.itemInMainHand
        val current = BuildBookCodec.read(held)
        if (current == null || !player.hasPermission("arc.build.book.edit")) {
            player.closeInventory()
            player.sendMessage(text("build-book.editor.invalid"))
            return
        }
        val next = current.copy(transform = nextTransform.validated()).validated()
        if (BuildingManager.updatePendingTransform(player, next) == false) {
            player.sendMessage(text("build-book.editor.preview-blocked"))
            return
        }
        player.inventory.setItemInMainHand(BuildBookCodec.update(held, next))
        player.playSound(player.location, Sound.UI_BUTTON_CLICK, 0.7f, 1.2f)
        create(player, next).show(player)
    }

    private fun InventoryClickEvent.delta(): Int {
        val magnitude = if (isShiftClick) 5 else 1
        return if (isRightClick) magnitude else -magnitude
    }

    private fun text(path: String): Component =
        requireNotNull(config.componentOrNull(path)) { "Missing build-book editor text '$path'" }

    private fun item(
        material: Material,
        name: Component,
        lore: List<Component>,
        click: ((InventoryClickEvent) -> Unit)? = null,
    ): GuiItem {
        val stack = ItemStack(material)
        stack.editMeta { meta ->
            TextUtil.strip(name)?.let(meta::displayName)
            meta.lore(lore.mapNotNull(TextUtil::strip))
        }
        return GuiItems.create(stack) { event ->
            event.isCancelled = true
            click?.invoke(event)
        }
    }
}
