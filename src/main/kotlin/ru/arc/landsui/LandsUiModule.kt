package ru.arc.landsui

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import ru.arc.ARC
import ru.arc.gui.ArcMenus
import ru.arc.core.PluginModule
import ru.arc.util.Logging.info
import ru.arc.util.Logging.warn
import ru.arc.util.TextUtil

object LandsUiModule : PluginModule {
    override val name = "LandsUi"
    override val priority = 85

    @Volatile private var settings: LandsUiSettings? = null
    @Volatile private var controller: LandsUiController? = null
    @Volatile private var regionTool: RegionTool? = null
    private var claimTool: ClaimBlockTool? = null
    private var regionAreaLimits: AutoCloseable? = null

    override fun init() = start(LandsUiConfig.load(ARC.instance.dataPath).snapshot())

    override fun reload() = start(LandsUiConfig.load(ARC.instance.dataPath).snapshot())

    override fun shutdown() {
        claimTool?.close()
        claimTool = null
        regionTool?.close()
        regionTool = null
        regionAreaLimits?.close()
        regionAreaLimits = null
        controller?.close()
        controller = null
        settings = null
    }

    fun isAvailable(): Boolean = controller != null

    fun open(player: Player) {
        ArcMenus.beginDialogFlow(player)
        val active = controller
        if (active == null) {
            player.sendMessage(TextUtil.mm("<#c42323>Меню поселений сейчас недоступно."))
            return
        }
        active.openRoot(player)
    }

    fun openAddMember(player: Player, landId: String) {
        ArcMenus.beginDialogFlow(player)
        val active = controller ?: return open(player)
        active.openAddMember(player, landId)
    }

    fun openDetails(player: Player, landId: String) {
        ArcMenus.beginDialogFlow(player)
        val active = controller ?: return open(player)
        active.openDetails(player, landId)
    }

    fun giveRegionTool(player: Player, landId: String) {
        regionTool?.give(player, landId) ?: open(player)
    }

    fun openInvite(player: Player, targetId: java.util.UUID, targetName: String) {
        ArcMenus.beginDialogFlow(player)
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
        claimTool?.close()
        claimTool = null
        regionTool?.close()
        regionTool = null
        regionAreaLimits?.close()
        regionAreaLimits = null
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
        regionAreaLimits = RegionAreaLimits.install()
        val gateway = BukkitLandsUiGateway()
        controller = LandsUiController(loaded, gateway)
        regionTool = RegionTool(loaded, gateway).also { it.start() }
        claimTool = ClaimBlockTool(loaded).also { it.start() }
        info("Lands UI module initialized")
    }
}
