package ru.arc.treasurechests

import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.server.ServiceRegisterEvent
import org.bukkit.event.server.ServiceUnregisterEvent
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
import java.util.Locale

/**
 * Реестр активных охот за сокровищами.
 *
 * Делегирует в TreasureHuntService для основной логики.
 * Этот объект существует для обратной совместимости.
 */
object TreasureHuntRegistry : Listener {
    private var service: TreasureHuntService? = null
    private var moduleConfig: TreasureHuntModuleConfig? = null
    private var bossBarRelay: TreasureHuntBossBarRelay? = null
    private var lastBossBarPublicationAt = 0L
    private var eventAmbience: HuntEventAmbience? = null
    private var auroraApiPresent: Boolean? = null

    /**
     * Initialize service with dependencies.
     */
    fun init() {
        if (ARC.plugin == null) return
        shutdown()

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
        eventAmbience = HuntEventAmbience(
            scheduler = scheduler,
            settingsProvider = { moduleConfig?.eventAmbienceSettings ?: HuntEventAmbienceSettings() },
            startWorldAurora = { worldId ->
                if (Bukkit.getPluginManager().isPluginEnabled("ArcAurora") && hasAuroraApi()) {
                    HuntAuroraEventAdapter.start(worldId)
                } else {
                    null
                }
            },
        )
        service = TreasureHuntService(moduleConfig!!, scheduler, announcer, chestSpawner) { hunts ->
            bossBarRelay?.onHuntsChanged(hunts)
            eventAmbience?.onActiveHuntsChanged(hunts)
        }
        reloadBossBarRelay()
        Bukkit.getPluginManager().registerEvents(this, ARC.instance)

        info("TreasureHuntRegistry initialized")
    }

    fun shutdown() {
        service?.shutdown()
        service = null
        closeBossBarRelay()
        eventAmbience?.close()
        eventAmbience = null
        auroraApiPresent = null
        HandlerList.unregisterAll(this)
    }

    private fun reloadBossBarRelay() {
        closeBossBarRelay()
        val redis = ARC.redisManager ?: return
        val settings = moduleConfig?.bossBarRelaySettings ?: return
        bossBarRelay = TreasureHuntBossBarRelay(
            redis,
            Tasks.scheduler,
            ARC.serverName.orEmpty().lowercase(Locale.ROOT),
            settings,
            initialLastSentAt = lastBossBarPublicationAt,
        ).also { it.onHuntsChanged(getActiveHunts()) }
    }

    private fun closeBossBarRelay() {
        val relay = bossBarRelay ?: return
        relay.close()
        lastBossBarPublicationAt = maxOf(lastBossBarPublicationAt, relay.lastPublishedAt)
        bossBarRelay = null
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onPlayerJoin(event: PlayerJoinEvent) = refreshAudience(event.player)

    @EventHandler(priority = EventPriority.MONITOR)
    fun onPlayerWorldChanged(event: PlayerChangedWorldEvent) = refreshAudience(event.player)

    @EventHandler
    fun onServiceRegistered(event: ServiceRegisterEvent) {
        if (event.provider.plugin.name == "ArcAurora") {
            auroraApiPresent = null
            eventAmbience?.refresh()
        }
    }

    @EventHandler
    fun onServiceUnregistered(event: ServiceUnregisterEvent) {
        if (event.provider.plugin.name == "ArcAurora") {
            auroraApiPresent = null
            eventAmbience?.refresh()
        }
    }

    private fun hasAuroraApi(): Boolean = auroraApiPresent ?: huntAuroraApiAvailable(ARC.instance.javaClass.classLoader).also {
        auroraApiPresent = it
        if (!it) warn("[hunt-ambience] ArcAurora is enabled but its service API is unavailable; event effects skipped")
    }

    private fun refreshAudience(player: Player) {
        service?.onPlayerWorldChanged(player)
        bossBarRelay?.refreshAudience()
    }

    // === Публичный API ===

    /**
     * Получает алиасы для ItemsAdder ID.
     */
    fun getAliases(): Map<String, String> = service?.getAliases() ?: emptyMap()

    internal fun getAutoStartSettings(): HuntAutoStartSettings = moduleConfig?.autoStartSettings ?: HuntAutoStartSettings()

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
        bossBarRelay?.onPlayerQuit(player)
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
            reloadBossBarRelay()
            eventAmbience?.refresh()
            existing.reloadConfig(moduleConfig!!)
        }
        info("Treasure hunt types reloaded")
    }
}
