package ru.arc.landsui

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import ru.arc.ARC
import ru.arc.core.PluginModule
import ru.arc.helpcenter.HelpCenterModule
import ru.arc.util.Logging.info
import ru.arc.util.Logging.warn
import ru.arc.util.TextUtil

object LandsUiModule : PluginModule {
    override val name = "LandsUi"
    override val priority = 85

    @Volatile private var settings: LandsUiSettings? = null
    @Volatile private var controller: LandsUiController? = null

    override fun init() = start(LandsUiConfig.load(ARC.instance.dataPath).snapshot())

    override fun reload() = start(LandsUiConfig.load(ARC.instance.dataPath).snapshot())

    override fun shutdown() {
        controller?.close()
        controller = null
        settings = null
    }

    fun isAvailable(): Boolean = controller != null

    fun open(player: Player) {
        val active = controller
        if (active == null) {
            player.sendMessage(TextUtil.mm("<#c42323>Меню поселений сейчас недоступно."))
            return
        }
        active.openRoot(player)
    }

    fun openInvite(player: Player, targetId: java.util.UUID, targetName: String) {
        val active = controller
        if (active == null) {
            open(player)
            return
        }
        active.openInvite(player, LandsUiPlayer(targetId, targetName))
    }

    private fun start(loaded: LandsUiSettings) {
        controller?.close()
        controller = null
        settings = loaded
        if (!loaded.enabled) {
            info("Lands UI module disabled by configuration")
            return
        }
        val lands = Bukkit.getPluginManager().getPlugin("Lands")
        if (lands == null || !lands.isEnabled) {
            warn("Lands UI module disabled because Lands is unavailable")
            return
        }
        controller = LandsUiController(loaded, BukkitLandsUiGateway()) { player ->
            HelpCenterModule.open(player)
        }
        info("Lands UI module initialized")
    }
}
