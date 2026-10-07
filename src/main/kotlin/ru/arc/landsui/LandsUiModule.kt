package ru.arc.landsui

import me.angeschossen.lands.api.LandsIntegration
import me.angeschossen.lands.api.items.ItemType
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
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
    private var commandShortcuts: ClaimMenuCommandListener? = null
    private var claimTool: ClaimBlockTool? = null
    private var regionAreaLimits: AutoCloseable? = null

    override fun init() = start(LandsUiConfig.load(ARC.instance.dataPath).snapshot())

    override fun reload() = start(LandsUiConfig.load(ARC.instance.dataPath).snapshot())

    override fun shutdown() {
        commandShortcuts?.close()
        commandShortcuts = null
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

    internal fun hasClaimMenu(player: Player): Boolean = claimTool?.hasMenu(player) == true

    internal fun isClaimMenuTarget(player: Player): Boolean = claimTool?.isMenuTarget(player) == true

    fun createClaimBlockItem(player: Player): ItemStack? {
        if (!Bukkit.getPluginManager().isPluginEnabled("Lands")) return null
        return runCatching {
            val landPlayer = LandsIntegration.of(ARC.instance).getLandPlayer(player.uniqueId) ?: return null
            ItemType.CLAIM_BLOCK.build(landPlayer)
        }.onFailure { failure ->
            warn("Could not create Lands claim block for {}: {}", player.name, failure.message)
        }.getOrNull()
    }

    fun open(player: Player) {
        ArcMenus.beginDialogFlow(player)
        val active = controller
        if (active == null) {
            player.sendMessage(TextUtil.mm("<#c42323>Меню поселений сейчас недоступно."))
            return
        }
        active.openRoot(player)
    }

    fun openCurrent(player: Player) {
        ArcMenus.beginDialogFlow(player)
        val active = controller ?: return open(player)
        active.openCurrent(player)
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

    fun openPanelAction(player: Player, landId: String, action: LandsUiPanelAction) {
        ArcMenus.beginDialogFlow(player)
        val active = controller ?: return open(player)
        active.openPanelAction(player, landId, action)
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
        commandShortcuts?.close()
        commandShortcuts = null
        controller?.close()
        controller = null
        claimTool?.close()
        claimTool = null
        regionTool?.close()
        regionTool = null
        regionAreaLimits?.close()
        regionAreaLimits = null
        settings = loaded
        if (loaded.commandShortcutsEnabled) {
            commandShortcuts = ClaimMenuCommandListener(
                canOpenClaimMenu = ::isAvailable,
                openClaimMenu = ::openCurrent,
            ).also { Bukkit.getPluginManager().registerEvents(it, ARC.instance) }
        }
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
        val gateway = BukkitLandsUiGateway(roleName = loaded::roleName)
        controller = LandsUiController(loaded, gateway)
        regionTool = RegionTool(loaded, gateway).also { it.start() }
        claimTool = ClaimBlockTool(loaded).also { it.start() }
        info("Lands UI module initialized")
    }
}
