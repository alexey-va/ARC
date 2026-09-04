package ru.arc.helpcenter

import org.bukkit.entity.Player
import ru.arc.ARC
import ru.arc.core.PluginModule
import ru.arc.landsui.LandsUiModule
import ru.arc.util.Logging.error
import ru.arc.util.Logging.info
import ru.arc.util.Common

object HelpCenterModule : PluginModule {
    override val name = "HelpCenter"
    override val priority = 86

    @Volatile private var controller: HelpCenterController? = null

    override fun init() = start(HelpCenterConfig.load(ARC.instance.dataPath).snapshot())

    override fun reload() = start(HelpCenterConfig.load(ARC.instance.dataPath).snapshot())

    override fun shutdown() {
        controller?.close()
        controller = null
    }

    fun isAvailable(): Boolean = controller != null

    fun open(player: Player, page: HelpCenterPage = HelpCenterPage.ROOT): Boolean {
        val current = controller ?: return false
        return runCatching { current.open(player, page) }
            .onFailure { failure -> error("Could not open help center for {}", player.name, failure) }
            .isSuccess
    }

    private fun start(settings: HelpCenterSettings) {
        controller?.close()
        controller = null
        if (!settings.enabled) {
            info("Help center module disabled by configuration")
            return
        }
        val preferenceStore = ARC.redisManager?.let { RedisHelpCenterPreferenceStore(it, Common.gson) }
            ?: UnavailableHelpCenterPreferenceStore()
        controller = HelpCenterController(
            settings = settings,
            gateway = BukkitHelpCenterGateway(),
            openLands = LandsUiModule::open,
            inventoryReturn = HelpCenterInventoryReturnRuntime(ARC.instance),
            inviteToLand = { player, target -> LandsUiModule.openInvite(player, target.id, target.name) },
            preferences = preferenceStore,
        )
        info("Help center module initialized")
    }
}
