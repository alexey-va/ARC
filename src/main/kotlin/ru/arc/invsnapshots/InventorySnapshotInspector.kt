package ru.arc.invsnapshots

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import ru.arc.ARC
import ru.arc.commands.arc.CommandConfig
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.Tasks
import ru.arc.core.whenCompleteSync
import ru.arc.gui.ArcMenuSchema
import ru.arc.gui.ArcMenus
import ru.arc.paper.menu.PaperMenuClickContext
import ru.arc.paper.menu.PaperMenuItemRenderContext

internal object InventorySnapshotInspector {
    fun open(viewer: Player, target: String) {
        if (!authorized(viewer)) return
        val source = try {
            if (Bukkit.getPluginManager().isPluginEnabled("HuskSync")) HuskSyncInventorySnapshotSource() else null
        } catch (failure: LinkageError) {
            unavailable(viewer)
            return
        } catch (failure: IllegalStateException) {
            unavailable(viewer)
            return
        }
        if (source == null) { unavailable(viewer); return }
        load(viewer, target, source)
    }

    internal fun load(viewer: Player, target: String, source: InventorySnapshotSource) {
        val tasks = LifecycleTaskScope()
        ArcMenus.beginDialogFlow(viewer)
        ArcMenus.openDialog(viewer, InventorySnapshotScreens.notice("loading", "<gray>Загружаем сохранения…"),
            onDismiss = tasks::close)
        try {
            source.find(target).whenCompleteSync(tasks) { history, failure ->
                try {
                    if (!authorized(viewer)) return@whenCompleteSync
                    when {
                        failure != null -> {
                            // Driver messages can contain connection details; log only a bounded diagnostic class.
                            ARC.instance.logger.warning("Snapshot inventory lookup failed: ${failure.javaClass.simpleName}")
                            ArcMenus.openDialog(viewer, InventorySnapshotScreens.notice("failed", "<red>Не удалось загрузить сохранения. Попробуйте позже."))
                        }
                        history == null -> ArcMenus.openDialog(viewer,
                            InventorySnapshotScreens.notice("not-found", "<gray>У этого игрока нет записи в HuskSync."))
                        history.snapshots.isEmpty() -> ArcMenus.openDialog(viewer,
                            InventorySnapshotScreens.notice("empty", "<gray>У этого игрока нет сохранённых инвентарей."))
                        else -> showList(viewer, history, 0)
                    }
                } finally { tasks.close() }
            }
        } catch (failure: RuntimeException) {
            tasks.close()
            ARC.instance.logger.warning("Snapshot inventory lookup could not start: ${failure.javaClass.simpleName}")
            ArcMenus.openDialog(viewer, InventorySnapshotScreens.notice("failed", "<red>Не удалось загрузить сохранения. Попробуйте позже."))
        }
    }

    private fun showList(viewer: Player, history: SnapshotHistory, page: Int) {
        if (!authorized(viewer)) return
        ArcMenus.openDialog(viewer, InventorySnapshotScreens.list(history, page,
            select = { selected -> if (authorized(viewer)) showInventory(viewer, history, selected, page) },
            navigate = { next -> if (authorized(viewer)) showList(viewer, history, next) },
        ))
    }

    private fun showInventory(viewer: Player, history: SnapshotHistory, snapshot: InventorySnapshot, page: Int) {
        if (!snapshot.hasInventory) {
            viewer.sendMessage(SnapshotText.get("no-inventory", "<gray>В этом сохранении нет данных инвентаря."))
            ArcMenus.beginDialogFlow(viewer)
            showList(viewer, history, page)
            return
        }
        val canCopy = viewer.hasPermission(SNAPSHOT_COPY_PERMISSION)
        val entries = InventorySnapshotScreens.SLOT_ORDER.map { slot ->
            val preview = InventorySnapshotScreens.preview(snapshot, slot, canCopy)
            if (preview == null) ArcMenus.entry(ArcMenus.item("snapshot-empty", PaperMenuItemRenderContext(
                values = mapOf("slot" to Component.text(InventorySnapshotScreens.slotLabel(slot))),
            )), enabled = false)
            else ArcMenus.entryWithContext(preview, acceptedClicks = setOf(ClickType.LEFT)) { context ->
                val result = SnapshotCopy.copy(viewer, context.player, currentView(viewer, context), context.event.click, snapshot, slot) { item ->
                    ARC.instance.logger.info("[SnapshotCopy] actor=${viewer.name} actorUuid=${viewer.uniqueId} " +
                        "target=${history.playerName} targetUuid=${history.playerId} snapshot=${snapshot.id} " +
                        "slot=$slot amount=${item.amount} type=${item.type.key}")
                }
                when (result) {
                    SnapshotCopyResult.COPIED -> viewer.sendMessage(SnapshotText.get("copied", "<green>Стак скопирован в ваш инвентарь."))
                    SnapshotCopyResult.FULL -> viewer.sendMessage(SnapshotText.get("full", "<yellow>Нужен один полностью пустой слот в вашем инвентаре."))
                    SnapshotCopyResult.DENIED -> viewer.sendMessage(SnapshotText.get("copy-denied", "<red>Для копирования требуется разрешение arc.invlist.copy."))
                    else -> Unit
                }
            }
        }
        val summary = ArcMenus.item(ArcMenuSchema.INVENTORY_SNAPSHOT, "summary", PaperMenuItemRenderContext(
            values = mapOf(
                "player" to Component.text(history.playerName),
                "date" to Component.text(InventorySnapshotScreens.timestamp(snapshot)),
                "server" to Component.text(snapshot.server),
                "cause" to Component.text(snapshot.cause),
                "id" to Component.text(snapshot.id.toString()),
            ),
            flags = if (canCopy) setOf("copy") else emptySet(),
        ))
        ArcMenus.open(viewer, ArcMenuSchema.INVENTORY_SNAPSHOT,
            Component.text("${history.playerName} · ${InventorySnapshotScreens.compactTimestamp(snapshot)}", NamedTextColor.DARK_GRAY),
            elements = mapOf(
                "summary" to ArcMenus.entry(summary, enabled = false),
                "back" to ArcMenus.entryWithContext(ArcMenus.item(ArcMenuSchema.INVENTORY_SNAPSHOT, "back"), acceptedClicks = setOf(ClickType.LEFT)) { context ->
                    // Opening/closing a native inventory from its click handler is unsafe; verify again next tick.
                    Tasks.scheduler.runLater(1) {
                        if (currentView(viewer, context) && authorized(viewer)) {
                            context.session.close()
                            ArcMenus.beginDialogFlow(viewer)
                            showList(viewer, history, page)
                        }
                    }
                },
            ),
            regions = mapOf(ArcMenuSchema.SNAPSHOT_SLOTS to entries),
        )
    }

    private fun currentView(viewer: Player, context: PaperMenuClickContext): Boolean =
        context.player === viewer && Bukkit.getPlayer(viewer.uniqueId) === viewer &&
            context.session.isOpen && viewer.openInventory.topInventory === context.session.inventory &&
            ARC.instance.isEnabled && Bukkit.getPluginManager().isPluginEnabled("HuskSync")

    private fun authorized(viewer: Player): Boolean {
        if (!viewer.isOnline || Bukkit.getPlayer(viewer.uniqueId) !== viewer || !ARC.instance.isEnabled) return false
        if (viewer.hasPermission(SNAPSHOT_VIEW_PERMISSION)) return true
        viewer.sendMessage(CommandConfig.noPermission())
        return false
    }

    private fun unavailable(viewer: Player) = viewer.sendMessage(
        SnapshotText.get("unavailable", "<gray>Сохранённые инвентари недоступны на этом сервере: HuskSync не включён."),
    )
}
