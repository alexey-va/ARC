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
import ru.arc.core.PluginModule
import ru.arc.core.whenCompleteSync
import ru.arc.gui.ArcMenus
import ru.arc.util.Logging.info
import ru.arc.util.Logging.warn
import ru.arc.xserver.XActionManager
import java.util.UUID

object GravesModule : PluginModule {
    override val name = "Graves"
    override val priority = 85
    private var runtime: GravesRuntime? = null
    override fun init() {
        val settings = GravesConfig.load(ARC.instance.dataPath).snapshot()
        runtime = GravesRuntime(settings).also(GravesRuntime::start)
    }
    override fun reload() { shutdown(); init() }
    override fun shutdown() { runtime?.close(); runtime = null }
    fun activeCount(player: Player): Int = runtime?.views(player)?.size ?: 0
    fun open(player: Player): Boolean = runtime?.let { it.menu.open(player); true } ?: false
}

/** All Bukkit and domain state is confined to the owning Paper thread. */
internal class GravesRuntime(private val settings: GravesSettings) : Listener, AutoCloseable {
    private val plugin = ARC.instance
    private val server = requireNotNull(ARC.serverName) { "Graves requires the configured network server name" }
    private val tasks = LifecycleTaskScope()
    private val native = if (plugin.server.pluginManager.isPluginEnabled("GravesX")) GravesXAccess.find(plugin.server, server) else null
    private val redis = ARC.redisManager
    private val network = redis?.let { GravesNetwork(it, server, ::acceptTransfer) }
    private val preferenceStore = redis?.let { GraveTrackingStore(it, settings.trackingEnabledByDefault) }
    private val preferences = mutableMapOf<UUID, GraveTracking>()
    private val preferenceGenerations = mutableMapOf<UUID, Long>()
    private val preferenceWrites = mutableMapOf<UUID, Long>()
    private var nextPreferenceGeneration = 0L
    private val hud = GravesHud(plugin, server, settings)
    internal val payments = GravePayments(plugin.dataPath, ::feedback)
    private var localViews = emptyList<GraveView>()
    private var networkRefreshPending = false
    private var snapshotFailed = false
    private var networkFailed = false
    private var closed = false
    private val transfers = mutableMapOf<UUID, Transfer>()
    private val outgoing = mutableSetOf<UUID>()
    private val notices = mutableMapOf<UUID, Set<UUID>>()
    private var nativeListener: GravesNativeListener? = null
    private var syncListener: Listener? = null
    private val waitsForInventorySync = plugin.server.pluginManager.isPluginEnabled("HuskSync")
    private data class Transfer(val grave: UUID, val price: Double, val expires: Long)

    internal val menu = GravesMenu(
        settings, ::views, { selected(it)?.id }, { preference(it).enabled },
        { player -> updatePreference(player) { it.copy(enabled = !it.enabled) } },
        { player, grave -> updatePreference(player) { it.copy(enabled = true, selected = grave) } },
        ::price, ::teleport,
    )

    fun start() {
        Bukkit.getPluginManager().registerEvents(this, plugin)
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
        info("Graves integration initialized on {}: local={}, network={}", server, native != null, network != null)
    }

    fun views(player: Player): List<GraveView> {
        val now = System.currentTimeMillis()
        return (localViews.filter { it.ownerId == player.uniqueId } + network?.views(player.uniqueId).orEmpty())
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
        Bukkit.getOnlinePlayers().forEach { player ->
            val graves = views(player)
            hud.refreshGlow(player, graves)
            if (!player.isDead) {
                val ids = graves.mapTo(hashSetOf()) { it.id }
                val old = notices.put(player.uniqueId, ids).orEmpty()
                if ((ids - old).isNotEmpty()) player.sendMessage(settings.text("created"))
            }
        }
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
    internal fun validLocal(player: Player, grave: UUID): Boolean = native?.canTeleport(player, grave) == true
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
            check(native?.teleport(player, grave) == true) { "GravesX declined the prepared teleport" }
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

    private fun afterInventorySync(player: UUID) { tasks.runSync { finishTransfer(player) } }

    private fun finishTransfer(playerId: UUID) {
        val player = Bukkit.getPlayer(playerId)?.takeIf { it.isOnline } ?: return
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

    override fun close() {
        closed = true
        HandlerList.unregisterAll(this)
        nativeListener?.let(HandlerList::unregisterAll)
        syncListener?.let(HandlerList::unregisterAll)
        tasks.close()
        payments.close()
        network?.close()
        hud.close()
    }
}
