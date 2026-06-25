package ru.arc.treasurechests

import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import ru.arc.ARC
import ru.arc.common.chests.CustomChest
import ru.arc.common.chests.ItemsAdderChest
import ru.arc.common.chests.VanillaChest
import ru.arc.common.locationpools.LocationPool
import ru.arc.core.TaskScheduler
import ru.arc.core.Tasks
import ru.arc.hooks.HookRegistry
import ru.arc.treasure.core.Treasures
import ru.arc.util.Logging.info
import ru.arc.util.Logging.warn
import ru.arc.util.TextUtil.mm
import java.util.UUID

/**
 * Реестр активных охот за сокровищами.
 *
 * Делегирует в TreasureHuntService для основной логики.
 * Этот объект существует для обратной совместимости.
 */
object TreasureHuntRegistry {
    private var service: TreasureHuntService? = null
    private var moduleConfig: TreasureHuntModuleConfig? = null

    /**
     * Initialize service with dependencies.
     */
    fun init() {
        if (ARC.plugin == null) return

        val scheduler: TaskScheduler = Tasks.scheduler

        val announcer =
            object : MessageAnnouncer {
                override fun sendToWorld(
                    world: World,
                    message: String,
                ) {
                    val component = mm(message)
                    world.players.forEach { it.sendMessage(component) }
                }

                override fun sendGlobally(
                    uuid: UUID,
                    message: String,
                ) {
                    ru.arc.xserver.announcements.AnnounceManager
                        .sendMessageGlobally(uuid, message)
                }

                override fun getPlayerUuids(): Set<UUID> =
                    ru.arc.xserver.playerlist.PlayerManager
                        .getPlayerUuids()
            }

        val chestSpawner =
            object : ChestSpawner {
                override fun createChest(
                    block: Block,
                    variant: ChestVariant,
                    namespaceId: String?,
                ): CustomChest? {
                    return when (variant) {
                        ChestVariant.VANILLA -> {
                            VanillaChest(block)
                        }

                        ChestVariant.ITEMS_ADDER -> {
                            if (namespaceId == null) {
                                warn("ItemsAdder chest requires namespaceId")
                                return null
                            }
                            if (HookRegistry.itemsAdderHook == null) {
                                warn("ItemsAdder not loaded")
                                return null
                            }
                            ItemsAdderChest(block, namespaceId)
                        }
                    }
                }

                override fun clearChest(
                    block: Block,
                    customBlockDataKey: NamespacedKey,
                ) {
                    // Handled by CustomChest.destroy()
                }
            }

        moduleConfig = TreasureHuntModuleConfig.load(ARC.instance.dataPath)
        service = TreasureHuntService(moduleConfig!!, scheduler, announcer, chestSpawner)

        info("TreasureHuntRegistry initialized")
    }

    // === Публичный API ===

    /**
     * Получает алиасы для ItemsAdder ID.
     */
    fun getAliases(): Map<String, String> = service?.getAliases() ?: emptyMap()

    /**
     * Получает сообщение о начале охоты по умолчанию.
     */
    fun getDefaultStartMessage(): String? = service?.getMessages()?.defaultStartMessage

    /**
     * Получает сообщение об окончании охоты по умолчанию.
     */
    fun getDefaultStopMessage(): String? = service?.getMessages()?.defaultStopMessage

    /**
     * Запускает охоту с указанным типом.
     */
    fun startHunt(
        typeId: String,
        chestCount: Int,
        sender: CommandSender,
    ): ActiveHunt? {
        val hunt = service?.startHunt(typeId, chestCount)
        if (hunt == null) {
            sender.sendMessage(mm("<red>Could not start treasure hunt type: <yellow>$typeId</yellow>"))
        }
        return hunt
    }

    /**
     * Запускает охоту с указанными параметрами.
     */
    fun startHunt(
        locationPool: LocationPool,
        chestCount: Int,
        chestVariant: ChestVariant,
        namespaceId: String?,
        treasurePoolId: String,
        sender: CommandSender,
    ): ActiveHunt? {
        // Проверяем treasure pool
        val treasurePool = Treasures.getPool(treasurePoolId)
        if (treasurePool == null) {
            warn("Could not find treasure pool: $treasurePoolId")
            sender.sendMessage(mm("<red>Could not find treasure pool: <yellow>$treasurePoolId</yellow>"))
            return null
        }

        // Проверяем ItemsAdder hook
        if (chestVariant == ChestVariant.ITEMS_ADDER && HookRegistry.itemsAdderHook == null) {
            throw IllegalArgumentException("ItemsAdder is not loaded!")
        }

        // Создаём тип сундука
        val chestType =
            when (chestVariant) {
                ChestVariant.VANILLA -> {
                    ChestType.vanilla(treasurePoolId)
                }

                ChestVariant.ITEMS_ADDER -> {
                    ChestType.itemsAdder(
                        namespaceId ?: throw IllegalArgumentException("namespaceId required for ItemsAdder"),
                        treasurePoolId,
                    )
                }
            }

        val hunt = service?.startHunt(locationPool, chestCount, chestType)
        if (hunt == null) {
            sender.sendMessage(mm("<red>Could not start treasure hunt"))
        }
        return hunt
    }

    /**
     * Останавливает указанную охоту.
     */
    fun stopHunt(hunt: ActiveHunt) {
        service?.stopHunt(hunt)
    }

    /**
     * Удаляет охоту из реестра (legacy, не требуется с новой архитектурой).
     */
    fun removeHunt(hunt: ActiveHunt) {
        // No-op, service handles this internally
    }

    /**
     * Останавливает все охоты.
     */
    fun stopAll() {
        service?.stopAll()
    }

    /**
     * Получает охоту по блоку.
     */
    fun getByBlock(block: Block): ActiveHunt? = service?.getByBlock(block)

    /**
     * Получает охоту по пулу локаций.
     */
    fun getByLocationPool(pool: LocationPool): ActiveHunt? = service?.getByLocationPool(pool)

    /**
     * Обрабатывает открытие сундука.
     */
    fun claimChest(
        block: Block,
        player: Player,
    ): Boolean = service?.claimChest(block, player) ?: false

    /**
     * Получает все активные охоты.
     */
    fun getActiveHunts(): List<ActiveHunt> = service?.getActiveHunts() ?: emptyList()

    fun hasActiveHunts(): Boolean = service?.hasActiveHunts() == true

    /**
     * Получает все ID зарегистрированных типов охот.
     */
    fun getHuntTypeIds(): List<String> = service?.getHuntTypeIds() ?: emptyList()

    /**
     * Получает конфигурацию типа охоты.
     */
    fun getHuntConfig(id: String): TreasureHuntConfig? = service?.getHuntConfig(id)

    /**
     * Обрабатывает выход игрока.
     */
    fun onPlayerQuit(player: Player) {
        service?.onPlayerQuit(player)
    }

    /**
     * Загружает типы охот из конфигурации.
     */
    fun loadHuntTypes() {
        if (ARC.plugin == null) return
        moduleConfig = TreasureHuntModuleConfig.load(ARC.instance.dataPath)
        val existing = service
        if (existing == null) {
            init()
        } else {
            existing.reloadConfig(moduleConfig!!)
        }
        info("Treasure hunt types reloaded")
    }
}
