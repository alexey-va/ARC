package ru.arc.graves

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryOpenEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.server.PluginEnableEvent
import ru.arc.ARC
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.Tasks
import ru.arc.audit.AuditConfig
import ru.arc.config.ConfigManager
import ru.arc.core.PluginModule
import ru.arc.core.whenCompleteSync
import ru.arc.gui.ArcMenus
import ru.arc.hooks.HookRegistry
import ru.arc.network.BackendServerId
import ru.arc.util.Logging.info
import ru.arc.util.Logging.warn
import ru.arc.util.WorldNames
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
    fun open(player: Player): Boolean = runtime?.let { it.openPersonal(player); true } ?: false
    fun decorativeCommand(player: Player, args: List<String>): Boolean = runtime?.let { it.decorative.command(player, args); true } ?: false
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
    private val epitaphs = GraveEpitaphs.load(plugin::getResource)
    private val owned = NativeGraves(server, settings, ::feedback, ::refreshPlayers, plugin,
        graveStore.thenApply { it },
        playerPersistence,
        Tasks.scheduler, restoration, { event, id ->
            GravePreferences.fromStored(HookRegistry.luckPermsHook?.getCachedMeta(event.entity.uniqueId, GravePreferences.META_KEY))
                .appearance { epitaphs.choose(id, GraveDeathKind.of(event)) }
        })
    private val legacyHolograms = LegacyGravesHolograms(plugin)
    private val redis = ARC.redisManager
    private val network = redis?.let { GravesNetwork(it, server, ::acceptTransfer) }
    private val adminRequests = GravesAdminRequests()
    private val worldNames: Map<String, String> = ConfigManager.of(plugin.dataPath, "modules/misc.yml").map("world-names")
    private val barriers = GraveBarriers(plugin, server, settings.nearbyGlowRadius, ::currentMarker,
        owned::playerReady, ::openMarker, ::feedback)
    private val hud: GravesHud = GravesHud(plugin, server, settings, onClear = barriers::clear, decorativeBarrier = ::decorativeBarrier)
    internal val decorative: DecorativeGraves = DecorativeGraves(plugin, settings, server,
        plugin.submitBackground { DecorativeGraveStore(plugin.dataPath) }, plugin.backgroundOwner(), epitaphs,
        { owned.views() + localViews }, ::refreshPlayers,
        ::decorativeAppearance, onHit = hud::decorativeHit, onBreak = hud::decorativeBreak)
    private fun decorativeBarrier(id: UUID, block: org.bukkit.block.Block): Boolean = decorative.hasBarrier(id, block)
    private fun decorativeAppearance(record: DecorativeGraveRecord): GraveAppearance =
        hud.appearance(record.view(server), requireNotNull(Bukkit.getWorld(record.worldId))) { block ->
            block.type.isAir || decorative.hasJournaledBarrier(block)
        }

    private var engravingsAvailable = false
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
    private var nativeListener: GravesNativeListener? = null
    private var syncListener: Listener? = null
    private var frameInteractions: GraveFrameInteractions? = null
    private var imageOwner: YamipaGraveEngravings? = null
    private var imageInitRunning = false
    private var imageRetryTicks = 40L
    private val waitsForInventorySync = plugin.server.pluginManager.isPluginEnabled("HuskSync")
    private data class Transfer(val grave: UUID, val price: Double, val expires: Long)

    private val menu = GravesMenu(settings, { if (owned.ready && !snapshotFailed) views(it) else null },
        ::price, ::teleport, { WorldNames.display(it, worldNames) }, { player, grave -> openContents(player, grave) })
    private val contentsMenu = GravesContentsMenu(settings, { WorldNames.display(it, worldNames) },
        ::canClaimContents, ::claimContents, ::requestContentsPage)
    private val adminMenu = GravesAdminMenu(settings, { WorldNames.display(it, worldNames) },
        ::openAdmin, { player, grave -> openAdminContents(player, grave) })

    fun start() {
        Bukkit.getPluginManager().registerEvents(this, plugin)
        Bukkit.getPluginManager().registerEvents(adminRequests, plugin)
        owned.start()
        decorative.start()
        legacyHolograms.start()
        if (native != null) nativeListener = GravesNativeListener(this).also {
            Bukkit.getPluginManager().registerEvents(it, plugin)
        }
        if (waitsForInventorySync) syncListener = GravesSyncListener(::afterInventorySync).also {
            Bukkit.getPluginManager().registerEvents(it, plugin)
        }
        plugin.getCommand("graves")?.setExecutor { sender, _, _, args ->
            if (sender is Player) openCommand(sender, args.toList())
            true
        }
        plugin.getCommand("graves")?.setTabCompleter { sender, _, _, args ->
            if (sender is Player && sender.hasPermission(GRAVES_ADMIN_PERMISSION) && args.size == 1 &&
                "admin".startsWith(args[0], ignoreCase = true)) listOf("admin") else emptyList()
        }
        refresh()
        tasks.runTimer(40, 40, ::refresh)
        initializeEngravings()
        info("ARC graves initialized on {}: native=true, legacy={}, network={}", server, native != null, network != null)
    }

    private fun initializeEngravings() {
        if (closed || imageInitRunning || imageOwner?.health()?.available == true) return
        val plugins = plugin.server.pluginManager
        if (!plugins.isPluginEnabled("YamipaPlugin") || !plugins.isPluginEnabled("packetevents")) return
        imageInitRunning = true
        plugin.submitBackground { GraveImageAssets.extract(plugin.dataPath, plugin::getResource) }
            .whenCompleteSync(tasks) { paths, failure ->
                imageInitRunning = false
                if (closed) return@whenCompleteSync
                if (failure != null || paths == null) {
                    warn("Could not prepare grave engraving assets on {}", server, failure)
                    return@whenCompleteSync
                }
                val engravings = YamipaGraveEngravings.create(plugin, paths)
                val health = engravings.health()
                if (!health.available) {
                    engravings.closeAsync()
                    // Yamipa's plugin-enable event can precede creation of its renderer.
                    if (health.detail?.contains("Cannot get renderer instance if plugin is not running") == true) {
                        val delay = imageRetryTicks
                        imageRetryTicks = (imageRetryTicks * 2).coerceAtMost(2400L)
                        tasks.runLater(delay) { initializeEngravings() }
                    }
                    return@whenCompleteSync
                }
                try {
                    frameInteractions?.close()
                    frameInteractions = GraveFrameInteractions(plugin, engravings::graveForFrame, barriers::interactFrame, decorative::strikeFrame)
                    hud.attachEngravings(engravings)
                    imageOwner = engravings
                    imageRetryTicks = 40L
                    refreshPlayers()
                } catch (error: Throwable) {
                    frameInteractions?.close()
                    frameInteractions = null
                    engravings.closeAsync()
                    warn("Could not enable grave engravings on {}", server, error)
                }
            }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun imageProviderEnabled(event: PluginEnableEvent) {
        if (event.plugin.name == "YamipaPlugin") {
            imageRetryTicks = 40L
            initializeEngravings()
        }
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
        val images = hud.engravingsAvailable()
        if (images != engravingsAvailable) {
            engravingsAvailable = images
            decorative.refreshBlocks()
        }
        val publicGraves = (owned.views() + localViews + decorative.views()).filter { it.server == server && it.active(System.currentTimeMillis()) }.distinctBy { it.id }
        Bukkit.getOnlinePlayers().forEach { player ->
            if (owned.playerReady(player.uniqueId)) finishTransfer(player.uniqueId)
            barriers.update(player, hud.refreshGlow(player, publicGraves))
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
        openContents(player, grave)
    }

    private fun openContents(player: Player, grave: UUID, page: Int = 0) {
        adminRequests.invalidate(player)
        if (closed || !player.isOnline || player.isDead || !owned.playerReady(player.uniqueId)) return
        val record = owned.find(grave)
        if (record == null) { feedback(player, "contents-unavailable"); return }
        if (record.ownerId != player.uniqueId) { feedback(player, "not-owner"); return }
        ArcMenus.closeDialog(player)
        contentsMenu.open(player, record, page = page)
    }

    private fun canClaimContents(player: Player, displayed: NativeGraveRecord): Boolean {
        if (closed || !owned.playerReady(player.uniqueId) || displayed.ownerId != player.uniqueId) return false
        val current = owned.find(displayed.id)?.takeIf { it.revision == displayed.revision && it.ownerId == player.uniqueId } ?: return false
        val view = owned.views().firstOrNull { it.id == current.id } ?: return false
        return owned.canClaim(player, view)
    }

    private fun claimContents(player: Player, grave: UUID, revision: Long) {
        val current = owned.find(grave)
        if (current == null || current.revision != revision) { feedback(player, "changed"); return }
        if (!canClaimContents(player, current)) { feedback(player, "claim-unavailable"); return }
        val view = owned.views().firstOrNull { it.id == grave } ?: return
        owned.claim(player, view)
    }

    private fun requestContentsPage(player: Player, grave: UUID, readOnly: Boolean, page: Int) {
        if (readOnly) openAdminContents(player, grave, page) else openContents(player, grave, page)
    }

    private fun beginAdmin(player: Player): GravesAdminRequests.Request? {
        if (closed || !player.isOnline || player.isDead) return null
        if (!player.hasPermission(GRAVES_ADMIN_PERMISSION)) { feedback(player, "admin-denied"); return null }
        player.closeInventory()
        ArcMenus.beginDialogFlow(player)
        val request = adminRequests.begin(player) ?: return null
        adminMenu.loading(player) { adminRequests.dismiss(request) }
        return request
    }

    private fun openAdmin(player: Player, page: Int = 0, serverFilter: String? = null) {
        val request = beginAdmin(player) ?: return
        graveStore.thenCompose { it.loadActive(System.currentTimeMillis(), serverFilter) }.whenCompleteSync(tasks) { records, failure ->
            if (!adminRequests.complete(request)) return@whenCompleteSync
            if (failure != null) warn("Graves administrator list failed server={} player={}", server, player.uniqueId, failure)
            adminMenu.open(player, if (failure == null) records else null, page, serverFilter)
        }
    }

    private fun openAdminContents(player: Player, grave: UUID, page: Int = 0) {
        val request = beginAdmin(player) ?: return
        graveStore.thenCompose { it.findActive(grave, System.currentTimeMillis()) }.whenCompleteSync(tasks) { record, failure ->
            if (!adminRequests.complete(request)) return@whenCompleteSync
            if (failure != null) warn("Graves administrator contents failed server={} player={} grave={}", server, player.uniqueId, grave, failure)
            ArcMenus.closeDialog(player)
            if (record == null || failure != null) feedback(player, "contents-unavailable")
            else contentsMenu.open(player, record, readOnly = true, page = page)
        }
    }

    internal fun price(player: Player): Double = if (player.hasPermission(settings.freePermission)) 0.0 else settings.defaultFee
    internal fun validLocal(player: Player, grave: UUID): Boolean = !owned.blocked(player.uniqueId) &&
        (owned.find(grave)?.let { it.server == server && it.ownerId == player.uniqueId } == true || native?.canTeleport(player, grave) == true)
    internal fun feedback(player: Player, key: String) {
        if (!player.isOnline) return
        player.sendMessage(settings.text(key))
        if (key == "claimed" || key == "claimed-partial") hud.claimSucceeded(player)
    }

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

    fun openPersonal(player: Player) {
        adminRequests.invalidate(player)
        if (closed || !player.isOnline || player.isDead) return
        ArcMenus.beginDialogFlow(player)
        menu.open(player)
    }

    private fun openCommand(player: Player, args: List<String>) {
        adminRequests.invalidate(player)
        if (args.firstOrNull()?.equals("admin", ignoreCase = true) == true) {
            if (!player.hasPermission(GRAVES_ADMIN_PERMISSION)) { feedback(player, "admin-denied"); return }
            if (args.size > 2) { feedback(player, "unavailable"); return }
            val filter = args.getOrNull(1)?.let { BackendServerId.parseOrNull(it)?.value }
            if (args.size == 2 && filter == null) { feedback(player, "unavailable"); return }
            openAdmin(player, serverFilter = filter)
        } else openPersonal(player)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun command(event: PlayerCommandPreprocessEvent) {
        val words = event.message.trim().removePrefix("/").split(Regex("\\s+"))
        val label = words.firstOrNull()?.lowercase() ?: return
        if (label !in setOf("graves", "grave", "gravesx", "graves:graves", "gravesx:graves", "arc:graves")) return
        if (words.size > 1 && words[1].lowercase() !in setOf("list", "menu", "gui", "admin")) return
        event.isCancelled = true
        openCommand(event.player, words.drop(1))
    }

    @EventHandler fun join(event: PlayerJoinEvent) {
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
        outgoing.remove(event.player.uniqueId)
    }

    @EventHandler fun changeWorld(event: PlayerChangedWorldEvent) = hud.clear(event.player)
    @EventHandler fun death(event: PlayerDeathEvent) = hud.clear(event.entity)

    fun closeAsync(): CompletableFuture<Void> {
        drain?.let { return it }
        closed = true
        adminRequests.close()
        HandlerList.unregisterAll(this)
        nativeListener?.let(HandlerList::unregisterAll)
        syncListener?.let(HandlerList::unregisterAll)
        tasks.close()
        payments.close()
        network?.close()
        legacyHolograms.close()
        frameInteractions?.close()
        frameInteractions = null
        barriers.close()
        // Explicit stop/reload maintenance: these stages use SQL/file workers only,
        // never Paper callbacks. Finish before ARC seals its background admission.
        try { restoration.join(); archive.checkpointOnShutdown().join() }
        catch (failure: Throwable) { warn("Legacy grave handoff failed on {}; keep the original GravesX database and plugin", server, failure) }
        val nativeStorage = owned.closeAsync()
        val decorativeStorage = decorative.closeAsync()
        storageDrain = CompletableFuture.allOf(nativeStorage, decorativeStorage)
        // A terminal local-scenery I/O failure has no live resources left. Keep it observable in
        // storageDrained, but let the next runtime recover player items and reload scenery separately.
        val decorativeRetired = decorativeStorage.handle { _, failure ->
            if (failure != null) warn("Decorative grave catalog I/O failed before retirement on {}; scenery will reload independently of item recovery", server, failure)
            null
        }
        return CompletableFuture.allOf(nativeStorage, decorativeRetired, hud.closeAsync()).also { drain = it }
    }
    fun storageDrained(): CompletableFuture<Void> = storageDrain.copy()
    override fun close() { closeAsync() }
}

/** Owns only outstanding administrator SQL requests; it never stores grave contents. */
internal class GravesAdminRequests(private val currentPlayer: (UUID) -> Player? = Bukkit::getPlayer) : Listener, AutoCloseable {
    class Request internal constructor(val nonce: Long, val player: Player, val world: UUID)
    private val requests = mutableMapOf<UUID, Request>()
    private var serial = 0L
    private var closed = false

    fun begin(player: Player): Request? {
        if (closed || !player.isOnline || player.isDead || !player.hasPermission(GRAVES_ADMIN_PERMISSION)) return null
        return Request(++serial, player, player.world.uid).also { requests[player.uniqueId] = it }
    }

    fun invalidate(player: Player) { requests.remove(player.uniqueId) }
    fun dismiss(request: Request) { if (requests[request.player.uniqueId] === request) requests.remove(request.player.uniqueId) }

    /** Consume before opening either UI: its own InventoryOpen must not cancel the result. */
    fun complete(request: Request): Boolean {
        if (requests[request.player.uniqueId] !== request) return false
        requests.remove(request.player.uniqueId)
        val player = request.player
        return !closed && player.isOnline && !player.isDead && currentPlayer(player.uniqueId) === player &&
            player.world.uid == request.world && player.hasPermission(GRAVES_ADMIN_PERMISSION)
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun command(event: PlayerCommandPreprocessEvent) { invalidate(event.player) }
    @EventHandler(priority = EventPriority.LOWEST)
    fun inventory(event: InventoryOpenEvent) { (event.player as? Player)?.let(::invalidate) }
    @EventHandler fun quit(event: PlayerQuitEvent) { invalidate(event.player) }
    @EventHandler fun world(event: PlayerChangedWorldEvent) { invalidate(event.player) }

    override fun close() { closed = true; requests.clear(); HandlerList.unregisterAll(this) }
}
