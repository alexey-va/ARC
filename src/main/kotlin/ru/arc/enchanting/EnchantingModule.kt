package ru.arc.enchanting

import org.bukkit.Bukkit
import org.bukkit.block.Block
import org.bukkit.block.data.Ageable
import org.bukkit.entity.Player
import org.bukkit.event.HandlerList
import ru.arc.ARC
import ru.arc.core.PluginModule
import ru.arc.core.Tasks
import ru.arc.ops.ItemDefinition
import ru.arc.ops.ItemPresets
import java.util.Locale

/** AdvancedEnchantments presentation, shop and gameplay integration. */
object EnchantingModule : PluginModule {
    override val name = "Enchanting"
    override val priority = 79
    private var advancedSafety: AdvancedBookSafetyListener? = null
    private var advancedGrindstone: AdvancedGrindstoneRepairCost? = null
    private var creativeWhiteScrollGuard: CreativeWhiteScrollGuard? = null
    private var bookShop: AdvancedBookShop? = null
    private var nativeShopGuard: NativeEnchanterLocationGuard? = null
    private var advancedLoot: AdvancedEnchantmentLoot? = null
    private var miningLoot: EnchantmentMiningLoot? = null
    private var farmingLoot: EnchantmentFarmingLoot? = null
    private var worldCaches: EnchantmentWorldCaches? = null
    private var discoveryRelay: EnchantmentDiscoveryRelay? = null
    private var lastDiscoveryPublishedAt = 0L

    override fun init() {
        val config = EnchantingConfig.load(ARC.instance.dataPath)
        shutdown()
        bindAdvancedBookPresentation()
        if (Bukkit.getPluginManager().isPluginEnabled("AdvancedEnchantments")) {
            try {
                creativeWhiteScrollGuard = CreativeWhiteScrollGuard(
                    requireNotNull(Bukkit.getPluginManager().getPlugin("AdvancedEnchantments")), config,
                ).also { it.start(ARC.instance) }
            } catch (failure: ReflectiveOperationException) {
                ARC.instance.logger.log(java.util.logging.Level.WARNING, "AE creative white-scroll guard unavailable", failure)
            }
            advancedSafety = AdvancedBookSafetyListener(config).also {
                Bukkit.getPluginManager().registerEvents(it, ARC.instance)
            }
            advancedGrindstone = AdvancedGrindstoneRepairCost().also {
                Bukkit.getPluginManager().registerEvents(it, ARC.instance)
            }
            bookShop = AdvancedBookShop(config).also { it.start() }
            nativeShopGuard = NativeEnchanterLocationGuard(
                requireNotNull(Bukkit.getPluginManager().getPlugin("AdvancedEnchantments")), config,
            ).also { Bukkit.getPluginManager().registerEvents(it, ARC.instance) }
            try {
                advancedLoot = AdvancedEnchantmentLoot(config.lootSettings).also {
                    Bukkit.getPluginManager().registerEvents(it, ARC.instance)
                }
            } catch (failure: IllegalArgumentException) {
                ARC.instance.logger.warning("AE gameplay loot disabled: invalid modules/enchanting.yml loot settings: ${failure.message}")
            }
            startDiscoveryPart("sources") { startDiscovery(config) }
        }
        startDiscoveryRelay()
    }

    override fun reload() = init()

    fun canPurchase(player: Player, enchantmentId: String): Boolean =
        bookShop?.canPurchase(player, enchantmentId) == true

    fun openPurchase(player: Player, enchantmentId: String, returnTo: () -> Unit): Boolean =
        bookShop?.openPurchase(player, enchantmentId, returnTo) == true

    internal fun activeLootSettings(): AdvancedEnchantmentLootSettings? = advancedLoot?.settingsSnapshot()

    internal fun activeMiningCacheSettings(): EnchantmentMiningLootSettings? = miningLoot?.settingsSnapshot()

    internal fun activeFarmingCacheSettings(): EnchantmentFarmingLootSettings? = farmingLoot?.settingsSnapshot()

    internal fun activeWorldCacheSettings(): EnchantmentWorldCacheSettings? = worldCaches?.settingsSnapshot()

    /** Unknown remote state is distinct from a fresh report of inactive Survival sources. */
    internal fun survivalDiscoverySnapshot(): EnchantmentDiscoverySnapshot? =
        if (ARC.serverName.orEmpty().lowercase(Locale.ROOT) == "survival") {
            EnchantmentDiscoverySnapshot(System.currentTimeMillis(), activeMiningCacheSettings(), activeWorldCacheSettings(), activeFarmingCacheSettings())
        } else discoveryRelay?.snapshot()

    private fun startDiscoveryRelay() {
        val redis = ARC.redisManager ?: return
        startDiscoveryPart("relay") {
            discoveryRelay = EnchantmentDiscoveryRelay(
                redis, Tasks.scheduler, ARC.serverName.orEmpty().lowercase(Locale.ROOT),
                activeMiningSettings = ::activeMiningCacheSettings,
                activeWorldCacheSettings = ::activeWorldCacheSettings,
                initialLastSentAt = lastDiscoveryPublishedAt,
                activeFarmingSettings = ::activeFarmingCacheSettings,
            )
        }
    }

    private fun startDiscovery(config: EnchantingConfig) {
        // Do not advertise or issue a missing/misconfigured preset as an AE box.
        val preset = ItemPresets.loadedDefinitions(listOf("ae_lootbox"))?.singleOrNull() as? ItemDefinition
        val customData = preset?.item?.get("customData")?.takeIf { it.isJsonObject }?.asJsonObject
        if (customData?.get("arc:treasure_key")?.takeIf { it.isJsonPrimitive }?.asString != "ae") {
            ARC.instance.logger.warning("AE discovery sources disabled: ae_lootbox preset is missing or has no AE treasure key")
            return
        }
        startDiscoveryPart("mining") {
            // Without the owner of placement markers, unknown blocks cannot mint mining rewards.
            if (config.miningCacheEnabled && Bukkit.getPluginManager().isPluginEnabled("eco")) {
                val settings = config.miningCacheSettings
                if (settings.survivalBackend) {
                    val listener = EnchantmentMiningLoot(settings, isNaturalBlock = ::isUnmarkedBlock)
                    try { Bukkit.getPluginManager().registerEvents(listener, ARC.instance) }
                    catch (failure: Throwable) { listener.close(); throw failure }
                    miningLoot = listener
                }
            }
        }
        startDiscoveryPart("surface") {
            val settings = config.worldCacheSettings
            if (settings.enabled && settings.survivalBackend && Bukkit.getPluginManager().isPluginEnabled("eco")) {
                val listener = EnchantmentWorldCaches(settings, isNaturalBlock = ::isUnmarkedBlock)
                try { Bukkit.getPluginManager().registerEvents(listener, ARC.instance) }
                catch (failure: Throwable) { listener.close(); throw failure }
                worldCaches = listener
            }
        }
        startDiscoveryPart("farming") {
            if (config.farmingCacheEnabled) {
                val settings = config.farmingCacheSettings
                if (settings.survivalBackend) {
                    require(settings.materials.all { it.createBlockData() is Ageable }) {
                        "Every farming-cache material must have an ageable crop state"
                    }
                    val listener = EnchantmentFarmingLoot(settings)
                    try { Bukkit.getPluginManager().registerEvents(listener, ARC.instance) }
                    catch (failure: Throwable) { listener.close(); throw failure }
                    farmingLoot = listener
                }
            }
        }
    }

    private inline fun startDiscoveryPart(name: String, start: () -> Unit) {
        try {
            start()
        } catch (failure: Exception) {
            ARC.instance.logger.log(java.util.logging.Level.WARNING, "AE $name discovery source unavailable", failure)
        } catch (failure: LinkageError) {
            ARC.instance.logger.log(java.util.logging.Level.WARNING, "AE $name discovery dependency unavailable", failure)
        }
    }

    /** Eco records ordinary player placement and piston moves; an absent marker is not proof of world generation. */
    private fun isUnmarkedBlock(block: Block): Boolean {
        if (!Bukkit.getPluginManager().isPluginEnabled("eco")) return false
        return try {
            !com.willfp.eco.util.BlockUtils.isPlayerPlaced(block)
        } catch (_: Exception) {
            false
        } catch (_: LinkageError) {
            false
        }
    }

    override fun shutdown() {
        discoveryRelay?.let {
            it.close()
            lastDiscoveryPublishedAt = maxOf(lastDiscoveryPublishedAt, it.lastPublishedAt)
        }
        discoveryRelay = null
        miningLoot?.let { HandlerList.unregisterAll(it); it.close() }
        miningLoot = null
        farmingLoot?.let { HandlerList.unregisterAll(it); it.close() }
        farmingLoot = null
        worldCaches?.let { HandlerList.unregisterAll(it); it.close() }
        worldCaches = null
        creativeWhiteScrollGuard?.close()
        creativeWhiteScrollGuard = null
        bookShop?.close()
        bookShop = null
        nativeShopGuard?.let { HandlerList.unregisterAll(it) }
        nativeShopGuard = null
        advancedLoot?.let { HandlerList.unregisterAll(it); it.close() }
        advancedLoot = null
        advancedSafety?.let { HandlerList.unregisterAll(it); it.close() }
        advancedSafety = null
        advancedGrindstone?.let { HandlerList.unregisterAll(it) }
        advancedGrindstone = null
        clearAdvancedBookPresentation()
    }
}
