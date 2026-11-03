package ru.arc.graves

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import ru.arc.ARC
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.Tasks
import ru.arc.audit.AuditConfig
import ru.arc.core.PluginModule
import ru.arc.core.whenCompleteSync
import ru.arc.gui.ArcMenus
import ru.arc.util.Logging.info
import ru.arc.util.Logging.warn
import ru.arc.xserver.XActionManager
import ru.arc.paper.teleport.NativePaperTeleportExecutor
import ru.arc.sql.SqlConnectionConfig
import java.util.UUID
import java.util.concurrent.CompletableFuture

object GravesModule : PluginModule {
    override val name = "Graves"
    override val priority = 85
    private var runtime: GravesRuntime? = null
    private var generation = 0L
    private var drain = CompletableFuture.completedFuture<Void>(null)
    private var storageDrain = CompletableFuture.completedFuture<Void>(null)
    override fun init() {
        val current = ++generation
        ARC.instance.whenCompleteOnMain(drain) { _, failure ->
            if (current != generation) return@whenCompleteOnMain
            if (failure != null) { warn("Graves predecessor cleanup failed", failure); return@whenCompleteOnMain }
            val sql = AuditConfig.load().mysql
            if (sql == null) {
                warn("Graves module unavailable: shared ARC MySQL settings are not configured")
                return@whenCompleteOnMain
            }
            val settings = GravesConfig.load(ARC.instance.dataPath).snapshot()
            runtime = GravesRuntime(settings, sql).also(GravesRuntime::start)
        }
    }
    override fun reload() { shutdown(); init() }
    override fun shutdown() { ++generation; runtime?.let { drain = it.closeAsync(); storageDrain = it.storageDrained() }; runtime = null }
    fun drained(): CompletableFuture<Void> = drain.copy()
    fun storageDrained(): CompletableFuture<Void> = storageDrain.copy()
    fun activeCount(player: Player): Int = runtime?.views(player)?.size ?: 0
    fun open(player: Player): Boolean = runtime?.let { it.menu.open(player); true } ?: false
}

/** All Bukkit and domain state is confined to the owning Paper thread. */
internal class GravesRuntime(private val settings: GravesSettings, sql: SqlConnectionConfig) : Listener, AutoCloseable {
    private val plugin = ARC.instance
    private val server = requireNotNull(ARC.serverName) { "Graves requires the configured network server name" }
    private val tasks = LifecycleTaskScope()
    private val native = if (plugin.server.pluginManager.isPluginEnabled("GravesX")) GravesXAccess.find(plugin.server, server) else null
    private val graveStore = plugin.submitBackground { MySqlNativeGraveRepository.open(sql) }
    private val archive = LegacyGravesArchive(plugin.dataPath, server, native, graveStore.thenApply { it }, plugin.backgroundOwner())
    private val restoration = archive.activateSealed()
    private val playerPersistence = if (plugin.server.pluginManager.isPluginEnabled("HuskSync")) HuskSyncGravePersistence(plugin.backgroundOwner()) else LocalGravePersistence
    private val owned = NativeGraves(server, settings, ::feedback, ::refreshPlayers, plugin,
        graveStore.thenApply { it },
        playerPersistence,
        Tasks.scheduler, restoration)
    private val legacyHolograms = LegacyGravesHolograms(plugin)
    private val redis = ARC.redisManager
    private val network = redis?.let { GravesNetwork(it, server, ::acceptTransfer) }
    private val preferenceStore = redis?.let { GraveTrackingStore(it, settings.trackingEnabledByDefault) }
    private val preferences = mutableMapOf<UUID, GraveTracking>()
    private val preferenceGenerations = mutableMapOf<UUID, Long>()
    private val preferenceWrites = mutableMapOf<UUID, Long>()
    private var nextPreferenceGeneration = 0L
    private val barriers = GraveBarriers(plugin, server, settings.nearbyGlowRadius, ::currentMarker,
        owned::playerReady, ::openMarker, ::feedback)
    private val hud = GravesHud(plugin, server, settings, onClear = barriers::clear)
    internal val payments = GravePayments(plugin.dataPath, ::feedback)
    private var localViews = emptyList<GraveView>()
    private var networkRefreshPending = false
    private var snapshotFailed = false
    private var networkFailed = false
    private var closed = false
    private var storageDrain = CompletableFuture.completedFuture<Void>(null)
    private var drain: CompletableFuture<Void>? = null
    private val transfers = mutableMapOf<UUID, Transfer>()
    private val outgoing = mutableSetOf<UUID>()
    private val notices = mutableMapOf<UUID, Set<UUID>>()
    private var nativeListener: GravesNativeListener? = null
    private var syncListener: Listener? = null
    private val waitsForInventorySync = plugin.server.pluginManager.isPluginEnabled("HuskSync")
    private data class Transfer(val grave: UUID, val price: Double, val expires: Long)

    internal val menu = GravesMenu(
        settings, { if (owned.ready && !snapshotFailed) views(it) else null }, { selected(it)?.id }, { preference(it).enabled },
        { player -> updatePreference(player) { it.copy(enabled = !it.enabled) } },
        { player, grave -> updatePreference(player) { it.copy(enabled = true, selected = grave) } },
        ::price, ::teleport,
        canClaim = owned::canClaim,
        claim = owned::claim,
    )

    fun start() {
        Bukkit.getPluginManager().registerEvents(this, plugin)
        owned.start()
        legacyHolograms.start()
        if (native != null) nativeListener = GravesNativeListener(this).also {
            Bukkit.getPluginManager().registerEvents(it, plugin)
        }
        if (waitsForInventorySync) syncListener = GravesSyncListener(::afterInventorySync).also {
            Bukkit.getPluginManager().registerEvents(it, plugin)
        }
        plugin.getCommand("graves")?.setExecutor { sender, _, _, _ ->
            if (sender is Player) openCommand(sender)
            true
        }
        refresh()
        Bukkit.getOnlinePlayers().forEach(::loadPreference)
        tasks.runTimer(40, 40, ::refresh)
        tasks.runTimer(1, settings.hudRefreshTicks.toLong()) {
            Bukkit.getOnlinePlayers().forEach { player ->
                val graves = views(player)
                hud.refresh(player, graves, selected(player, graves)?.id, preference(player).enabled)
            }
        }
        info("ARC graves initialized on {}: native=true, legacy={}, network={}", server, native != null, network != null)
    }

    fun views(player: Player): List<GraveView> {
        val now = System.currentTimeMillis()
        return (owned.views().filter { it.ownerId == player.uniqueId } + localViews.filter { it.ownerId == player.uniqueId } + network?.views(player.uniqueId).orEmpty())
            .filter { it.active(now) }.distinctBy { it.id }.sortedByDescending { it.createdAt }
    }

    private fun refresh() {
        if (closed) return
        try {
            localViews = native?.snapshot(server).orEmpty().filter { it.active(System.currentTimeMillis()) }
            if (snapshotFailed) info("Graves local snapshot recovered on {}", server)
            snapshotFailed = false
        } catch (failure: Throwable) {
            if (!snapshotFailed) warn("Graves snapshot failed on {}", server, failure)
            snapshotFailed = true
        }
        if (network != null && !networkRefreshPending && !snapshotFailed) {
            networkRefreshPending = true
            // Native grave discovery is SQL-backed; Redis carries only the legacy
            // projection and online lease, without duplicating complete native lists.
            network.publish(localViews).thenCompose { network.refresh() }.whenCompleteSync(tasks) { _, failure ->
                networkRefreshPending = false
                if (failure != null && !networkFailed) warn("Graves network refresh failed on {}", server, failure)
                if (failure == null && networkFailed) info("Graves network refresh recovered on {}", server)
                networkFailed = failure != null
                refreshPlayers()
            }
        }
        transfers.entries.removeIf { it.value.expires < System.currentTimeMillis() }
        refreshPlayers()
    }

    private fun refreshPlayers() {
        val publicGraves = (owned.views() + localViews).filter { it.server == server && it.active(System.currentTimeMillis()) }.distinctBy { it.id }
        Bukkit.getOnlinePlayers().forEach { player ->
            if (owned.playerReady(player.uniqueId)) finishTransfer(player.uniqueId)
            val graves = views(player)
            barriers.update(player, hud.refreshGlow(player, publicGraves))
            if (!player.isDead) {
                val ids = graves.mapTo(hashSetOf()) { it.id }
                val old = notices.put(player.uniqueId, ids).orEmpty()
                if ((ids - old).isNotEmpty()) player.sendMessage(settings.text("created"))
            }
        }
    }

    private fun currentMarker(view: GraveView): Boolean {
        if (closed || view.server != server || !view.active(System.currentTimeMillis())) return false
        val record = owned.find(view.id)
        return if (record != null) record.ownerId == view.ownerId && record.server == view.server && record.world == view.world &&
            record.x == view.x && record.y == view.y && record.z == view.z
        else localViews.any { it.id == view.id && it.ownerId == view.ownerId && it.server == view.server && it.world == view.world &&
            it.x == view.x && it.y == view.y && it.z == view.z && it.active(System.currentTimeMillis()) }
    }

    private fun openMarker(player: Player, grave: UUID) {
        if (closed || !owned.playerReady(player.uniqueId)) return
        ArcMenus.beginDialogFlow(player)
        menu.openDetail(player, grave)
    }

    private fun preference(player: Player): GraveTracking = preferences[player.uniqueId] ?: GraveTracking(settings.trackingEnabledByDefault)
    private fun selected(player: Player, graves: List<GraveView> = views(player)): GraveView? =
        graves.firstOrNull { it.id == preference(player).selected }
            ?: graves.firstOrNull { it.server == server && it.world == player.world.name }
            ?: graves.firstOrNull()

    private fun loadPreference(player: Player) {
        val generation = ++nextPreferenceGeneration
        preferenceGenerations[player.uniqueId] = generation
        preferenceStore?.load(player.uniqueId)?.whenCompleteSync(tasks) { value, failure ->
            if (player.isOnline && preferenceGenerations[player.uniqueId] == generation && failure == null && value != null) {
                preferences[player.uniqueId] = value
            }
        }
    }

    private fun updatePreference(player: Player, transform: (GraveTracking) -> GraveTracking) {
        if (player.uniqueId in preferenceWrites) return
        val previous = preference(player)
        val next = transform(previous)
        val generation = ++nextPreferenceGeneration
        preferenceWrites[player.uniqueId] = generation
        preferenceGenerations[player.uniqueId] = generation
        preferences[player.uniqueId] = next
        val store = preferenceStore
        if (store == null) {
            preferenceWrites.remove(player.uniqueId)
            return
        }
        store.update(player.uniqueId) { next }.whenCompleteSync(tasks) { value, failure ->
            if (preferenceWrites[player.uniqueId] != generation) return@whenCompleteSync
            preferenceWrites.remove(player.uniqueId)
            if (!player.isOnline || preferenceGenerations[player.uniqueId] != generation) return@whenCompleteSync
            if (failure == null && value != null) preferences[player.uniqueId] = value
            else {
                preferences[player.uniqueId] = previous
                warn("Grave tracking preference failed for {}", player.uniqueId, failure)
                if (player.isOnline) feedback(player, "tracking-save-failed")
            }
            // Do not reopen an abandoned dialog from an asynchronous storage callback.
        }
    }

    internal fun price(player: Player): Double = if (player.hasPermission(settings.freePermission)) 0.0 else settings.defaultFee
    internal fun validLocal(player: Player, grave: UUID): Boolean = !owned.blocked(player.uniqueId) &&
        (owned.find(grave)?.let { it.server == server && it.ownerId == player.uniqueId } == true || native?.canTeleport(player, grave) == true)
    internal fun feedback(player: Player, key: String) { if (player.isOnline) player.sendMessage(settings.text(key)) }

    private fun teleport(player: Player, view: GraveView, quote: Double) {
        if (!player.isOnline || view.ownerId != player.uniqueId || !view.active(System.currentTimeMillis()) || price(player) != quote ||
            views(player).none { it.id == view.id && it.server == view.server }) {
            feedback(player, "changed"); return
        }
        if (view.server == server) { teleportLocal(player, view.id, quote); return }
        val bridge = network
        if (bridge == null || view.server !in bridge.knownServers()) { feedback(player, "unavailable"); return }
        if (!outgoing.add(player.uniqueId)) { feedback(player, "payment-pending"); return }
        bridge.prepare(player.uniqueId, view.id, view.server, quote).whenCompleteSync(tasks) { accepted, failure ->
            if (!player.isOnline) { outgoing.remove(player.uniqueId); return@whenCompleteSync }
            if (failure != null || accepted != true || price(player) != quote) {
                outgoing.remove(player.uniqueId)
                feedback(player, "unavailable")
            } else {
                ArcMenus.closeDialog(player)
                player.sendMessage(settings.text("transfer", "server" to view.server))
                XActionManager.movePlayerToServer(player, view.server)
                tasks.runLater(20 * 30L) { outgoing.remove(player.uniqueId) }
            }
        }
    }

    private fun acceptTransfer(player: UUID, grave: UUID, quote: Double): Boolean {
        owned.find(grave)?.let { record ->
            if (closed || record.server != server || record.ownerId != player || owned.blocked(player) ||
                quote !in setOf(0.0, settings.defaultFee) || transfers.containsKey(player)) return false
            transfers[player] = Transfer(grave, quote, System.currentTimeMillis() + 30_000)
            return true
        }
        val live = native?.grave(grave) ?: return false
        if (closed || live.ownerUUID != player || live.timeAliveRemaining == 0L ||
            quote !in setOf(0.0, settings.defaultFee) || transfers.containsKey(player)) return false
        transfers[player] = Transfer(grave, quote, System.currentTimeMillis() + 30_000)
        return true
    }

    private fun teleportLocal(player: Player, grave: UUID, quote: Double) {
        if (!validLocal(player, grave)) { feedback(player, "changed"); return }
        payments.prepare(player, grave, quote, { validLocal(player, grave) && price(player) == quote }) {
            ArcMenus.closeDialog(player)
            owned.find(grave)?.let { record ->
                teleportOwned(player, record, quote)
                return@prepare
            }
            check(native?.teleport(player, grave) == true) { "GravesX declined the prepared teleport" }
        }
    }

    private fun teleportOwned(player: Player, record: NativeGraveRecord, quote: Double) {
        if (!payments.admitNative(player, record.id)) return
        val world = Bukkit.getWorld(record.world)
        if (world == null) { payments.complete(player, record.id, true); feedback(player, "unavailable"); return }
        world.getChunkAtAsync(record.x shr 4, record.z shr 4, true).whenCompleteSync(tasks) { _, failure ->
            val now = System.currentTimeMillis()
            val occupied = (owned.views() + localViews).asSequence()
                .filter { it.server == server && it.world == world.name && it.active(now) }
                .map { it.x to it.z }.toSet()
            val target = if (failure == null) GraveDestinations.visit(world, record.x, record.y, record.z, occupied) else null
            if (target == null || !validLocal(player, record.id) || price(player) != quote) {
                payments.complete(player, record.id, true); feedback(player, "teleport-failed"); return@whenCompleteSync
            }
            val origin = player.location.clone()
            NativePaperTeleportExecutor.teleportAsync(player, target).whenCompleteSync(tasks) { moved, problem ->
                if (problem != null || moved != true) { payments.complete(player, record.id, true); feedback(player, "teleport-failed"); return@whenCompleteSync }
                if (payments.charge(player, record.id, price(player), validLocal(player, record.id))) {
                    payments.complete(player, record.id, false)
                } else {
                    NativePaperTeleportExecutor.teleportAsync(player, origin).whenCompleteSync(tasks) { restored, rollbackFailure ->
                        payments.complete(player, record.id, true)
                        if (restored != true || rollbackFailure != null) warn("Grave unpaid teleport rollback failed player={} grave={}", player.uniqueId, record.id, rollbackFailure)
                    }
                }
            }
        }
    }

    private fun openCommand(player: Player) {
        ArcMenus.beginDialogFlow(player)
        menu.open(player)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun command(event: PlayerCommandPreprocessEvent) {
        val words = event.message.trim().removePrefix("/").split(Regex("\\s+"))
        val label = words.firstOrNull()?.lowercase() ?: return
        if (label !in setOf("graves", "grave", "gravesx", "graves:graves", "gravesx:graves", "arc:graves")) return
        if (words.size > 1 && (words.size != 2 || words[1].lowercase() !in setOf("list", "menu", "gui"))) return
        event.isCancelled = true
        openCommand(event.player)
    }

    @EventHandler fun join(event: PlayerJoinEvent) {
        loadPreference(event.player)
        if (!waitsForInventorySync) tasks.runLater(20) { finishTransfer(event.player.uniqueId) }
    }

    private fun afterInventorySync(player: UUID) { tasks.runSync { owned.synchronized(player); finishTransfer(player) } }

    private fun finishTransfer(playerId: UUID) {
        val player = Bukkit.getPlayer(playerId)?.takeIf { it.isOnline } ?: return
        if (!owned.playerReady(playerId)) return
        val transfer = transfers.remove(playerId) ?: return
        if (transfer.expires < System.currentTimeMillis()) { feedback(player, "changed"); return }
        refresh()
        teleportLocal(player, transfer.grave, transfer.price)
    }

    @EventHandler fun quit(event: PlayerQuitEvent) {
        hud.clear(event.player)
        preferences.remove(event.player.uniqueId)
        notices.remove(event.player.uniqueId)
        outgoing.remove(event.player.uniqueId)
        preferenceGenerations.remove(event.player.uniqueId)
        preferenceWrites.remove(event.player.uniqueId)
    }

    fun closeAsync(): CompletableFuture<Void> {
        drain?.let { return it }
        closed = true
        HandlerList.unregisterAll(this)
        nativeListener?.let(HandlerList::unregisterAll)
        syncListener?.let(HandlerList::unregisterAll)
        tasks.close()
        payments.close()
        network?.close()
        legacyHolograms.close()
        barriers.close()
        // Explicit stop/reload maintenance: these stages use SQL/file workers only,
        // never Paper callbacks. Finish before ARC seals its background admission.
        try { restoration.join(); archive.checkpointOnShutdown().join() }
        catch (failure: Throwable) { warn("Legacy grave handoff failed on {}; keep the original GravesX database and plugin", server, failure) }
        storageDrain = owned.closeAsync()
        return CompletableFuture.allOf(storageDrain, hud.closeAsync()).also { drain = it }
    }
    fun storageDrained(): CompletableFuture<Void> = storageDrain.copy()
    override fun close() { closeAsync() }
}
