package ru.arc.enchanting

import org.bukkit.Bukkit
import org.bukkit.event.HandlerList
import ru.arc.core.PluginModule
import ru.arc.dungeons.ArcDungeonsPlugin
import ru.arc.eliteloot.EliteEnchantmentBookPresentationText
import ru.arc.eliteloot.bindEliteEnchantmentCatalog
import ru.arc.eliteloot.clearEliteEnchantmentCatalog

object EliteEnchantingModule : PluginModule {
    override val name = "EliteEnchanting"
    override val priority = 79
    internal var bookText = EliteEnchantmentBookPresentationText()
        private set
    private var controller: EliteEnchantingController? = null

    override fun init() {
        val config = EliteEnchantingConfig.load(ArcDungeonsPlugin.instance.legacyDataPath)
        shutdown()
        bookText = config.bookText
        val native = Bukkit.getPluginManager().getPlugin("EliteMobs")?.takeIf { it.isEnabled } ?: return
        bindEliteEnchantmentCatalog(native.javaClass.classLoader)
        controller = EliteEnchantingController(config).also {
            Bukkit.getPluginManager().registerEvents(it, ArcDungeonsPlugin.instance)
        }
    }
    override fun reload() = init()
    override fun shutdown() {
        controller?.let { HandlerList.unregisterAll(it); it.close() }
        controller = null
        clearEliteEnchantmentCatalog()
    }
}
