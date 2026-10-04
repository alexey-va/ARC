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
    ): ActiveHunt? = startHunt(typeId, chestCount, sender, replaceExisting = true)

    fun startHunt(
        typeId: String,
        chestCount: Int,
        sender: CommandSender,
        replaceExisting: Boolean,
    ): ActiveHunt? {
        val hunt = service?.startHunt(typeId, chestCount, replaceExisting)
        if (hunt == null) {
            sender.sendMessage(mm("<red>Не удалось запустить охоту: <yellow>$typeId</yellow>"))
        }
        return hunt
    }

    /** Starts an already resolved config, preserving its inherited preset settings. */
    fun startHunt(
        config: TreasureHuntConfig,
        chestCount: Int,
        sender: CommandSender,
        replaceExisting: Boolean,
    ): ActiveHunt? {
        if (!validateStartConfig(config, sender)) return null
        val hunt = service?.startHunt(config, chestCount, replaceExisting)
        if (hunt == null) sender.sendMessage(mm("<red>Не удалось запустить охоту."))
        return hunt
    }

    /** Validates reward pools and required hooks before a caller places or generates any chests. */
    internal fun validateStartConfig(config: TreasureHuntConfig, sender: CommandSender): Boolean =
        validateChestTypes(config.chestTypes.values(), sender)

    private fun validateChestTypes(
        chestTypes: Iterable<ChestType>,
        sender: CommandSender,
    ): Boolean {
        val types = chestTypes.toList()
        if (types.isEmpty()) {
            sender.sendMessage(mm("<red>У пресета не настроен ни один вид сундука."))
            return false
        }
        for (chestType in types) {
            if (!validateRewardPool(chestType.treasurePoolId, sender)) return false
            if (chestType.type == ChestVariant.ITEMS_ADDER && !validateItemsAdderHook(sender)) return false
        }
        return true
    }

    private fun validateRewardPool(poolId: String, sender: CommandSender): Boolean {
        if (Treasures.getPool(poolId) != null) return true
        warn("Could not find treasure pool: $poolId")
        sender.sendMessage(mm("<red>Не найден набор наград: <yellow>$poolId</yellow>"))
        return false
    }

    private fun validateItemsAdderHook(
        sender: CommandSender,
        throwIfUnavailable: Boolean = false,
    ): Boolean {
        if (HookRegistry.itemsAdderHook != null) return true
        if (throwIfUnavailable) throw IllegalArgumentException("ItemsAdder is not loaded!")
        sender.sendMessage(mm("<red>ItemsAdder не загружен"))
        return false
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
    ): ActiveHunt? = startHunt(
        locationPool,
        chestCount,
        chestVariant,
        namespaceId,
        treasurePoolId,
        sender,
        replaceExisting = true,
    )

    fun startHunt(
        locationPool: LocationPool,
        chestCount: Int,
        chestVariant: ChestVariant,
        namespaceId: String?,
        treasurePoolId: String,
        sender: CommandSender,
        replaceExisting: Boolean,
    ): ActiveHunt? {
        if (!validateRewardPool(treasurePoolId, sender)) return null
        if (chestVariant == ChestVariant.ITEMS_ADDER && !validateItemsAdderHook(sender, throwIfUnavailable = true)) return null

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

        val hunt = service?.startHunt(locationPool, chestCount, chestType, replaceExisting)
        if (hunt == null) {
            sender.sendMessage(mm("<red>Не удалось запустить охоту."))
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

    internal fun attachGrapple(grapple: TreasureHuntGrapple?) {
        service?.attachGrapple(grapple)
    }

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
