package ru.arc.hooks.elitemobs.arena

import com.magmaguy.elitemobs.api.EliteMobSpawnEvent
import com.magmaguy.elitemobs.api.NPCEntitySpawnEvent
import com.magmaguy.elitemobs.api.PlayerJoinArenaEvent
import com.magmaguy.elitemobs.api.instanced.MatchDestroyEvent
import com.magmaguy.elitemobs.api.instanced.MatchLeaveEvent
import com.magmaguy.elitemobs.commands.ArenaCommand
import com.magmaguy.elitemobs.config.custombosses.CustomBossesConfig
import com.magmaguy.elitemobs.config.npcs.NPCsConfig
import com.magmaguy.elitemobs.config.npcs.NPCsConfigFields
import com.magmaguy.elitemobs.entitytracker.EntityTracker
import com.magmaguy.elitemobs.instanced.MatchInstance.InstancedRegionState
import com.magmaguy.elitemobs.instanced.arena.ArenaInstance
import com.magmaguy.elitemobs.mobconstructor.MobLevelPlaceholderFormatter
import com.magmaguy.elitemobs.mobconstructor.custombosses.CustomBossEntity
import com.magmaguy.elitemobs.npcs.NPCInteractions.NPCInteractionType
import com.magmaguy.elitemobs.magmacore.command.AdvancedCommand
import com.magmaguy.elitemobs.magmacore.command.CommandData
import com.magmaguy.elitemobs.magmacore.command.CommandManager
import com.magmaguy.elitemobs.magmacore.command.SenderType
import com.magmaguy.elitemobs.magmacore.command.arguments.ListStringCommandArgument
import com.magmaguy.elitemobs.magmacore.util.ChatColorConverter
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.entity.EnderDragon
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.plugin.java.JavaPlugin
import ru.arc.config.Config
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TaskScheduler
import ru.arc.core.Tasks
import java.util.IdentityHashMap
import java.util.UUID

/** Adds a level picker to stock arenas; EliteMobs still owns admission, waves and rewards. */
class NativeArenaLevels(
    private val plugin: JavaPlugin,
    private val config: Config,
    scheduler: TaskScheduler = Tasks.scheduler,
) : Listener, AutoCloseable {
    private val tasks = LifecycleTaskScope(scheduler)
    private val menus = NativeArenaMenu(plugin, config, scheduler, this)
    private val lobbies = IdentityHashMap<ArenaInstance, ArenaLevelLobby>()
    private val contextKey = NamespacedKey(plugin, "arena_level")
    private val disguises = DisguiseNames(plugin)
    private val npcRoutes = IdentityHashMap<NPCsConfigFields, NpcRoute>()
    private var commandManager: CommandManager? = null
    private var nativeCommand: AdvancedCommand? = null
    private val playerCommand = PickerCommand("arena", "elitemobs.arena.start")
    private val npcCommand = PickerCommand("arcarenalevels", "")
    private var installed = false

    fun install() {
        check(!installed) { "Arena levels are already installed" }
        bindRoutes()
        plugin.server.pluginManager.registerEvents(this, plugin)
        installed = true
        tasks.runTimer(20, 20) { menus.refresh() }
    }

    /** Replaces menu configuration, leaving occupied native matches and their level snapshots intact. */
    fun reload() {
        menus.reload()
        lobbies.entries.removeIf { (arena, lobby) -> !lobby.locked && arena.players.isEmpty() }
    }

    internal fun lobby(arena: ArenaInstance): ArenaLevelLobby = lobbies.getOrPut(arena) {
        var plan = ArenaLevelPlan.inspect(arena, config.int("maximum-enemy-level", 200).coerceIn(1, 200))
        if (plan.supported && !disguises.available && plan.bosses.keys.any {
                !CustomBossesConfig.getCustomBoss(it)?.disguise.isNullOrBlank()
            }) {
            plan = plan.copy(unsupported = "LibsDisguises public name API is unavailable")
        }
        if (!plan.supported) plugin.logger.warning("Arena ${arena.customArenasConfigFields.filename}: original difficulty only (${plan.unsupported})")
        ArenaLevelLobby(plan).also { if (arena.players.isNotEmpty()) it.observePlayer() }
    }

    internal fun join(arena: ArenaInstance, player: Player, level: Int): Boolean =
        lobby(arena).admit(level) { arena.addNewPlayer(player) }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinArenaEvent) {
        val player = event.player ?: return
        if (!event.arenaInstance.isSpectator(player)) lobby(event.arenaInstance).observePlayer()
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onLeave(event: MatchLeaveEvent) {
        val arena = event.instance as? ArenaInstance ?: return
        if (arena.players.isEmpty()) lobbies[arena]?.reset()
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onDestroy(event: MatchDestroyEvent) {
        val arena = event.instance as? ArenaInstance ?: return
        lobbies[arena]?.reset()
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onSpawn(event: EliteMobSpawnEvent) {
        val boss = event.eliteMobEntity as? CustomBossEntity ?: return
        val existing = boss.getCustomData(contextKey) as? MobScope
        val scope = existing ?: childScope(boss) ?: rootScope(boss) ?: return
        if (!scope.current()) return
        val body = boss.livingEntity ?: return
        if (scope.body == body.uniqueId) return
        // A phase reuses the actor but creates a fresh body and resets its configured level.
        val inherited = existing == null && scope.inherited
        val authored = boss.customBossesConfigFields.level
        val target = if (inherited) boss.level else scope.lobby.plan.level(authored, scope.offset)
        boss.addCustomData(contextKey, scope)
        scope.body = body.uniqueId
        applyLevel(boss, target, disguises::refresh)
    }

    private fun childScope(boss: CustomBossEntity): MobScope? {
        val parent = boss.summoningEntity as? CustomBossEntity ?: return null
        val parentScope = parent.getCustomData(contextKey) as? MobScope ?: return null
        if (!parentScope.current()) return null
        val definition = parentScope.lobby.plan.bosses[parent.customBossesConfigFields.filename] ?: return null
        val inherit = definition.summons[boss.customBossesConfigFields.filename] ?: return null
        return MobScope(parentScope.arena, parentScope.lobby, parentScope.generation, parentScope.offset, inherit)
    }

    private fun rootScope(boss: CustomBossEntity): MobScope? {
        if (boss.summoningEntity != null) return null
        val filename = boss.customBossesConfigFields.filename
        val position = boss.spawnLocation ?: return null
        val matches = lobbies.entries.filter { (arena, lobby) ->
            lobby.locked && lobby.selected > lobby.plan.baseline && arena.state == InstancedRegionState.ONGOING &&
                lobby.plan.waves[arena.currentWave].orEmpty().any {
                    it.filename == filename && samePoint(position, arena.container.spawnPoint(it.spawnPoint))
                }
        }
        // Shared geometry can have only one active native run; fail closed if another owner violates it.
        if (matches.size != 1) return null
        val (arena, lobby) = matches.single()
        return MobScope(arena, lobby, lobby.generation, lobby.selected - lobby.plan.baseline, false)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onNpcSpawn(event: NPCEntitySpawnEvent) {
        routeNpc(event.npcEntity.npCsConfigFields)
    }

    private fun bindRoutes() {
        restoreRoutes()
        val native = Bukkit.getPluginManager().getPlugin("EliteMobs") as? JavaPlugin
            ?: error("EliteMobs is not available")
        val manager = native.getCommand("elitemobs")?.executor as? CommandManager
            ?: error("Stock EliteMobs command registry is not available")
        val index = manager.commands.indexOfFirst { it.javaClass == ArenaCommand::class.java }
        check(index >= 0) { "Stock EliteMobs arena command is not available" }
        nativeCommand = manager.commands[index]
        commandManager = manager
        manager.commands[index] = playerCommand
        manager.commands.add(npcCommand)
        NPCsConfig.getNpcEntities().values.forEach(::routeNpc)
        EntityTracker.getNpcEntities().values.forEach { routeNpc(it.npCsConfigFields) }
    }

    private fun routeNpc(fields: NPCsConfigFields) {
        if (fields.interactionType != NPCInteractionType.ARENA_MASTER) return
        val arena = fields.arenaFilename ?: return
        val route = "em arcarenalevels $arena"
        npcRoutes[fields] = NpcRoute(fields.interactionType, fields.command, route)
        fields.interactionType = NPCInteractionType.COMMAND
        fields.command = route
    }

    private fun restoreRoutes() {
        commandManager?.let { manager ->
            val index = manager.commands.indexOfFirst { it === playerCommand }
            if (index >= 0) nativeCommand?.let { manager.commands[index] = it }
            manager.commands.removeIf { it === npcCommand }
        }
        npcRoutes.forEach { (fields, original) ->
            if (fields.interactionType == NPCInteractionType.COMMAND && fields.command == original.installedCommand) {
                fields.interactionType = original.type
                fields.command = original.command
            }
        }
        npcRoutes.clear()
        commandManager = null
        nativeCommand = null
    }

    override fun close() {
        tasks.close()
        menus.close()
        HandlerList.unregisterAll(this)
        restoreRoutes()
        lobbies.values.forEach { it.reset() }
        lobbies.clear()
        installed = false
    }

    private inner class PickerCommand(name: String, requiredPermission: String) : AdvancedCommand(listOf(name)) {
        init {
            addArgument("arenaID", ListStringCommandArgument("Arena ID"))
            usage = "/em $name <arenaID>"
            permission = requiredPermission
            description = "Open the Arena menu."
            senderType = SenderType.PLAYER
        }

        override fun execute(data: CommandData) {
            menus.open(data.playerSender, data.getStringArgument("arenaID"))
        }
    }

    private data class NpcRoute(val type: NPCInteractionType, val command: String?, val installedCommand: String)

    private class MobScope(
        val arena: ArenaInstance,
        val lobby: ArenaLevelLobby,
        val generation: Long,
        val offset: Int,
        val inherited: Boolean,
        var body: UUID? = null,
    ) {
        fun current() = lobby.generation == generation && lobby.locked && arena.state == InstancedRegionState.ONGOING
    }

    internal companion object {
        fun samePoint(actual: Location, authored: Location?): Boolean = authored != null &&
            actual.world == authored.world && actual.x == authored.x && actual.y == authored.y && actual.z == authored.z

        /** Spawn notification is before native scripts and the phase health-fraction restoration. */
        fun applyLevel(boss: CustomBossEntity, level: Int, disguiseName: (LivingEntity, String) -> Unit = { _, _ -> }) {
            boss.level = level
            if (boss.isNormalizedCombat) boss.setNormalizedMaxHealth() else boss.setMaxHealth()
            boss.health = boss.maxHealth
            val template = boss.customBossesConfigFields.name ?: return
            val name = ChatColorConverter.convert(
                MobLevelPlaceholderFormatter.replaceLevelPlaceholders(template, boss, level),
            )
            // CustomBossEntity also forwards this public call to its current custom model.
            boss.setName(name, true)
            val body = boss.livingEntity
            if (body != null) disguiseName(body, name)
            (body as? EnderDragon)?.bossBar?.setTitle(name)
        }
    }
}

/** Two optional public methods, resolved once; the retired dependency cannot be fetched reproducibly. */
private class DisguiseNames(private val plugin: JavaPlugin) {
    private val provider = Bukkit.getPluginManager().getPlugin("LibsDisguises")?.takeIf { it.isEnabled }
    private val methods = provider?.let {
        try {
            val loader = it.javaClass.classLoader
            val api = Class.forName("me.libraryaddict.disguise.DisguiseAPI", false, loader)
            val disguise = Class.forName("me.libraryaddict.disguise.disguisetypes.Disguise", false, loader)
            api.getMethod("getDisguise", Entity::class.java) to disguise.getMethod("setDisguiseName", String::class.java)
        } catch (failure: ReflectiveOperationException) {
            plugin.logger.warning("Arena disguise name support is unavailable: ${failure.message}")
            null
        } catch (failure: LinkageError) {
            plugin.logger.warning("Arena disguise name support is unavailable: ${failure.message}")
            null
        }
    }
    val available: Boolean get() = provider == null || methods != null
    private var failureLogged = false

    fun refresh(body: LivingEntity, name: String) {
        val (get, set) = methods ?: return
        try {
            get.invoke(null, body)?.let { set.invoke(it, name) }
        } catch (failure: ReflectiveOperationException) {
            if (!failureLogged) {
                failureLogged = true
                plugin.logger.warning("Could not update an arena disguise name: ${failure.message}")
            }
        }
    }
}
