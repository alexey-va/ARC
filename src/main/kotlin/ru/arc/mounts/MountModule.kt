package ru.arc.mounts

import net.luckperms.api.LuckPerms
import org.bukkit.Material
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.command.TabExecutor
import org.bukkit.entity.EntityType
import ru.arc.ARC
import ru.arc.core.PluginModule
import ru.arc.core.Tasks
import ru.arc.core.modules.EconomyModule
import ru.arc.util.Logging.info
import ru.arc.util.Logging.warn
import ru.arc.util.TextUtil
import java.util.Locale

object MountModule : PluginModule {
    override val name = "Mounts"
    override val priority = 82

    @Volatile private var config: MountModuleConfig? = null
    @Volatile private var catalog: MountCatalog? = null
    private var ownership: MountOwnership? = null
    private var purchases: MountPurchaseCoordinator? = null
    private var sessions: MountSessionController? = null
    private var gui: MountGuiController? = null

    override fun init() {
        bindUnavailableCommands()
        start(MountModuleConfig.load(ARC.instance.dataPath))
    }

    override fun reload() {
        shutdownRuntime()
        start(MountModuleConfig.load(ARC.instance.dataPath))
    }

    override fun shutdown() {
        shutdownRuntime()
        config = null
        catalog = null
    }

    private fun start(loadedConfig: MountModuleConfig) {
        config = loadedConfig
        if (!loadedConfig.enabled) {
            info("Mounts module disabled by configuration")
            return
        }
        if (!loadedConfig.ownershipMigrationComplete) {
            warn("Mounts module inactive until the ownership permission migration is complete")
            return
        }

        val loadedCatalog = loadedConfig.catalog()
        validatePaperTypes(loadedCatalog)
        val luckPerms = ARC.instance.server.servicesManager.load(LuckPerms::class.java)
        if (luckPerms == null) {
            warn("Mounts module disabled because LuckPerms API is unavailable")
            return
        }

        val loadedOwnership = LuckPermsMountOwnership(luckPerms)
        val wallet = VaultMountWallet(ARC.instance.server, EconomyModule.getEconomy())
        val controller =
            MountSessionController(
                plugin = ARC.instance,
                scheduler = Tasks.scheduler,
                configProvider = ::requiredConfig,
                message = { player, path, fallback -> player.sendMessage(TextUtil.mm(requiredConfig().message(path, fallback), true)) },
            )
        val coordinator =
            MountPurchaseCoordinator(
                ownership = loadedOwnership,
                wallet = wallet,
                runSync = { task -> Tasks.scheduler.runSync(Runnable(task)) },
            )
        val guiController =
            MountGuiController(
                plugin = ARC.instance,
                configProvider = ::requiredConfig,
                catalogProvider = ::requiredCatalog,
                ownership = loadedOwnership,
                wallet = wallet,
                purchases = coordinator,
                sessions = controller,
            )

        catalog = loadedCatalog
        ownership = loadedOwnership
        purchases = coordinator
        sessions = controller
        gui = guiController
        try {
            controller.start()
            guiController.start()
            bindCommands(guiController, loadedOwnership, controller)
        } catch (failure: Throwable) {
            shutdownRuntime()
            throw failure
        }
        info("Mounts module initialized with {} mount(s)", loadedCatalog.all.size)
    }

    private fun shutdownRuntime() {
        bindUnavailableCommands()
        gui?.shutdown()
        gui = null
        sessions?.shutdown()
        sessions = null
        purchases?.clear()
        purchases = null
        ownership = null
        catalog = null
    }

    private fun bindCommands(
        guiController: MountGuiController,
        loadedOwnership: MountOwnership,
        controller: MountSessionController,
    ) {
        val mountsCommand = MountsCommand(guiController)
        val unlockCommand = UnlockMountCommand(::requiredCatalog, loadedOwnership, Tasks.scheduler)
        val rideCommand = RideMobCommand(::requiredConfig, ::requiredCatalog, controller)
        ARC.instance.getCommand("mounts")?.apply {
            setExecutor(mountsCommand)
            tabCompleter = mountsCommand
        } ?: warn("Mounts command is missing from plugin.yml")
        ARC.instance.getCommand("unlock-mount")?.apply {
            setExecutor(unlockCommand)
            tabCompleter = unlockCommand
        } ?: warn("Unlock-mount command is missing from plugin.yml")
        ARC.instance.getCommand("ride-mob")?.apply {
            setExecutor(rideCommand)
            tabCompleter = rideCommand
        } ?: warn("Ride-mob command is missing from plugin.yml")
    }

    private fun validatePaperTypes(loadedCatalog: MountCatalog) {
        loadedCatalog.all.forEach { definition ->
            require(Material.matchMaterial(definition.iconMaterial) != null) {
                "Mount '${definition.id}' has unknown material '${definition.iconMaterial}'"
            }
            val entityType = runCatching { EntityType.valueOf(definition.entityType.uppercase(Locale.ROOT)) }.getOrNull()
            require(entityType != null && entityType.isAlive && entityType.isSpawnable) {
                "Mount '${definition.id}' has invalid entity type '${definition.entityType}'"
            }
        }
    }

    private fun bindUnavailableCommands() {
        listOf("mounts", "unlock-mount", "ride-mob").forEach { name ->
            ARC.instance.getCommand(name)?.apply {
                setExecutor(UnavailableMountCommand)
                tabCompleter = UnavailableMountCommand
            }
        }
    }

    private fun requiredConfig(): MountModuleConfig = checkNotNull(config) { "Mounts config is unavailable" }
    private fun requiredCatalog(): MountCatalog = checkNotNull(catalog) { "Mounts catalog is unavailable" }
}

private object UnavailableMountCommand : TabExecutor {
    override fun onCommand(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<out String>,
    ): Boolean {
        sender.sendMessage(TextUtil.mm("<red>Маунты сейчас недоступны.", true))
        return true
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>,
    ): List<String> = emptyList()
}
