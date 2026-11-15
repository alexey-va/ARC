package ru.arc.invsnapshots

import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.whenCompleteSync
import java.util.UUID
import java.util.concurrent.CompletionStage

internal enum class SnapshotLoadResult(val key: String, val message: String) {
    SAVING("load-saving", "<gray>Сохраняем ваш текущий инвентарь…"),
    DENIED("copy-denied", "<red>Для копирования требуется разрешение arc.invlist.copy."),
    LEGACY("load-legacy", "<yellow>HuskSync не сохранил исходные позиции при обновлении этого снимка. Доступно копирование по одному стеку."),
    INCOMPLETE("load-incomplete", "<yellow>В снимке неполные данные. Можно скопировать отдельные предметы."),
    NOT_READY("load-not-ready", "<yellow>Дождитесь синхронизации и освободите курсор."),
    ALREADY_LOADED("load-same", "<gray>У вас уже загружен этот инвентарь."),
    CHANGED("load-changed", "<yellow>Ваш инвентарь изменился во время сохранения. Нажмите ещё раз."),
    BACKUP_FAILED("load-backup-failed", "<red>Не удалось сохранить ваш инвентарь. Загрузка отменена."),
    APPLY_FAILED("load-failed", "<red>Загрузить инвентарь не удалось. Ваш прежний снимок <white><backup></white> закреплён в <white>/arc invlist <viewer></white>."),
    LOADED("loaded", "<green>Инвентарь загружен. <gray>Ваш прежний снимок <white><backup></white> закреплён в <white>/arc invlist <viewer></white>."),
}

/** A single open preview can have one pending load. All live state checks and item changes run on Paper. */
internal class SnapshotLoad(
    private val synchronized: (Player) -> Boolean,
    private val backup: (Player) -> CompletionStage<UUID>,
) {
    private var saving = false

    fun start(
        viewer: Player,
        actor: Player,
        click: ClickType,
        currentView: () -> Boolean,
        snapshot: InventorySnapshot,
        report: (SnapshotLoadResult, UUID?) -> Unit,
    ) {
        if (actor !== viewer || click != ClickType.LEFT || !viewer.isOnline || !currentView()) return
        fun ready() = !viewer.isDead && viewer.itemOnCursor.isEmpty && synchronized(viewer)
        val desired = snapshot.inventory
        when {
            !viewer.canCopySnapshot() -> return report(SnapshotLoadResult.DENIED, null)
            !snapshot.exactSlotOrder -> return report(SnapshotLoadResult.LEGACY, null)
            desired == null || !desired.fits(viewer) -> return report(SnapshotLoadResult.INCOMPLETE, null)
            saving -> return
            !ready() -> return report(SnapshotLoadResult.NOT_READY, null)
            desired.matches(viewer) -> return report(SnapshotLoadResult.ALREADY_LOADED, null)
        }
        val before = SnapshotInventory.capture(viewer)
        val write = try {
            backup(viewer)
        } catch (failure: RuntimeException) {
            return report(SnapshotLoadResult.BACKUP_FAILED, null)
        }
        saving = true
        report(SnapshotLoadResult.SAVING, null)
        val tasks = LifecycleTaskScope()
        write.whenCompleteSync(tasks) { id, failure ->
            try {
                if (!viewer.isOnline || !currentView()) return@whenCompleteSync
                val result = when {
                    !viewer.canCopySnapshot() -> SnapshotLoadResult.DENIED
                    failure != null || id == null -> SnapshotLoadResult.BACKUP_FAILED
                    !ready() -> SnapshotLoadResult.NOT_READY
                    !before.matches(viewer) -> SnapshotLoadResult.CHANGED
                    else -> try {
                        desired.apply(viewer)
                        SnapshotLoadResult.LOADED
                    } catch (failure: RuntimeException) {
                        // The verified recovery snapshot remains usable even if Paper rejects a rollback.
                        runCatching { before.apply(viewer) }
                        SnapshotLoadResult.APPLY_FAILED
                    }
                }
                report(result, id)
            } finally {
                saving = false
                tasks.close()
            }
        }
    }
}
