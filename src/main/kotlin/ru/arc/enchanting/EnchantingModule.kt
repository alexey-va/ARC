package ru.arc.enchanting

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.HandlerList
import ru.arc.ARC
import ru.arc.core.PluginModule
import ru.arc.eliteloot.EliteEnchantmentBookPresentationText
import ru.arc.eliteloot.bindEliteEnchantmentCatalog
import ru.arc.eliteloot.clearEliteEnchantmentCatalog

/** Unified book presentation and direct EliteMobs application; enchantments remain provider-owned. */
object EnchantingModule : PluginModule {
    override val name = "Enchanting"
    override val priority = 79
    internal var bookText = EliteEnchantmentBookPresentationText()
        private set
    private var controller: EliteEnchantingController? = null
    private var advancedSafety: AdvancedBookSafetyListener? = null
    private var bookShop: AdvancedBookShop? = null
    private var nativeShopGuard: NativeEnchanterLocationGuard? = null
    private var advancedLoot: AdvancedEnchantmentLoot? = null

    override fun init() {
        val config = EnchantingConfig.load(ARC.instance.dataPath)
        bookText = config.bookText
        shutdown()
        bindAdvancedBookPresentation()
        if (Bukkit.getPluginManager().isPluginEnabled("AdvancedEnchantments")) {
            advancedSafety = AdvancedBookSafetyListener(config).also {
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
        val eliteMobs = Bukkit.getPluginManager().getPlugin("EliteMobs")
        if (eliteMobs == null || !eliteMobs.isEnabled) return
        bindEliteEnchantmentCatalog(eliteMobs.javaClass.classLoader)
        controller = EliteEnchantingController(config).also {
            Bukkit.getPluginManager().registerEvents(it, ARC.instance)
        }
    }

    override fun reload() = init()

    fun canPurchase(player: Player, enchantmentId: String): Boolean =
        bookShop?.canPurchase(player, enchantmentId) == true

    fun openPurchase(player: Player, enchantmentId: String, returnTo: () -> Unit): Boolean =
        bookShop?.openPurchase(player, enchantmentId, returnTo) == true

    override fun shutdown() {
        bookShop?.close()
        bookShop = null
        nativeShopGuard?.let { HandlerList.unregisterAll(it) }
        nativeShopGuard = null
        advancedLoot?.let { HandlerList.unregisterAll(it); it.close() }
        advancedLoot = null
        controller?.let { HandlerList.unregisterAll(it); it.close() }
        controller = null
        advancedSafety?.let { HandlerList.unregisterAll(it); it.close() }
        advancedSafety = null
        clearEliteEnchantmentCatalog()
        clearAdvancedBookPresentation()
    }
}
