package ru.arc.enchanting

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.HandlerList
import ru.arc.ARC
import ru.arc.core.PluginModule

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
        }

    }

    override fun reload() = init()

    fun canPurchase(player: Player, enchantmentId: String): Boolean =
        bookShop?.canPurchase(player, enchantmentId) == true

    fun openPurchase(player: Player, enchantmentId: String, returnTo: () -> Unit): Boolean =
        bookShop?.openPurchase(player, enchantmentId, returnTo) == true

    override fun shutdown() {
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
