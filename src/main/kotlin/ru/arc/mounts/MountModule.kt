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
import ru.arc.metrics.MetricsModule
import ru.arc.hooks.HookRegistry
import ru.arc.metrics.core.MetricPoint
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
    private var journal: MountPurchaseJournal? = null
    private var purchases: MountPurchaseCoordinator? = null
    private var sessions: MountSessionController? = null
    private var quickSummons: MountQuickSummonController? = null
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
        if (loadedConfig.hideFlyingMountFromRider && HookRegistry.packetEventsHook == null) {
            warn("Rider-only mount visibility is unavailable because PacketEvents is not active")
        }

        val loadedCatalog = loadedConfig.catalog()
        validatePaperTypes(loadedCatalog)
        val luckPerms = ARC.instance.server.servicesManager.load(LuckPerms::class.java)
        if (luckPerms == null) {
            warn("Mounts module disabled because LuckPerms API is unavailable")
            return
        }

        val loadedOwnership = LuckPermsMountOwnership(luckPerms)
        val wallet = RedisEconomyMountWallet()
        val journal = FileMountPurchaseJournal(ARC.instance.dataPath.resolve("data").resolve("mount-purchases.json"))
        this.journal = journal
        val controller =
            MountSessionController(
                plugin = ARC.instance,
                scheduler = Tasks.scheduler,
                configProvider = ::requiredConfig,
                allowedMountIds = loadedCatalog.all.mapTo(hashSetOf(), MountDefinition::id),
                message = { player, path, fallback -> player.sendMessage(TextUtil.mm(requiredConfig().message(path, fallback), true)) },
                onStateChanged = ::publishMetrics,
                setRiderMountHidden = { player, entity, hidden ->
                    HookRegistry.packetEventsHook?.setEntityInvisibleFor(entity, player, hidden)
                },
            )
        val coordinator =
            MountPurchaseCoordinator(
                ownership = loadedOwnership,
                wallet = wallet,
                journal = journal,
                purchasesEnabled = { requiredConfig().purchasesEnabled },
                runSync = { task -> Tasks.scheduler.runSync(Runnable(task)) },
                onStateChanged = ::publishMetrics,
            )
        val summonService =
            MountSummonService(
                configProvider = ::requiredConfig,
                catalogProvider = ::requiredCatalog,
                ownership = loadedOwnership,
                sessions = controller,
            )
        val quickSummonController =
            MountQuickSummonController(
                plugin = ARC.instance,
                configProvider = ::requiredConfig,
                summons = summonService,
            )
        coordinator.recover(loadedCatalog) { record ->
            warn("Mount purchase {} requires manual review at stage {}", record.transactionId, record.status.name.lowercase(Locale.ROOT))
        }
        val guiController =
            MountGuiController(
                plugin = ARC.instance,
                configProvider = ::requiredConfig,
                catalogProvider = ::requiredCatalog,
                ownership = loadedOwnership,
                wallet = wallet,
                purchases = coordinator,
                summons = summonService,
                quickSummons = quickSummonController,
            )

        catalog = loadedCatalog
        ownership = loadedOwnership
        purchases = coordinator
        sessions = controller
        quickSummons = quickSummonController
        gui = guiController
        try {
            controller.start()
            quickSummonController.start()
            guiController.start()
            bindCommands(guiController, loadedOwnership, controller)
        } catch (failure: Throwable) {
            shutdownRuntime()
            throw failure
        }
        publishMetrics()
        info("Mounts module initialized with {} mount(s)", loadedCatalog.all.size)
    }

    private fun shutdownRuntime() {
        bindUnavailableCommands()
        gui?.shutdown()
        gui = null
        quickSummons?.shutdown()
        quickSummons = null
        sessions?.shutdown()
        sessions = null
        purchases?.clear()
        purchases = null
        journal = null
        ownership = null
        catalog = null
        publishMetrics()
    }

    private fun bindCommands(
        guiController: MountGuiController,
        loadedOwnership: MountOwnership,
        controller: MountSessionController,
    ) {
        val mountCommand =
            MountCommand(
                config = ::requiredConfig,
                catalog = ::requiredCatalog,
                ownership = loadedOwnership,
                sessions = controller,
                scheduler = Tasks.scheduler,
                openMenu = guiController::openList,
            )
        ARC.instance.getCommand("mount")?.apply {
            setExecutor(mountCommand)
            tabCompleter = mountCommand
        } ?: warn("Mount command is missing from plugin.yml")
    }

    internal fun validatePaperTypes(loadedCatalog: MountCatalog) {
        loadedCatalog.all.forEach { definition ->
            require(Material.matchMaterial(definition.iconMaterial) != null) {
                "Mount '${definition.id}' has unknown material '${definition.iconMaterial}'"
            }
            val entityType = runCatching { EntityType.valueOf(definition.entityType.uppercase(Locale.ROOT)) }.getOrNull()
            require(entityType != null && entityType.isAlive && entityType.isSpawnable) {
                "Mount '${definition.id}' has invalid entity type '${definition.entityType}'"
            }
            MountAppearanceApplicator.validate(entityType, definition.appearance, "Mount '${definition.id}' appearance")
            definition.levels.forEach { level -> level.price?.toExactMinor() }
            definition.glowPrice?.toExactMinor()
            definition.abilities.upgrades.forEach { ability ->
                require(Material.matchMaterial(ability.iconMaterial) != null) {
                    "Mount '${definition.id}' ability '${ability.id}' has unknown material '${ability.iconMaterial}'"
                }
                ability.price.toExactMinor()
            }
            definition.skins.forEach { skin ->
                require(Material.matchMaterial(skin.iconMaterial) != null) {
                    "Mount '${definition.id}' skin '${skin.id}' has unknown material '${skin.iconMaterial}'"
                }
                skin.price?.toExactMinor()
                MountAppearanceApplicator.validate(entityType, skin.appearance, "Mount '${definition.id}' skin '${skin.id}'")
                skin.trail?.let { trail ->
                    val particle = runCatching { org.bukkit.Particle.valueOf(trail.particle) }.getOrNull()
                    require(particle != null && particle.dataType == Void::class.java) {
                        "Mount '${definition.id}' skin '${skin.id}' has unsupported particle '${trail.particle}'"
                    }
                }
            }
        }
    }

    private fun bindUnavailableCommands() {
        ARC.instance.getCommand("mount")?.apply {
            setExecutor(UnavailableMountCommand)
            tabCompleter = UnavailableMountCommand
        }
    }

    internal fun publishMetrics() {
        val loadedConfig = config
        val loadedCatalog = catalog
        val records = journal?.records().orEmpty()
        MetricsModule.recordSnapshot("mounts", "gameplay-mounts") {
            buildList {
                add(MetricPoint("arc_mounts_enabled", "Whether native ARC mounts are enabled", if (loadedConfig?.enabled == true) 1.0 else 0.0))
                add(MetricPoint("arc_mounts_purchases_enabled", "Whether mount purchases are enabled on this node", if (loadedConfig?.purchasesEnabled == true) 1.0 else 0.0))
                add(MetricPoint("arc_mounts_catalog_entries", "Configured native ARC mounts", (loadedCatalog?.all?.size ?: 0).toDouble()))
                add(MetricPoint("arc_mounts_active_sessions", "Active native ARC mount sessions", (sessions?.activeSessionCount() ?: 0).toDouble()))
                add(
                    MetricPoint(
                        "arc_mount_purchase_journal_unresolved",
                        "Unresolved native ARC mount purchases",
                        records.count { !it.status.terminal || it.status == MountPurchaseJournalStatus.MANUAL_REVIEW }.toDouble(),
                    ),
                )
                MountPurchaseJournalStatus.entries.forEach { status ->
                    add(
                        MetricPoint(
                            "arc_mount_purchase_journal_records",
                            "Native ARC mount purchase journal records by status",
                            records.count { it.status == status }.toDouble(),
                            mapOf("status" to status.name.lowercase(Locale.ROOT)),
                        ),
                    )
                }
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
