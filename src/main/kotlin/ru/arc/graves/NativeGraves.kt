package ru.arc.graves

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.event.player.PlayerSwapHandItemsEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.event.player.PlayerItemConsumeEvent
import org.bukkit.event.player.PlayerItemDamageEvent
import org.bukkit.event.player.PlayerItemMendEvent
import org.bukkit.event.player.PlayerMoveEvent
import com.destroystokyo.paper.event.player.PlayerPickupExperienceEvent
import ru.arc.ARC
import ru.arc.audit.AuditConfig
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TaskScheduler
import ru.arc.core.whenCompleteSync
import org.bukkit.plugin.Plugin
import ru.arc.util.Logging.info
import ru.arc.util.Logging.warn
import java.util.UUID
import java.util.concurrent.CompletableFuture
import kotlin.math.floor

/**
 * Owns player graves. A shared SQL intent precedes every item transition. The player
 * keeps death items until the intent is durable; a pending intent survives disconnect,
 * server changes and reload. Finalization follows the authoritative player-data ACK.
 */
internal class NativeGraves(
    private val server: String,
    private val settings: GravesSettings,
    private val feedback: (Player, String) -> Unit,
    private val changed: () -> Unit,
    private val plugin: Plugin,
    private val opened: CompletableFuture<NativeGraveRepository>,
    private val persistence: NativeGravePlayerPersistence,
    scheduler: TaskScheduler,
    private val restoration: CompletableFuture<Unit> = CompletableFuture.completedFuture(Unit),
) : Listener, AutoCloseable {
    private val tasks = LifecycleTaskScope(scheduler)
    private var repository: NativeGraveRepository? = null
    private var records = mutableMapOf<UUID, NativeGraveRecord>()
    private var projection = emptyList<GraveView>()
    private var projectionDirty = true
    private val busy = mutableSetOf<UUID>()
    private val preparing = mutableSetOf<UUID>()
    private val synced = mutableSetOf<UUID>()
    private val syncCompleted = mutableSetOf<UUID>()
    private val freshSync = mutableSetOf<UUID>()
    private val watermarks = mutableMapOf<UUID, NativeGraveWatermark>()
    private val knownRevisions = mutableMapOf<UUID, Long>()
    private val lastSafe = mutableMapOf<UUID, Location>()
    private val conflicts = mutableSetOf<UUID>()
    private val completions = mutableSetOf<CompletableFuture<*>>()
    private var refreshPending = false
    private var closed = false
    private var drain: CompletableFuture<Void>? = null
    var ready = false; private set
    private var failed = false
    private val usesSync = plugin.server.pluginManager.isPluginEnabled("HuskSync")
    private val inventoryScope = if (persistence.sharedInventory) "network" else "local:$server"

    fun start() {
        Bukkit.getPluginManager().registerEvents(this, plugin)
        opened.thenCombine(restoration) { value, _ -> value }.whenCompleteSync(tasks) { value, failure ->
            if (failure != null) { report("open", null, failure); return@whenCompleteSync }
            repository = value
            refresh()
        }
        if (!usesSync) Bukkit.getOnlinePlayers().forEach { syncCompleted += it.uniqueId }
        else Bukkit.getOnlinePlayers().filter(persistence::isSynchronized).forEach { syncCompleted += it.uniqueId }
        tasks.runTimer(20, 40) { refresh() }
    }

    /** Pure cached projection includes remote/offline backends; SQL is authoritative. */
    fun views(): List<GraveView> {
        if (projectionDirty) projection = records.values.asSequence()
        .filter { it.phase == NativeGravePhase.ACTIVE && (it.expiresAt < 0 || it.expiresAt > System.currentTimeMillis()) }
        .map { record -> GraveView(record.id, record.ownerId, record.server, record.world, record.x, record.y,
            record.z, record.createdAt, record.expiresAt, record.experience,
            record.items.map { raw ->
                val item = NativeGraveItems.decode(raw)
                val name = item.itemMeta?.displayName() ?: Component.translatable(item.type.translationKey())
                GraveItem(GravesNetworkWire.safeItemNameJson(GsonComponentSerializer.gson().serialize(safeName(name))), item.amount)
            }) }.toList().also { projectionDirty = false }
        return projection.filter { it.active(System.currentTimeMillis()) }
    }

    fun find(id: UUID): NativeGraveRecord? = records[id]?.takeIf { it.phase == NativeGravePhase.ACTIVE && (it.expiresAt < 0 || it.expiresAt > System.currentTimeMillis()) }
    fun playerReady(player: UUID): Boolean = ready && player in synced && !blocked(player)
    fun blocked(player: UUID): Boolean = player in preparing || player in busy || (player in syncCompleted && player !in synced) || records.values.any {
        it.ownerId == player && it.operation?.inventoryScope == inventoryScope && it.phase in setOf(NativeGravePhase.PREPARED, NativeGravePhase.CLAIMING)
    }

    fun canClaim(player: Player, view: GraveView): Boolean = ready && !closed && player.isOnline && !player.isDead &&
        player.uniqueId in synced && view.ownerId == player.uniqueId && view.server == server && view.world == player.world.name &&
        !blocked(player.uniqueId) && player.location.distanceSquared(Location(player.world, view.x + .5, view.y + .5, view.z + .5)) <= settings.claimDistance * settings.claimDistance && find(view.id) != null

    fun claim(player: Player, view: GraveView) {
        if (!canClaim(player, view)) { feedback(player, "claim-unavailable"); return }
        val previous = find(view.id) ?: return
        val before = NativeGraveItems.capture(player)
        val pickup = NativeGraveItems.pickup(before, previous.items, previous.experience)
        if (previous.experience == 0 && NativeGraveItems.matches(before, pickup.after)) { feedback(player, "inventory-full"); return }
        val operation = NativeGraveOperation(UUID.randomUUID(), server, NativeGraveOperationKind.CLAIM, before, pickup.after, inventoryScope = inventoryScope)
        val pending = previous.copy(revision = previous.revision + 1, phase = NativeGravePhase.CLAIMING,
            items = pickup.remaining, experience = 0, operation = operation)
        preparing += player.uniqueId
        val store = requireNotNull(repository)
        observe(store.reserveSequence(operation.id, player.uniqueId).thenCompose { sequence ->
            val numbered = pending.copy(operation = operation.copy(sequence = sequence))
            store.replace(previous, numbered).thenApply { it to numbered }
        }) { result, failure ->
            preparing -= player.uniqueId
            if (failure != null) { report("claim-intent", previous, failure); refresh(); return@observe }
            if (result?.first != true) { feedback(player, "changed"); refresh(); return@observe }
            remember(result.second)
            resume(player)
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun death(event: PlayerDeathEvent) {
        val player = event.entity
        if (event.keepInventory || player.gameMode !in setOf(GameMode.SURVIVAL, GameMode.ADVENTURE)) return
        // GravesX's exact-version death handlers run at MONITOR. Keeping inventory here
        // suppresses its new graves, while the optional adapter can still serve old ones.
        if (!ready || player.uniqueId !in synced) {
            preserveDeath(event)
            feedback(player, "storage-preserved")
            return
        }
        if (blocked(player.uniqueId)) {
            preserveDeath(event)
            feedback(player, "pending-preserved")
            return
        }
        val drops = event.drops.filterNot { it.isEmpty }.map { it.clone() }
        val keepXp = event.keepLevel
        val before = NativeGraveItems.capture(player)
        val xp = if (keepXp) 0 else floor(NativeGraveItems.experiencePoints(before.level, before.progress) * settings.experienceFraction).toInt()
        if (drops.isEmpty() && xp == 0) return
        if (records.values.count { it.ownerId == player.uniqueId && it.server == server && it.phase in setOf(NativeGravePhase.ACTIVE, NativeGravePhase.PREPARED) && (it.expiresAt < 0 || it.expiresAt > System.currentTimeMillis()) } >= settings.maxGraves) {
            preserveDeath(event); feedback(player, "grave-limit-preserved"); return
        }
        val location = player.location
        val anchor = GraveDestinations.nearby(location.world, location.blockX, location.blockY, location.blockZ)
            ?: lastSafe[player.uniqueId]?.takeIf { it.world.uid == location.world.uid &&
                it.distanceSquared(location) <= 128.0 * 128.0 && GraveDestinations.safe(it) }
        if (anchor == null) { preserveDeath(event); feedback(player, "placement-preserved"); return }
        val now = System.currentTimeMillis()
        val record = NativeGraveRecord(id = UUID.randomUUID(), ownerId = player.uniqueId, ownerName = player.name,
            server = server, world = anchor.world.name, x = anchor.blockX,
            y = anchor.blockY, z = anchor.blockZ,
            createdAt = now, expiresAt = now + settings.retentionSeconds * 1000L, experience = xp,
            items = drops.map(NativeGraveItems::encode), revision = 0, phase = NativeGravePhase.PREPARED,
            operation = NativeGraveOperation(UUID.randomUUID(), server, NativeGraveOperationKind.CREATE, before,
                NativeGraveItems.afterDeath(before, drops, keepXp), inventoryScope = inventoryScope))
        preserveDeath(event)
        preparing += player.uniqueId
        // Paper permits death cancellation. Let later handlers settle before owning
        // an inventory transition; a rescued player must never acquire a death grave.
        tasks.runLater(1) {
            if (event.isCancelled) { preparing -= player.uniqueId; return@runLater }
            val store = requireNotNull(repository)
            observe(store.reserveSequence(requireNotNull(record.operation).id, player.uniqueId).thenCompose { sequence ->
                val numbered = record.copy(operation = record.operation.copy(sequence = sequence))
                store.insert(numbered).thenApply { it to numbered }
            }) { result, failure ->
                preparing -= player.uniqueId
                if (failure != null || result?.first != true) {
                    if (failure != null) report("death-intent", record, failure)
                    feedback(player, "creation-preserved")
                    refresh()
                    return@observe
                }
                remember(result.second)
                resume(player)
            }
        }
    }

    private fun preserveDeath(event: PlayerDeathEvent) {
        event.keepInventory = true
        event.keepLevel = true
        event.drops.clear()
        event.droppedExp = 0
    }

    fun synchronized(playerId: UUID) {
        syncCompleted += playerId
        synced -= playerId
        // Cached pending data must never be the first post-transfer authority.
        freshSync += playerId
        refresh(force = true)
    }

    private fun ensureSynchronized(player: Player) {
        if (closed || !ready || !player.isOnline || player.isDead || player.uniqueId !in syncCompleted || player.uniqueId in busy || player.uniqueId in freshSync) return
        val minimum = if (persistence.sharedInventory) watermarks[player.uniqueId] else null
        if (player.uniqueId in synced && persistence.sequence(player) >= (minimum?.sequence ?: 0)) { resume(player); return }
        synced -= player.uniqueId
        busy += player.uniqueId
        observe(persistence.reconcile(player, minimum)) { _, failure ->
            busy -= player.uniqueId
            if (failure != null) { report("sync-reconcile", null, failure); return@observe }
            if (player.isOnline && Bukkit.getPlayer(player.uniqueId) === player && player.uniqueId in syncCompleted) {
                synced += player.uniqueId
                resume(player)
                changed()
            }
        }
    }

    /** Called after actual HuskSync completion, never after an arbitrary delay. */
    private fun resume(player: Player) {
        if (closed || !ready || !player.isOnline || player.isDead || player.uniqueId !in synced || player.uniqueId in busy) return
        val pending = records.values.filter { it.ownerId == player.uniqueId && it.operation?.inventoryScope == inventoryScope && it.phase in setOf(NativeGravePhase.PREPARED, NativeGravePhase.CLAIMING) }
        if (pending.size > 1) {
            report("multiple-operations", pending.first(), IllegalStateException("Multiple pending grave transitions for one player")); return
        }
        val record = pending.singleOrNull() ?: return
        busy += player.uniqueId
        // The cache is a discovery hint, never an admission. A completed old claim
        // must lose this fresh SQL CAS before any inventory effect can be replayed.
        val admitted = record.copy(revision = record.revision + 1)
        observe(requireNotNull(repository).replace(record, admitted)) { accepted, failure ->
            if (failure != null || accepted != true) {
                busy -= player.uniqueId
                if (failure != null) report("apply-admission", record, failure)
                synchronized(player.uniqueId)
                return@observe
            }
            remember(admitted)
            if (!player.isOnline || Bukkit.getPlayer(player.uniqueId) !== player || player.isDead || player.uniqueId !in synced) { busy -= player.uniqueId; return@observe }
            applyAdmitted(player, admitted)
        }
    }

    private fun applyAdmitted(player: Player, record: NativeGraveRecord) {
        val operation = requireNotNull(record.operation)
        val current = NativeGraveItems.capture(player)
        val receipted = operation.sequence > 0 && persistence.sequence(player) >= operation.sequence
        if (!receipted && !NativeGraveItems.matches(current, operation.before) && !NativeGraveItems.matches(current, operation.after)) {
            val review = record.copy(revision = record.revision + 1, phase = NativeGravePhase.REVIEW)
            observe(requireNotNull(repository).replace(record, review)) { accepted, failure ->
                busy -= player.uniqueId
                if (accepted == true) remember(review)
                report("inventory-conflict", record, failure ?: IllegalStateException("Inventory differs from both durable states; affected entitlement preserved for reconciliation"))
                feedback(player, "inventory-conflict")
            }
            return
        }
        try {
            if (!receipted && !NativeGraveItems.matches(current, operation.after)) NativeGraveItems.apply(player, operation.after)
            val checkpoint = NativeGraveItems.capture(player)
            val saved = persistence.persist(player, operation.sequence)
            observe(saved) { _, failure ->
                if (failure != null) { busy -= player.uniqueId; report("player-save", record, failure); return@observe }
                val completed = record.copy(revision = record.revision + 1, operation = null,
                    completedSequence = if (persistence.sharedInventory) operation.sequence else 0,
                    checkpoint = if (persistence.sharedInventory) checkpoint else null,
                    phase = if (record.items.isEmpty() && record.experience == 0) NativeGravePhase.CLAIMED else NativeGravePhase.ACTIVE)
                observe(requireNotNull(repository).replace(record, completed)) { accepted, problem ->
                    busy -= player.uniqueId
                    if (problem != null || accepted != true) {
                        report("finalize", record, problem ?: IllegalStateException("Grave finalization CAS declined"))
                        synchronized(player.uniqueId)
                    } else {
                        remember(completed)
                        conflicts -= operation.id
                        if (operation.kind != NativeGraveOperationKind.CREATE || settings.creationMessageEnabled) {
                            feedback(player, if (operation.kind == NativeGraveOperationKind.CREATE) "created" else if (completed.phase == NativeGravePhase.CLAIMED) "claimed" else "claimed-partial")
                        }
                        changed()
                    }
                }
            }
        } catch (failure: Throwable) {
            busy -= player.uniqueId
            report("apply", record, failure)
        }
    }

    private fun refresh(force: Boolean = false) {
        if (closed || (refreshPending && !force)) return
        val store = repository ?: return
        refreshPending = true
        val owners = Bukkit.getOnlinePlayers().mapTo(hashSetOf()) { it.uniqueId }
        val fresh = freshSync.toSet()
        val beforeRead = knownRevisions.toMap()
        val networkWatermarks = if (persistence.sharedInventory) store.loadWatermarks(owners) else CompletableFuture.completedFuture<Map<UUID, NativeGraveWatermark>>(emptyMap())
        observe(store.loadCurrent(server, owners, System.currentTimeMillis()).thenCombine(networkWatermarks) { rows, marks -> rows to marks }) { loaded, failure ->
            refreshPending = false
            if (failure != null) { ready = false; report("refresh", null, failure); return@observe }
            if (failed) info("Native graves storage recovered on {}", server)
            failed = false
            ready = true
            val rows = loaded?.first.orEmpty()
            val ids = rows.mapTo(hashSetOf()) { it.id }
            // A query's absent row retires the old projection, unless a newer local
            // write completed after that query began. Retain only revision tombstones.
            if (records.entries.removeIf { it.key !in ids && knownRevisions[it.key] == beforeRead[it.key] }) projectionDirty = true
            rows.forEach(::remember)
            loaded?.second.orEmpty().forEach { (owner, mark) ->
                if ((watermarks[owner]?.sequence ?: 0) < mark.sequence) watermarks[owner] = mark
            }
            watermarks.keys.removeIf { it !in owners && it !in busy && it !in preparing }
            freshSync.removeAll(fresh)
            Bukkit.getOnlinePlayers().forEach(::ensureSynchronized)
            changed()
        }
    }

    private fun remember(record: NativeGraveRecord) {
        if ((knownRevisions[record.id] ?: -1) < record.revision || (record.id !in records && knownRevisions[record.id] == record.revision && record.phase != NativeGravePhase.CLAIMED)) {
            knownRevisions[record.id] = record.revision
            if (record.phase == NativeGravePhase.CLAIMED) records.remove(record.id) else records[record.id] = record
            if (record.completedSequence > (watermarks[record.ownerId]?.sequence ?: 0) && record.checkpoint != null) {
                watermarks[record.ownerId] = NativeGraveWatermark(record.ownerId, record.completedSequence, record.checkpoint)
            }
            projectionDirty = true
        }
    }

    private fun <T> observe(future: CompletableFuture<T>, complete: (T?, Throwable?) -> Unit) {
        completions += future
        future.whenCompleteSync(tasks) { value, failure ->
            completions -= future
            if (!closed) complete(value, failure)
        }
    }

    private fun report(operation: String, grave: NativeGraveRecord?, failure: Throwable) {
        if (operation != "refresh" || !failed) warn("Native graves operation={} server={} grave={} player={}", operation, server, grave?.id, grave?.ownerId, failure)
        failed = true
    }

    @EventHandler fun respawn(event: PlayerRespawnEvent) { tasks.runLater(1) { ensureSynchronized(event.player) } }
    @EventHandler fun join(event: PlayerJoinEvent) {
        if (!usesSync) synchronized(event.player.uniqueId)
    }
    @EventHandler fun quit(event: PlayerQuitEvent) { synced -= event.player.uniqueId; syncCompleted -= event.player.uniqueId; freshSync -= event.player.uniqueId; lastSafe -= event.player.uniqueId }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) fun moved(event: PlayerMoveEvent) {
        if (!event.hasChangedBlock()) return
        val target = event.to
        if (GraveDestinations.safe(target)) lastSafe[event.player.uniqueId] = target.clone()
    }
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true) fun click(event: InventoryClickEvent) { if (blocked(event.whoClicked.uniqueId)) event.isCancelled = true }
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true) fun drag(event: InventoryDragEvent) { if (blocked(event.whoClicked.uniqueId)) event.isCancelled = true }
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true) fun drop(event: PlayerDropItemEvent) { if (blocked(event.player.uniqueId)) event.isCancelled = true }
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true) fun swap(event: PlayerSwapHandItemsEvent) { if (blocked(event.player.uniqueId)) event.isCancelled = true }
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true) fun pickup(event: EntityPickupItemEvent) { if (blocked(event.entity.uniqueId)) event.isCancelled = true }
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true) fun interact(event: PlayerInteractEvent) { if (blocked(event.player.uniqueId)) event.isCancelled = true }
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true) fun experience(event: PlayerPickupExperienceEvent) { if (blocked(event.player.uniqueId)) event.isCancelled = true }
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true) fun consume(event: PlayerItemConsumeEvent) { if (blocked(event.player.uniqueId)) event.isCancelled = true }
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true) fun damageItem(event: PlayerItemDamageEvent) { if (blocked(event.player.uniqueId)) event.isCancelled = true }
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true) fun mend(event: PlayerItemMendEvent) { if (blocked(event.player.uniqueId)) event.isCancelled = true }
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true) fun command(event: PlayerCommandPreprocessEvent) {
        val label = event.message.substringBefore(' ').lowercase()
        if (blocked(event.player.uniqueId) && label !in setOf("/graves", "/arc:graves", "/menu", "/help")) { event.isCancelled = true; feedback(event.player, "items-pending") }
    }
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true) fun teleport(event: PlayerTeleportEvent) { if (blocked(event.player.uniqueId)) event.isCancelled = true }
    fun closeAsync(beforeStoreClose: CompletableFuture<*> = CompletableFuture.completedFuture(Unit)): CompletableFuture<Void> {
        drain?.let { return it }
        closed = true
        ready = false
        HandlerList.unregisterAll(this)
        tasks.close()
        persistence.close()
        // Accepted SQL work owns its original repository until its actual completion.
        val accepted = CompletableFuture.allOf(*completions.toTypedArray(), restoration, beforeStoreClose)
        return opened.handle { value, _ -> value }.thenCombine(accepted.handle { _, _ -> Unit }) { value, _ -> value }
            .thenCompose { value -> value?.closeAsync()?.thenApply<Void> { null } ?: CompletableFuture.completedFuture(null) }
            .also { drain = it }
    }
    override fun close() { closeAsync() }

    private fun safeName(value: Component): Component = value.clickEvent(null).hoverEvent(null).insertion(null).children(value.children().map(::safeName))
}
