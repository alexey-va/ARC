package ru.arc.itemcatalog

import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Tag
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerItemHeldEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.MapMeta
import org.bukkit.map.MapCanvas
import org.bukkit.map.MapCursor
import org.bukkit.map.MapCursorCollection
import org.bukkit.map.MapRenderer
import org.bukkit.map.MapView
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import ru.arc.ARC
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.ScheduledTask
import ru.arc.core.TaskScheduler
import ru.arc.core.Tasks
import ru.arc.core.whenCompleteSync
import ru.arc.hooks.HookRegistry
import ru.arc.onetime.OneTimeUseFingerprint
import java.awt.Color
import java.awt.Graphics2D
import java.awt.image.BufferedImage
import java.util.Collections
import java.util.UUID
import java.util.WeakHashMap
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

internal fun personalTreasureMapLocationUnclaimed(plugin: Plugin, location: Location): Boolean? {
    return runCatching {
        val manager = plugin.server.pluginManager
        val landsPlugin = manager.getPlugin("Lands") ?: return@runCatching null
        if (!manager.isPluginEnabled(landsPlugin)) return@runCatching null

        val landsClear = HookRegistry.landsHook?.isUnclaimed(location) ?: return@runCatching null
        if (!landsClear) return@runCatching false

        val worldGuardPlugin = manager.getPlugin("WorldGuard") ?: return@runCatching true
        if (!manager.isPluginEnabled(worldGuardPlugin)) return@runCatching null

        val worldGuardClear = HookRegistry.wgHook?.isUnclaimed(location) ?: return@runCatching null
        landsClear && worldGuardClear
    }.getOrNull()
}

internal data class PersonalTreasureMapRenderState(
    val ownerId: UUID,
    val destination: PersonalTreasureMapDestination,
    val playerX: Double?,
    val playerZ: Double?,
    val yaw: Float,
)

/** Owns the native map guidance view and viewer-only cache marker for personal treasure maps. */
class PersonalTreasureMapController internal constructor(
    private val plugin: Plugin,
    private val resolveSpec: (String) -> PhysicalRewardSpec?,
    private val resolveDefinition: (PhysicalRewardSpec) -> PersonalTreasureMapDefinition?,
    private val currentServer: () -> String = { ARC.serverName ?: "arc" },
    private val scheduler: TaskScheduler = Tasks.scheduler,
    private val marker: PersonalTreasureMapMarker = PacketPersonalTreasureMapMarker(plugin),
    private val mapViewFactory: (org.bukkit.World) -> MapView = Bukkit::createMap,
    private val asyncChunkLoader: (World, Int, Int) -> CompletionStage<Chunk?> = { world, chunkX, chunkZ ->
        world.getChunkAtAsync(chunkX, chunkZ, false)
    },
    private val isUnclaimed: (Location) -> Boolean? = { location ->
        personalTreasureMapLocationUnclaimed(plugin, location)
    },
) : Listener, AutoCloseable {
    private data class MapViewKey(val server: String, val worldId: UUID)

    private data class ResolvedMap(
        val voucher: PhysicalRewardVoucherIdentity,
        val identity: PersonalTreasureMapIdentity,
        val spec: PhysicalRewardSpec,
        val definition: PersonalTreasureMapDefinition,
        val destination: PersonalTreasureMapDestination?,
    )

    private data class PendingSearch(
        val operationId: Long,
        val playerId: UUID,
        val voucher: PhysicalRewardVoucherIdentity,
        val identity: PersonalTreasureMapIdentity,
        val policy: PersonalTreasureMapSearchPolicy,
        val worldId: UUID,
        val originX: Double,
        val originZ: Double,
        val candidates: List<Pair<Int, Int>>,
        val lifecycleToken: LifecycleTaskScope.Token,
        var timeout: ScheduledTask? = null,
    )

    private val renderStates = ConcurrentHashMap<UUID, PersonalTreasureMapRenderState>()
    private val renderer = PersonalTreasureMapRenderer(renderStates)
    private val markerFailures = mutableSetOf<UUID>()
    private val mapViews = mutableMapOf<MapViewKey, MapView>()
    private val taskScope = LifecycleTaskScope(scheduler)
    private val lifecycleToken = taskScope.token()
    private val pendingSearches = mutableMapOf<UUID, PendingSearch>()
    private var nextSearchOperationId = 0L
    private var closed = false
    private var refreshTask: ScheduledTask? = taskScope.runTimer(lifecycleToken, 20L, 20L) { refreshOnlinePlayers() }

    init {
        plugin.server.pluginManager.registerEvents(this, plugin)
    }

    /** Binds a clean map voucher to its first activator and attaches this node's contextual view. */
    fun bindToOwner(player: Player, stack: ItemStack): ItemStack? {
        if (closed || stack.type != Material.FILLED_MAP || stack.amount != 1) return null
        val voucher = PhysicalRewardVoucher.identity(stack) ?: return null
        val spec = resolveSpec(voucher.key)?.takeIf { it.fingerprint == voucher.fingerprint } ?: return null
        val definition = resolveDefinition(spec) ?: return null
        val existing = PersonalTreasureMapIdentity.read(stack)
        if (existing != null) {
            if (existing.voucherId != voucher.id || existing.ownerId != player.uniqueId ||
                !matchesDefinition(existing, definition, voucher.id)
            ) return null
            return decorateMap(player, stack)
        }
        if (hasPersonalMapMarker(stack)) return null
        val identity = PersonalTreasureMapIdentity(
            voucherId = voucher.id,
            ownerId = player.uniqueId,
            definitionId = definition.id,
            definitionFingerprint = definition.fingerprint,
            destinationIndex = definition.destinationIndex(voucher.id),
            mapViewServer = null,
            mapViewId = null,
            mapViewWorld = null,
            searchGeneration = 0,
            target = null,
            targetPolicyFingerprint = definition.searchPolicy?.targetPolicyFingerprint?.sha256,
        )
        val bound = stack.clone()
        bound.editMeta { meta -> writeIdentity(meta.persistentDataContainer, identity) }
        return decorateMap(player, bound)
    }

    /** Rebinds a bound map to this runtime's view, including after a node transfer or plugin reload. */
    fun decorateMap(player: Player, stack: ItemStack): ItemStack? {
        if (closed) return null
        val resolved = resolve(stack) ?: return null
        val copy = stack.clone()
        val view = ensureMapView(player, copy) ?: return null
        val presentation = resolved.spec.preview.itemMeta
        copy.editMeta { meta ->
            val mapMeta = meta as? MapMeta ?: return@editMeta
            presentation.displayName()?.let(mapMeta::displayName)
            mapMeta.lore(presentation.lore())
            mapMeta.isScaling = false
            mapMeta.mapView = view
            val data = mapMeta.persistentDataContainer
            data.set(PersonalTreasureMapIdentity.mapViewServerKey, PersistentDataType.STRING, currentServer())
            data.set(PersonalTreasureMapIdentity.mapViewIdKey, PersistentDataType.INTEGER, view.id)
            data.set(PersonalTreasureMapIdentity.mapViewWorldKey, PersistentDataType.STRING, player.world.uid.toString())
        }
        return copy
    }

    fun identity(stack: ItemStack?): PersonalTreasureMapIdentity? = PersonalTreasureMapIdentity.read(stack)

    /**
     * Runs before the shared one-use claim. OPEN_MAP updates the held filled-map view without claiming;
     * CLAIM lets the shared controller acquire the durable claim. The shared listener cancels block interaction.
     */
    fun beforeUse(player: Player, stack: ItemStack?): PersonalTreasureMapUseDecision {
        val voucher = PhysicalRewardVoucher.identity(stack) ?:
            return if (hasPersonalMapMarker(stack)) PersonalTreasureMapUseDecision.REJECT
            else PersonalTreasureMapUseDecision.NOT_A_PERSONAL_MAP
        val spec = resolveSpec(voucher.key)?.takeIf { it.fingerprint == voucher.fingerprint }
        val definition = spec?.let(resolveDefinition)
        val personalCandidate = hasPersonalMapMarker(stack) || definition != null
        if (!personalCandidate) return PersonalTreasureMapUseDecision.NOT_A_PERSONAL_MAP
        val searchPolicy = definition?.searchPolicy
        if (searchPolicy != null && !isSearchLocation(player, searchPolicy)) {
            // An explicitly migrated, owner-bound map may be activated outside its new search world.
            // Validate it and discard only the exact known legacy target before returning guidance.
            if (hasPersonalMapMarker(stack) && definition.legacyTargetPolicy != null) {
                val resolved = resolve(stack) ?: return PersonalTreasureMapUseDecision.REJECT
                if (resolved.voucher != voucher || tokenFailure(player, resolved) != null) {
                    return PersonalTreasureMapUseDecision.REJECT
                }
                if (hasLegacyTarget(resolved)) {
                    if (!migrateLegacyTarget(player, resolved, selectTarget = false)) {
                        return PersonalTreasureMapUseDecision.REJECT
                    }
                }
            }
            return PersonalTreasureMapUseDecision.OPEN_MAP
        }
        if (!hasPersonalMapMarker(stack)) {
            val mainHand = player.inventory.itemInMainHand
            if (mainHand.type != Material.FILLED_MAP || mainHand.amount != 1 ||
                PhysicalRewardVoucher.identity(mainHand) != voucher || hasPersonalMapMarker(mainHand)
            ) return PersonalTreasureMapUseDecision.REJECT
            val bound = bindToOwner(player, mainHand) ?: return PersonalTreasureMapUseDecision.REJECT
            val currentMainHand = player.inventory.itemInMainHand
            if (currentMainHand.type != Material.FILLED_MAP || currentMainHand.amount != 1 ||
                PhysicalRewardVoucher.identity(currentMainHand) != voucher || hasPersonalMapMarker(currentMainHand)
            ) {
                return PersonalTreasureMapUseDecision.REJECT
            }
            player.inventory.setItemInMainHand(bound)
            val resolved = resolve(bound) ?: return PersonalTreasureMapUseDecision.REJECT
            selectOrRefreshTarget(player, resolved, invalidateExisting = false)
            refreshHeldMap(player)
            return PersonalTreasureMapUseDecision.OPEN_MAP
        }
        val resolved = resolve(stack) ?: return PersonalTreasureMapUseDecision.REJECT
        if (resolved.voucher != voucher || tokenFailure(player, resolved) != null) {
            return PersonalTreasureMapUseDecision.REJECT
        }
        if (hasLegacyTarget(resolved)) {
            if (!migrateLegacyTarget(player, resolved, selectTarget = true)) {
                return PersonalTreasureMapUseDecision.REJECT
            }
            return PersonalTreasureMapUseDecision.OPEN_MAP
        }
        if (resolved.definition.searchPolicy != null && resolved.destination == null) {
            selectOrRefreshTarget(player, resolved, invalidateExisting = false)
            refreshHeldMap(player)
            return PersonalTreasureMapUseDecision.OPEN_MAP
        }
        val destination = resolved.destination ?: return PersonalTreasureMapUseDecision.REJECT
        if (locationFailure(player, destination) != null) {
            refreshHeldMap(player)
            return PersonalTreasureMapUseDecision.OPEN_MAP
        }
        when (isSafeTarget(player, destination, resolved.definition.searchPolicy)) {
            false -> {
                selectOrRefreshTarget(player, resolved, invalidateExisting = true)
                refreshHeldMap(player)
                return PersonalTreasureMapUseDecision.OPEN_MAP
            }
            null -> {
                refreshHeldMap(player)
                return PersonalTreasureMapUseDecision.OPEN_MAP
            }
            true -> Unit
        }
        refreshHeldMap(player)
        return PersonalTreasureMapUseDecision.CLAIM
    }

    /** Rechecked after the durable claim is acquired and before the reward adapter mutates anything. */
    fun preflight(
        player: Player,
        voucher: PhysicalRewardVoucherIdentity,
        spec: PhysicalRewardSpec,
    ): PersonalTreasureMapFailure? {
        if (closed || spec.key != voucher.key || spec.fingerprint != voucher.fingerprint) {
            return PersonalTreasureMapFailure.INVALID_OR_STALE
        }
        val held = player.inventory.itemInMainHand
        if (PhysicalRewardVoucher.identity(held) != voucher) return PersonalTreasureMapFailure.INVALID_OR_STALE
        val resolved = resolve(held) ?: return PersonalTreasureMapFailure.INVALID_OR_STALE
        if (resolved.voucher != voucher || resolved.spec.key != spec.key || resolved.spec.fingerprint != spec.fingerprint) {
            return PersonalTreasureMapFailure.INVALID_OR_STALE
        }
        tokenFailure(player, resolved)?.let { return it }
        val destination = resolved.destination ?: return PersonalTreasureMapFailure.NO_SAFE_TARGET
        locationFailure(player, destination)?.let { return it }
        return when (isSafeTarget(player, destination, resolved.definition.searchPolicy)) {
            true -> null
            null -> PersonalTreasureMapFailure.SAFETY_UNAVAILABLE
            false -> {
                val replaced = selectOrRefreshTarget(player, resolved, invalidateExisting = true)
                refreshHeldMap(player)
                if (replaced) PersonalTreasureMapFailure.TARGET_CHANGED else PersonalTreasureMapFailure.NO_SAFE_TARGET
            }
        }
    }

    /** Refreshes one holder's map cursor and nearby scene without loading chunks. */
    fun refreshHeldMap(player: Player) {
        if (closed || !player.isOnline) return
        val held = player.inventory.itemInMainHand
        val resolved = resolve(held)
        if (resolved == null || tokenFailure(player, resolved) != null) {
            clear(player.uniqueId)
            return
        }
        val rebound = decorateMap(player, held)
        if (rebound == null) {
            clear(player.uniqueId)
            return
        }
        if (rebound.itemMeta != held.itemMeta) player.inventory.setItemInMainHand(rebound)

        val destination = resolved.destination
        if (destination == null) {
            clearRenderState(player.uniqueId)
            return
        }
        val correctPlace = currentServer() == destination.server && player.world.name == destination.world
        val location = player.location
        renderStates[player.uniqueId] = PersonalTreasureMapRenderState(
            ownerId = player.uniqueId,
            destination = destination,
            playerX = location.x.takeIf { correctPlace },
            playerZ = location.z.takeIf { correctPlace },
            yaw = location.yaw,
        )
        val destinationChunkLoaded = correctPlace && player.world.isChunkLoaded(
            floor(destination.x).toInt() shr 4,
            floor(destination.z).toInt() shr 4,
        )
        val closeEnoughToSeeMarker = destinationChunkLoaded &&
            location.distanceSquared(destination.toLocation(player.world)) <= MARKER_DISTANCE_SQUARED
        if (closeEnoughToSeeMarker && isSafeTarget(player, destination, resolved.definition.searchPolicy) == true) {
            try {
                marker.show(player, destination)
                markerFailures.remove(player.uniqueId)
            } catch (failure: Throwable) {
                marker.clear(player.uniqueId)
                if (markerFailures.add(player.uniqueId)) {
                    plugin.logger.log(java.util.logging.Level.WARNING, "Personal treasure marker failed for ${player.uniqueId}", failure)
                }
            }
        } else {
            marker.clear(player.uniqueId)
            markerFailures.remove(player.uniqueId)
        }
    }

    /** Returns immutable guidance for the caller's localized action bar. */
    fun guidance(player: Player): PersonalTreasureMapGuidance? {
        val held = player.inventory.itemInMainHand
        val resolved = resolve(held)
        val definition = resolved?.definition ?: run {
            val voucher = PhysicalRewardVoucher.identity(held) ?: return null
            val spec = resolveSpec(voucher.key)?.takeIf { it.fingerprint == voucher.fingerprint } ?: return null
            resolveDefinition(spec) ?: return null
        }
        if (resolved != null && tokenFailure(player, resolved) != null) return null
        val policy = definition.searchPolicy
        val destination = resolved?.destination
        val targetServer = destination?.server ?: policy?.server ?: return null
        val targetWorld = destination?.world ?: policy?.let { if (it.acceptsWorld(player.world.name)) player.world.name else it.world } ?: return null
        val hint = destination?.hint ?: PersonalTreasureMapSearchPolicy.TARGET_HINT
        val onServer = currentServer() == targetServer
        val onWorld = onServer && player.world.name == targetWorld
        if (destination == null) {
            val heldVoucher = PhysicalRewardVoucher.identity(held)
            val heldIdentity = PersonalTreasureMapIdentity.read(held)
            val searching = pendingSearches[player.uniqueId]?.let { request ->
                request.voucher == heldVoucher && request.identity == heldIdentity && pendingStillOwnsMap(player, request)
            } == true
            return PersonalTreasureMapGuidance(
                hint, null, null, onServer, onWorld, false, false,
                ownerBound = resolved != null,
                searching = searching,
                targetWorld = destination?.world,
            )
        }
        if (!onWorld) {
            return PersonalTreasureMapGuidance(destination.hint, null, null, onServer, false, false, true, targetWorld = destination.world)
        }
        val location = player.location
        val dx = destination.x - location.x
        val dz = destination.z - location.z
        val locationAvailable = locationFailure(player, destination) == null
        val targetSafety = if (locationAvailable) isSafeTarget(player, destination, policy) else null
        return PersonalTreasureMapGuidance(
            hint = destination.hint,
            distance = sqrt(dx * dx + (destination.y - location.y) * (destination.y - location.y) + dz * dz),
            bearingDegrees = Math.toDegrees(atan2(dx, -dz)).let { (it + 360.0) % 360.0 },
            onDestinationServer = true,
            onDestinationWorld = true,
            withinClaimRadius = locationAvailable && targetSafety == true,
            targetSelected = true,
            ownerBound = true,
            safetyUnavailable = locationAvailable && targetSafety == null,
            targetWorld = destination.world,
        )
    }

    /** Reconciles online holders after an external localized message or guidance action. */
    fun guide(player: Player) {
        refreshHeldMap(player)
    }

    fun clear(playerId: UUID) {
        cancelPendingSearch(playerId)
        clearRenderState(playerId)
    }

    private fun clearRenderState(playerId: UUID) {
        renderStates.remove(playerId)
        marker.clear(playerId)
        markerFailures.remove(playerId)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) = clear(event.player.uniqueId)

    @EventHandler
    fun onWorldChange(event: PlayerChangedWorldEvent) {
        cancelPendingSearch(event.player.uniqueId)
        clear(event.player.uniqueId)
        taskScope.runLater(lifecycleToken, 1L) {
            plugin.server.getPlayer(event.player.uniqueId)?.let(::refreshHeldMap)
        }
    }

    @EventHandler
    fun onItemHeld(event: PlayerItemHeldEvent) {
        taskScope.runLater(lifecycleToken, 1L) {
            plugin.server.getPlayer(event.player.uniqueId)?.let(::refreshHeldMap)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        refreshTask?.cancel()
        refreshTask = null
        taskScope.close()
        pendingSearches.values.toList().forEach { it.timeout?.cancel() }
        pendingSearches.clear()
        org.bukkit.event.HandlerList.unregisterAll(this)
        renderStates.keys.toList().forEach(::clear)
        marker.close()
        mapViews.values.forEach { it.removeRenderer(renderer) }
        mapViews.clear()
    }

    private fun refreshOnlinePlayers() {
        if (!closed) plugin.server.onlinePlayers.toList().forEach(::refreshHeldMap)
    }

    private fun resolve(stack: ItemStack?): ResolvedMap? {
        val voucher = PhysicalRewardVoucher.identity(stack) ?: return null
        val identity = PersonalTreasureMapIdentity.read(stack) ?: return null
        val spec = resolveSpec(voucher.key)?.takeIf { it.fingerprint == voucher.fingerprint } ?: return null
        val definition = resolveDefinition(spec) ?: return null
        if (!matchesDefinition(identity, definition, voucher.id)) return null
        val destination = identity.target ?: if (definition.searchPolicy == null) {
            definition.destinations.getOrNull(identity.destinationIndex)
        } else null
        return ResolvedMap(voucher, identity, spec, definition, destination)
    }

    private fun tokenFailure(player: Player, resolved: ResolvedMap): PersonalTreasureMapFailure? {
        if (!matchesDefinition(resolved.identity, resolved.definition, resolved.voucher.id)) {
            return PersonalTreasureMapFailure.INVALID_OR_STALE
        }
        return if (resolved.identity.ownerId != player.uniqueId) PersonalTreasureMapFailure.WRONG_OWNER else null
    }

    private fun locationFailure(player: Player, destination: PersonalTreasureMapDestination): PersonalTreasureMapFailure? {
        if (currentServer() != destination.server) return PersonalTreasureMapFailure.WRONG_SERVER
        if (player.world.name != destination.world) return PersonalTreasureMapFailure.WRONG_WORLD
        return if (player.location.distanceSquared(destination.toLocation(player.world)) > CLAIM_DISTANCE_SQUARED) {
            PersonalTreasureMapFailure.TOO_FAR
        } else null
    }

    private fun matchesDefinition(
        identity: PersonalTreasureMapIdentity,
        definition: PersonalTreasureMapDefinition,
        voucherId: UUID,
    ): Boolean {
        val targetMatchesPolicy = identity.target?.let { target ->
            val policy = definition.searchPolicy
            val currentTarget = policy != null && target.server == policy.server && policy.acceptsWorld(target.world) &&
                policy.containsTarget(target.x, target.z)
            val legacy = definition.legacyTargetPolicy
            val legacyTarget = legacy != null && target.server == legacy.server && target.world == legacy.world
            currentTarget || legacyTarget
        } ?: true
        val currentPolicyFingerprint = definition.searchPolicy?.targetPolicyFingerprint?.sha256
        val legacyPolicyFingerprint = definition.legacyTargetPolicy?.targetPolicyFingerprint?.sha256
        val previousPolicy = previousSingleWorldPolicy(definition)
        val targetPolicyMarkerMatches = when (identity.targetPolicyFingerprint) {
            null -> true // v1-v3 maps predate the independent target-policy marker.
            currentPolicyFingerprint -> identity.target == null || targetMatchesCurrentPolicy(identity, definition)
            legacyPolicyFingerprint -> identity.target == null || targetMatchesLegacyPolicy(identity, definition)
            previousPolicy?.targetPolicyFingerprint?.sha256 -> identity.target == null ||
                (previousPolicy != null && identity.target.world == previousPolicy.world &&
                    targetMatchesCurrentPolicy(identity, definition))
            else -> false
        }
        return identity.voucherId == voucherId &&
            identity.definitionId == definition.id &&
            identity.definitionFingerprint == definition.fingerprint &&
            identity.destinationIndex == definition.destinationIndex(voucherId) &&
            targetMatchesPolicy && targetPolicyMarkerMatches
    }

    private fun targetMatchesCurrentPolicy(
        identity: PersonalTreasureMapIdentity,
        definition: PersonalTreasureMapDefinition,
    ): Boolean {
        val target = identity.target ?: return true
        val policy = definition.searchPolicy ?: return false
        return target.server == policy.server && policy.acceptsWorld(target.world) && policy.containsTarget(target.x, target.z)
    }

    private fun targetMatchesLegacyPolicy(
        identity: PersonalTreasureMapIdentity,
        definition: PersonalTreasureMapDefinition,
    ): Boolean {
        val target = identity.target ?: return true
        val legacy = definition.legacyTargetPolicy ?: return false
        return target.server == legacy.server && target.world == legacy.world
    }

    private fun previousSingleWorldPolicy(definition: PersonalTreasureMapDefinition): PersonalTreasureMapSearchPolicy? =
        definition.searchPolicy?.takeIf { it.additionalWorlds.isNotEmpty() }?.copy(additionalWorlds = emptySet())

    private fun hasLegacyTarget(resolved: ResolvedMap): Boolean {
        val target = resolved.identity.target ?: return false
        val previousPolicy = previousSingleWorldPolicy(resolved.definition)
        if (previousPolicy != null && target.world == previousPolicy.world &&
            resolved.identity.targetPolicyFingerprint == previousPolicy.targetPolicyFingerprint.sha256 &&
            targetMatchesCurrentPolicy(resolved.identity, resolved.definition)
        ) return false
        if (resolved.definition.preserveLegacyTarget && targetMatchesCurrentPolicy(resolved.identity, resolved.definition)) return false
        val legacy = resolved.definition.legacyTargetPolicy ?: return false
        val current = resolved.definition.searchPolicy ?: return false
        return target.server == legacy.server && target.world == legacy.world &&
            resolved.identity.targetPolicyFingerprint != current.targetPolicyFingerprint.sha256
    }

    private fun migrateLegacyTarget(
        player: Player,
        resolved: ResolvedMap,
        selectTarget: Boolean,
    ): Boolean {
        val mainHand = player.inventory.itemInMainHand
        if (PhysicalRewardVoucher.identity(mainHand) != resolved.voucher ||
            PersonalTreasureMapIdentity.read(mainHand) != resolved.identity ||
            resolved.identity.ownerId != player.uniqueId
        ) return false

        val migrated = mainHand.clone().apply {
            editMeta { meta ->
                writeIdentity(
                    meta.persistentDataContainer,
                    resolved.identity.copy(
                        target = null,
                        targetPolicyFingerprint = resolved.definition.searchPolicy?.targetPolicyFingerprint?.sha256,
                    ),
                )
            }
        }
        player.inventory.setItemInMainHand(migrated)
        val updated = resolve(migrated) ?: return false
        if (updated.voucher != resolved.voucher || tokenFailure(player, updated) != null) return false
        if (selectTarget) selectOrRefreshTarget(player, updated, invalidateExisting = false)
        refreshHeldMap(player)
        return true
    }

    private fun isSearchLocation(player: Player, policy: PersonalTreasureMapSearchPolicy): Boolean =
        currentServer() == policy.server && policy.acceptsWorld(player.world.name)

    /** Starts one bounded asynchronous probe; terrain and protection reads happen on the server thread. */
    private fun selectOrRefreshTarget(
        player: Player,
        resolved: ResolvedMap,
        invalidateExisting: Boolean,
    ): Boolean {
        val policy = resolved.definition.searchPolicy ?: return false
        if (!isSearchLocation(player, policy)) return false
        val held = player.inventory.itemInMainHand
        val currentVoucher = PhysicalRewardVoucher.identity(held)
        var currentIdentity = PersonalTreasureMapIdentity.read(held)
        if (currentVoucher != resolved.voucher || currentIdentity != resolved.identity) return false
        val currentMarker = policy.targetPolicyFingerprint.sha256
        if (currentIdentity.target != null && !invalidateExisting && currentIdentity.targetPolicyFingerprint == currentMarker) return true

        pendingSearches[player.uniqueId]?.let { pending ->
            if (pendingStillOwnsMap(player, pending)) return true
            cancelPendingSearch(player.uniqueId)
        }

        val normalizedIdentity = currentIdentity.copy(
            target = null,
            targetPolicyFingerprint = currentMarker,
        )
        if (normalizedIdentity != currentIdentity) {
            if (!replaceHeldIdentity(player, resolved.voucher, currentIdentity, normalizedIdentity)) return false
        }
        // decorateMap writes the per-view server/world/id into the map identity. Capture the exact
        // persisted identity after decoration so the asynchronous callback compares against the
        // item that is actually in hand (especially for migrated v3 maps without a view binding).
        val normalizedStack = player.inventory.itemInMainHand
        if (PhysicalRewardVoucher.identity(normalizedStack) != resolved.voucher) return false
        val normalizedHeldIdentity = PersonalTreasureMapIdentity.read(normalizedStack) ?: return false
        if (normalizedHeldIdentity.voucherId != resolved.voucher.id ||
            normalizedHeldIdentity.ownerId != player.uniqueId ||
            normalizedHeldIdentity.definitionId != resolved.definition.id ||
            normalizedHeldIdentity.definitionFingerprint != resolved.definition.fingerprint ||
            normalizedHeldIdentity.searchGeneration != currentIdentity.searchGeneration ||
            normalizedHeldIdentity.target != null ||
            normalizedHeldIdentity.targetPolicyFingerprint != currentMarker
        ) return false
        val decoratedStack = decorateMap(player, normalizedStack) ?: return false
        val latestStack = player.inventory.itemInMainHand
        if (PhysicalRewardVoucher.identity(latestStack) != resolved.voucher ||
            PersonalTreasureMapIdentity.read(latestStack) != normalizedHeldIdentity
        ) return false
        val decoratedIdentity = PersonalTreasureMapIdentity.read(decoratedStack) ?: return false
        if (decoratedStack.itemMeta != latestStack.itemMeta) player.inventory.setItemInMainHand(decoratedStack)
        currentIdentity = decoratedIdentity
        val origin = player.location
        val voucherId = resolved.voucher.id
        val candidates = policy.bounds?.let { bounds ->
            personalTreasureMapCandidateOrder(
                voucherId = voucherId,
                generation = currentIdentity.searchGeneration,
                bounds = bounds,
                minDistance = policy.minDistance,
                originX = origin.x,
                originZ = origin.z,
            )
        } ?: personalTreasureMapCandidateOrder(
            voucherId = voucherId,
            generation = currentIdentity.searchGeneration,
            centerX = floor(origin.x).toInt(),
            centerZ = floor(origin.z).toInt(),
            radius = requireNotNull(policy.radius),
            minDistance = policy.minDistance,
        )
        if (candidates.isEmpty()) {
            completeSearch(player, null, resolved.voucher, currentIdentity, policy)
            return false
        }

        val request = PendingSearch(
            operationId = nextSearchOperationId(),
            playerId = player.uniqueId,
            voucher = resolved.voucher,
            identity = currentIdentity,
            policy = policy,
            worldId = player.world.uid,
            originX = origin.x,
            originZ = origin.z,
            candidates = candidates,
            lifecycleToken = lifecycleToken,
        )
        pendingSearches[player.uniqueId] = request
        request.timeout = taskScope.runLater(lifecycleToken, SEARCH_TIMEOUT_TICKS) {
            if (pendingSearches[player.uniqueId]?.operationId == request.operationId) {
                val online = plugin.server.getPlayer(player.uniqueId)
                if (online != null && pendingStillOwnsMap(online, request)) {
                    completeSearch(online, null, request.voucher, request.identity, request.policy, request)
                } else {
                    cancelPendingSearch(player.uniqueId, request)
                }
            }
        }
        if (request.timeout == null) {
            cancelPendingSearch(player.uniqueId)
            return false
        }
        probeCandidate(request, 0)
        return true
    }

    private fun probeCandidate(request: PendingSearch, startIndex: Int) {
        val player = plugin.server.getPlayer(request.playerId)
        if (player == null || !pendingStillOwnsMap(player, request)) {
            cancelPendingSearch(request.playerId, request)
            return
        }
        val world = plugin.server.getWorld(request.worldId)
        if (world == null || !request.policy.acceptsWorld(world.name)) {
            completeSearch(player, null, request.voucher, request.identity, request.policy, request)
            return
        }
        var index = startIndex
        while (index < request.candidates.size) {
            val (x, z) = request.candidates[index]
            val candidateIndex = index
            index++
            if (!world.worldBorder.isInside(Location(world, x + 0.5, world.minHeight.toDouble(), z + 0.5))) continue
            val future = runCatching { asyncChunkLoader(world, x shr 4, z shr 4) }.getOrNull() ?: continue
            future.whenCompleteSync(taskScope, request.lifecycleToken) { chunk, failure ->
                val online = plugin.server.getPlayer(request.playerId)
                if (online == null || !pendingStillOwnsMap(online, request)) {
                    cancelPendingSearch(request.playerId, request)
                    return@whenCompleteSync
                }
                val activeWorld = plugin.server.getWorld(request.worldId)
                if (activeWorld == null || !request.policy.acceptsWorld(activeWorld.name)) {
                    cancelPendingSearch(request.playerId, request)
                    return@whenCompleteSync
                }
                val chunkX = x shr 4
                val chunkZ = z shr 4
                if (failure != null || chunk == null || chunk.world.uid != request.worldId ||
                    chunk.x != chunkX || chunk.z != chunkZ || !activeWorld.isChunkLoaded(chunkX, chunkZ)
                ) {
                    probeCandidate(request, candidateIndex + 1)
                    return@whenCompleteSync
                }
                val target = findSafeTargetInLoadedChunk(activeWorld, x, z, request.policy)
                if (target != null) completeSearch(online, target, request.voucher, request.identity, request.policy, request)
                else probeCandidate(request, candidateIndex + 1)
            }
            return
        }
        completeSearch(player, null, request.voucher, request.identity, request.policy, request)
    }

    private fun findSafeTargetInLoadedChunk(
        world: World,
        x: Int,
        z: Int,
        policy: PersonalTreasureMapSearchPolicy,
    ): PersonalTreasureMapDestination? {
        val chunkX = x shr 4
        val chunkZ = z shr 4
        if (!world.isChunkLoaded(chunkX, chunkZ) || !policy.containsTarget(x + 0.5, z + 0.5)) return null
        val highestY = world.getHighestBlockYAt(x, z)
        val lowestY = (highestY - SURFACE_LOOKBACK_BLOCKS).coerceAtLeast(world.minHeight)
        for (groundY in highestY downTo lowestY) {
            if (!isSafeGround(world.getBlockAt(x, groundY, z).type)) continue
            val targetY = groundY + 1
            if (targetY + 1 >= world.maxHeight) continue
            if (!isSafeAir(world.getBlockAt(x, targetY, z)) || !isSafeAir(world.getBlockAt(x, targetY + 1, z))) continue
            val location = Location(world, x + 0.5, targetY.toDouble(), z + 0.5)
            if (!world.worldBorder.isInside(location) || isUnclaimedColumn(world, x, groundY, z) != true) continue
            return PersonalTreasureMapDestination(
                policy.server,
                world.name,
                location.x,
                location.y,
                location.z,
                PersonalTreasureMapSearchPolicy.TARGET_HINT,
            )
        }
        return null
    }

    private fun pendingStillOwnsMap(player: Player, request: PendingSearch): Boolean {
        if (closed || !player.isOnline || player.uniqueId != request.playerId ||
            pendingSearches[request.playerId]?.operationId != request.operationId ||
            !taskScope.isCurrent(request.lifecycleToken) || currentServer() != request.policy.server ||
            player.world.uid != request.worldId || !request.policy.acceptsWorld(player.world.name)
        ) return false
        val held = player.inventory.itemInMainHand
        return PhysicalRewardVoucher.identity(held) == request.voucher &&
            PersonalTreasureMapIdentity.read(held) == request.identity &&
            request.identity.ownerId == player.uniqueId &&
            request.identity.searchGeneration >= 0 &&
            request.identity.targetPolicyFingerprint == request.policy.targetPolicyFingerprint.sha256
    }

    private fun completeSearch(
        player: Player,
        target: PersonalTreasureMapDestination?,
        voucher: PhysicalRewardVoucherIdentity,
        identity: PersonalTreasureMapIdentity,
        policy: PersonalTreasureMapSearchPolicy,
        request: PendingSearch? = null,
    ) {
        if (!player.isOnline || closed || currentServer() != policy.server ||
            !policy.acceptsWorld(player.world.name) ||
            (request != null && !pendingStillOwnsMap(player, request))
        ) {
            request?.let { cancelPendingSearch(it.playerId, it) }
            return
        }
        val mainHand = player.inventory.itemInMainHand
        if (PhysicalRewardVoucher.identity(mainHand) != voucher ||
            PersonalTreasureMapIdentity.read(mainHand) != identity ||
            identity.ownerId != player.uniqueId ||
            identity.targetPolicyFingerprint != policy.targetPolicyFingerprint.sha256
        ) {
            request?.let { cancelPendingSearch(it.playerId, it) }
            return
        }
        val updatedIdentity = identity.copy(
            searchGeneration = nextSearchGeneration(identity.searchGeneration),
            target = target,
            targetPolicyFingerprint = policy.targetPolicyFingerprint.sha256,
        )
        val updated = mainHand.clone().apply {
            editMeta { meta -> writeIdentity(meta.persistentDataContainer, updatedIdentity) }
        }
        val decorated = decorateMap(player, updated) ?: run {
            request?.let { cancelPendingSearch(it.playerId, it) }
            return
        }
        val latest = player.inventory.itemInMainHand
        if (PhysicalRewardVoucher.identity(latest) != voucher ||
            PersonalTreasureMapIdentity.read(latest) != identity ||
            request?.let { pendingSearches[it.playerId]?.operationId != it.operationId } == true
        ) {
            request?.let { cancelPendingSearch(it.playerId, it) }
            return
        }
        player.inventory.setItemInMainHand(decorated)
        if (request != null) {
            pendingSearches.remove(request.playerId, request)
            request.timeout?.cancel()
            request.timeout = null
        }
        if (target == null) clearRenderState(player.uniqueId) else refreshHeldMap(player)
    }

    private fun replaceHeldIdentity(
        player: Player,
        voucher: PhysicalRewardVoucherIdentity,
        expected: PersonalTreasureMapIdentity,
        updatedIdentity: PersonalTreasureMapIdentity,
    ): Boolean {
        val held = player.inventory.itemInMainHand
        if (PhysicalRewardVoucher.identity(held) != voucher ||
            PersonalTreasureMapIdentity.read(held) != expected
        ) return false
        val updated = held.clone().apply {
            editMeta { meta -> writeIdentity(meta.persistentDataContainer, updatedIdentity) }
        }
        val decorated = decorateMap(player, updated) ?: return false
        val latest = player.inventory.itemInMainHand
        if (PhysicalRewardVoucher.identity(latest) != voucher ||
            PersonalTreasureMapIdentity.read(latest) != expected
        ) return false
        player.inventory.setItemInMainHand(decorated)
        return true
    }

    private fun cancelPendingSearch(playerId: UUID, expected: PendingSearch? = null) {
        val request = pendingSearches[playerId] ?: return
        if (expected != null && request.operationId != expected.operationId) return
        if (pendingSearches.remove(playerId, request)) {
            request.timeout?.cancel()
            request.timeout = null
        }
    }

    private fun nextSearchOperationId(): Long {
        nextSearchOperationId = if (nextSearchOperationId == Long.MAX_VALUE) 1L else nextSearchOperationId + 1L
        return nextSearchOperationId
    }

    private fun nextSearchGeneration(current: Int): Int = if (current == Int.MAX_VALUE) current else current + 1

    private fun isSafeTarget(
        player: Player,
        destination: PersonalTreasureMapDestination,
        policy: PersonalTreasureMapSearchPolicy?,
    ): Boolean? {
        if (policy != null && (destination.server != policy.server || !policy.acceptsWorld(destination.world) ||
                !policy.containsTarget(destination.x, destination.z))
        ) return false
        if (policy != null && !isSearchLocation(player, policy)) return null
        val world = player.world
        if (currentServer() != destination.server || world.name != destination.world) return false
        val x = floor(destination.x).toInt()
        val y = floor(destination.y).toInt()
        val z = floor(destination.z).toInt()
        if (!world.isChunkLoaded(x shr 4, z shr 4)) return null
        if (!world.worldBorder.isInside(destination.toLocation(world))) return false
        val groundY = y - 1
        if (groundY < world.minHeight || y + 1 >= world.maxHeight) return false
        if (!isSafeGround(world.getBlockAt(x, groundY, z).type) ||
            !isSafeAir(world.getBlockAt(x, y, z)) || !isSafeAir(world.getBlockAt(x, y + 1, z))
        ) return false
        return isUnclaimedColumn(world, x, groundY, z)
    }

    private fun isUnclaimedColumn(world: org.bukkit.World, x: Int, groundY: Int, z: Int): Boolean? {
        // Lands areas are horizontal; WorldGuard regions can be vertical, so test the ground and both standing blocks.
        var unknown = false
        for (y in groundY..groundY + 2) {
            when (isUnclaimed(Location(world, x + 0.5, y.toDouble(), z + 0.5))) {
                false -> return false
                null -> unknown = true
                true -> Unit
            }
        }
        return if (unknown) null else true
    }

    private fun isSafeGround(material: Material): Boolean =
        material.isSolid && !material.isAir && !Tag.LEAVES.isTagged(material) && material !in UNSAFE_MATERIALS

    private fun isSafeAir(block: org.bukkit.block.Block): Boolean =
        block.isPassable && !block.isLiquid && block.type !in UNSAFE_MATERIALS

    private fun ensureMapView(player: Player, stack: ItemStack): MapView? {
        val identity = PersonalTreasureMapIdentity.read(stack) ?: return null
        val server = currentServer()
        val worldId = player.world.uid
        val worldKey = worldId.toString()
        val viewKey = MapViewKey(server, worldId)
        var view = mapViews[viewKey]
        if (view == null && identity.mapViewServer == server && identity.mapViewWorld == worldKey && identity.mapViewId != null) {
            view = Bukkit.getMap(identity.mapViewId)
        }
        if (view == null) view = runCatching { mapViewFactory(player.world) }.getOrElse { failure ->
            plugin.logger.warning("Personal treasure map view creation failed: ${failure.javaClass.simpleName}")
            return null
        }
        if (view.getRenderers().none { it === renderer }) {
            view.getRenderers().toList().forEach(view::removeRenderer)
            view.setScale(MapView.Scale.CLOSEST)
            view.setTrackingPosition(false)
            view.setUnlimitedTracking(false)
            view.setLocked(true)
            view.addRenderer(renderer)
        }
        mapViews[viewKey] = view
        return view
    }

    private fun writeIdentity(data: org.bukkit.persistence.PersistentDataContainer, identity: PersonalTreasureMapIdentity) {
        data.set(PersonalTreasureMapIdentity.versionKey, PersistentDataType.STRING, PersonalTreasureMapIdentity.VERSION)
        data.set(PersonalTreasureMapIdentity.ownerKey, PersistentDataType.STRING, identity.ownerId.toString())
        data.set(PersonalTreasureMapIdentity.definitionKey, PersistentDataType.STRING, identity.definitionId)
        data.set(PersonalTreasureMapIdentity.fingerprintKey, PersistentDataType.STRING, identity.definitionFingerprint.sha256)
        data.set(PersonalTreasureMapIdentity.destinationIndexKey, PersistentDataType.INTEGER, identity.destinationIndex)
        data.set(PersonalTreasureMapIdentity.searchGenerationKey, PersistentDataType.INTEGER, identity.searchGeneration)
        identity.targetPolicyFingerprint?.let {
            data.set(PersonalTreasureMapIdentity.targetPolicyFingerprintKey, PersistentDataType.STRING, it)
        } ?: data.remove(PersonalTreasureMapIdentity.targetPolicyFingerprintKey)
        val target = identity.target
        if (target == null) {
            listOf(
                PersonalTreasureMapIdentity.targetServerKey,
                PersonalTreasureMapIdentity.targetWorldKey,
                PersonalTreasureMapIdentity.targetXKey,
                PersonalTreasureMapIdentity.targetYKey,
                PersonalTreasureMapIdentity.targetZKey,
            ).forEach(data::remove)
        } else {
            data.set(PersonalTreasureMapIdentity.targetServerKey, PersistentDataType.STRING, target.server)
            data.set(PersonalTreasureMapIdentity.targetWorldKey, PersistentDataType.STRING, target.world)
            data.set(PersonalTreasureMapIdentity.targetXKey, PersistentDataType.DOUBLE, target.x)
            data.set(PersonalTreasureMapIdentity.targetYKey, PersistentDataType.DOUBLE, target.y)
            data.set(PersonalTreasureMapIdentity.targetZKey, PersistentDataType.DOUBLE, target.z)
        }
    }

    private fun hasPersonalMapMarker(stack: ItemStack?): Boolean {
        val data = stack?.itemMeta?.persistentDataContainer ?: return false
        return listOf(
            PersonalTreasureMapIdentity.versionKey,
            PersonalTreasureMapIdentity.ownerKey,
            PersonalTreasureMapIdentity.definitionKey,
            PersonalTreasureMapIdentity.fingerprintKey,
            PersonalTreasureMapIdentity.destinationIndexKey,
            PersonalTreasureMapIdentity.mapViewServerKey,
            PersonalTreasureMapIdentity.mapViewIdKey,
            PersonalTreasureMapIdentity.mapViewWorldKey,
            PersonalTreasureMapIdentity.searchGenerationKey,
            PersonalTreasureMapIdentity.targetPolicyFingerprintKey,
            PersonalTreasureMapIdentity.targetServerKey,
            PersonalTreasureMapIdentity.targetWorldKey,
            PersonalTreasureMapIdentity.targetXKey,
            PersonalTreasureMapIdentity.targetYKey,
            PersonalTreasureMapIdentity.targetZKey,
        ).any { data.has(it) }
    }

    private fun PersonalTreasureMapDestination.toLocation(world: org.bukkit.World): Location =
        Location(world, x, y, z)

    companion object {
        private const val CLAIM_DISTANCE_SQUARED = 9.0
        private const val MARKER_DISTANCE_SQUARED = 64.0 * 64.0
        private const val SURFACE_LOOKBACK_BLOCKS = 4
        private const val SEARCH_TIMEOUT_TICKS = 20L * 30L
        private val UNSAFE_MATERIALS = setOf(
            Material.CACTUS,
            Material.CAMPFIRE,
            Material.FIRE,
            Material.LAVA,
            Material.MAGMA_BLOCK,
            Material.POWDER_SNOW,
            Material.SOUL_CAMPFIRE,
            Material.SOUL_FIRE,
            Material.SWEET_BERRY_BUSH,
            Material.WITHER_ROSE,
        )
    }
}

/** Pure, reproducible location ordering. It never reads Bukkit world state. */
internal fun personalTreasureMapCandidateOrder(
    voucherId: UUID,
    generation: Int,
    centerX: Int,
    centerZ: Int,
    radius: Int,
    count: Int = PersonalTreasureMapSearchPolicy.CANDIDATE_LIMIT,
    minDistance: Int = PersonalTreasureMapSearchPolicy.LEGACY_MIN_TARGET_DISTANCE,
): List<Pair<Int, Int>> {
    require(radius in PersonalTreasureMapSearchPolicy.MIN_RADIUS..PersonalTreasureMapSearchPolicy.MAX_RADIUS)
    require(count in 1..PersonalTreasureMapSearchPolicy.CANDIDATE_LIMIT)
    require(minDistance in 1..radius)
    val seed = voucherId.mostSignificantBits xor java.lang.Long.rotateLeft(voucherId.leastSignificantBits, 17) xor
        (generation.toLong() shl 32) xor (centerX.toLong() shl 16) xor centerZ.toLong()
    val random = Random(seed)
    val minimumSquared = minDistance.toDouble().let { it * it }
    val radiusSquared = radius.toDouble() * radius
    return buildList(count) {
        repeat(count * 64) {
            if (size >= count) return@repeat
            val angle = random.nextDouble() * Math.PI * 2.0
            val distance = sqrt(minimumSquared + random.nextDouble() * (radiusSquared - minimumSquared))
            val dx = (kotlin.math.cos(angle) * distance).roundToInt()
            val dz = (kotlin.math.sin(angle) * distance).roundToInt()
            val squaredDistance = dx.toDouble() * dx + dz.toDouble() * dz
            val point = centerX + dx to centerZ + dz
            if (squaredDistance in minimumSquared..radiusSquared && point !in this) add(point)
        }
    }
}

/** Pure deterministic global search ordering; coordinates are block centers inside the configured border. */
internal fun personalTreasureMapCandidateOrder(
    voucherId: UUID,
    generation: Int,
    bounds: PersonalTreasureMapBounds,
    minDistance: Int,
    originX: Double,
    originZ: Double,
    count: Int = PersonalTreasureMapSearchPolicy.CANDIDATE_LIMIT,
): List<Pair<Int, Int>> {
    require(minDistance in 1..PersonalTreasureMapSearchPolicy.MAX_MIN_DISTANCE)
    require(originX.isFinite() && originZ.isFinite())
    require(count in 1..PersonalTreasureMapSearchPolicy.CANDIDATE_LIMIT)
    val seed = voucherId.mostSignificantBits xor java.lang.Long.rotateLeft(voucherId.leastSignificantBits, 17) xor
        (generation.toLong() shl 32) xor 0x6D61702D657870L
    val random = Random(seed)
    val minimumSquared = minDistance.toDouble() * minDistance
    return buildList(count) {
        val maxAttempts = count * 256
        repeat(maxAttempts) {
            if (size >= count) return@repeat
            val x = random.nextInt(bounds.minX, bounds.maxX)
            val z = random.nextInt(bounds.minZ, bounds.maxZ)
            val distanceSquared = (x + 0.5 - originX) * (x + 0.5 - originX) +
                (z + 0.5 - originZ) * (z + 0.5 - originZ)
            val candidate = x to z
            if (distanceSquared >= minimumSquared && candidate !in this) add(candidate)
        }
    }
}

internal interface PersonalTreasureMapMarker : AutoCloseable {
    fun show(player: Player, destination: PersonalTreasureMapDestination)
    fun clear(playerId: UUID)
}

private class PersonalTreasureMapRenderer(
    private val renderStates: Map<UUID, PersonalTreasureMapRenderState>,
) : MapRenderer(true) {
    private val drawnCanvases = Collections.newSetFromMap(WeakHashMap<MapCanvas, Boolean>())

    override fun render(map: MapView, canvas: MapCanvas, player: Player) {
        synchronized(drawnCanvases) {
            if (drawnCanvases.add(canvas)) canvas.drawImage(0, 0, PARCHMENT)
        }
        val cursors = MapCursorCollection()
        renderStates[player.uniqueId]?.takeIf { it.ownerId == player.uniqueId }?.let { state ->
            cursors.addCursor(MapCursor(0, 0, 0, MapCursor.Type.RED_X, true, Component.text(state.destination.hint)))
            val playerX = state.playerX
            val playerZ = state.playerZ
            if (playerX != null && playerZ != null) {
                val deltaX = playerX - state.destination.x
                val deltaZ = playerZ - state.destination.z
                val (cursorX, cursorY) = personalTreasureMapPlayerCursorOffset(
                    deltaX,
                    deltaZ,
                )
                val type = if (abs(deltaX) > 63.0 || abs(deltaZ) > 63.0) MapCursor.Type.PLAYER_OFF_MAP else MapCursor.Type.PLAYER
                val direction = Math.floorMod((state.yaw / 22.5f).roundToInt(), 16).toByte()
                cursors.addCursor(MapCursor(cursorX, cursorY, direction, type, true, Component.text("Вы")))
            }
        }
        canvas.cursors = cursors
    }

    companion object {
        private val PARCHMENT = BufferedImage(128, 128, BufferedImage.TYPE_INT_RGB).apply {
            val graphics: Graphics2D = createGraphics()
            try {
                graphics.color = Color(224, 210, 171)
                graphics.fillRect(0, 0, 128, 128)
                graphics.color = Color(166, 142, 99)
                graphics.drawRect(3, 3, 121, 121)
                graphics.drawRect(6, 6, 115, 115)
                graphics.color = Color(203, 185, 143)
                for (line in 0..7) graphics.drawLine(10, 16 + line * 13, 117, 16 + line * 13)
                graphics.color = Color(174, 153, 111)
                graphics.drawOval(48, 48, 31, 31)
            } finally {
                graphics.dispose()
            }
        }

    }
}

/** Map north is up (negative Z), so players south of the X render lower on the parchment. */
internal fun personalTreasureMapPlayerCursorOffset(deltaX: Double, deltaZ: Double): Pair<Byte, Byte> =
    (deltaX * 2.0).roundToInt().coerceIn(-127, 127).toByte() to
        (deltaZ * 2.0).roundToInt().coerceIn(-127, 127).toByte()
