package ru.arc.enchanting

import org.bukkit.Bukkit
import org.bukkit.event.HandlerList
import ru.arc.ARC
import ru.arc.config.ConfigManager
import ru.arc.core.PluginModule
import ru.arc.eliteloot.EliteEnchantmentBookPresentationText

/** Adds the book gesture and preview; EliteMobs keeps custody, payment and settlement. */
object EnchantingModule : PluginModule {
    override val name = "Enchanting"
    override val priority = 79
    internal var bookText = EliteEnchantmentBookPresentationText()
        private set
    private var controller: EliteEnchantingController? = null

    override fun init() {
        val config = EnchantingConfig(ConfigManager.ofModule(ARC.instance.dataPath, "enchanting.yml"))
        bookText = config.bookText
        shutdown()
        if (!Bukkit.getPluginManager().isPluginEnabled("EliteMobs")) return
        controller = EliteEnchantingController(config).also {
            Bukkit.getPluginManager().registerEvents(it, ARC.instance)
        }
    }

    override fun reload() = init()
    override fun shutdown() {
        controller?.let { HandlerList.unregisterAll(it); it.close() }
        controller = null
    }
}
