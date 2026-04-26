package ru.arc.common.locationpools

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.block.BlockPlaceEvent
import ru.arc.ARC
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.TaskScheduler
import java.util.UUID

/**
 * Управляет режимом редактирования локаций для игроков.
 *
 * Делегирует в LocationPoolService для основной логики.
 */
object LocationPoolEditor {
    private var service: LocationPoolService? = null

    /**
     * Initialize with service.
     */
    fun init() {
        if (ARC.plugin == null) return

        val scheduler: TaskScheduler = BukkitTaskScheduler(ARC.instance)
        val playerProvider =
            object : PlayerProvider {
                override fun getPlayer(uuid: UUID): Player? = Bukkit.getPlayer(uuid)
            }

        val moduleConfig = LocationPoolModuleConfig.load(ARC.instance.dataPath)
        service = LocationPoolService(moduleConfig, scheduler, playerProvider)
        service?.start()
    }

    /**
     * Начинает режим редактирования пула для игрока.
     */
    fun startEditing(
        player: Player,
        poolId: String,
    ) {
        requireService().startEditing(player, poolId)
    }

    /**
     * Начинает редактирование по UUID (для обратной совместимости).
     */
    fun startEditing(
        uuid: UUID,
        poolId: String,
    ) {
        val player = Bukkit.getPlayer(uuid) ?: return
        startEditing(player, poolId)
    }

    /**
     * Отменяет режим редактирования для игрока.
     */
    fun cancelEditing(
        uuid: UUID,
        timeout: Boolean = false,
    ) {
        service?.cancelEditing(uuid, timeout)
    }

    /**
     * Получает ID редактируемого пула для игрока.
     */
    fun getEditingPool(uuid: UUID): String? = service?.getEditingPool(uuid)

    /**
     * Проверяет, редактирует ли игрок какой-либо пул.
     */
    fun isEditing(uuid: UUID): Boolean = service?.isEditing(uuid) ?: false

    /**
     * Обрабатывает размещение блока в режиме редактирования.
     *
     * @return true если событие было обработано
     */
    fun processBlockPlace(event: BlockPlaceEvent): Boolean = service?.processBlockPlace(event) ?: false

    /**
     * Запускает задачу отображения партиклов для редактируемых локаций.
     */
    fun startShowTask() {
        // Service handles this in start()
    }

    /**
     * Запускает задачу таймаута для неактивных сессий.
     */
    fun startTimeoutTask() {
        // Service handles this in start()
    }

    /**
     * Останавливает все задачи.
     */
    fun stopTasks() {
        service?.stop()
    }

    /**
     * Очищает все сессии редактирования.
     */
    fun clear() {
        service?.clearSessions()
    }

    /**
     * Отменяет редактирование для всех игроков, редактирующих указанный пул.
     */
    fun cancelEditingForPool(poolId: String) {
        service?.cancelEditingForPool(poolId)
    }

    /**
     * Перезагружает конфигурацию.
     */
    fun reloadConfig() {
        if (ARC.plugin == null) return
        val newConfig = LocationPoolModuleConfig.load(ARC.instance.dataPath)
        service?.reloadConfig(newConfig)
    }

    private fun requireService(): LocationPoolService =
        service ?: throw IllegalStateException("LocationPoolEditor not initialized. Call init() first.")
}
