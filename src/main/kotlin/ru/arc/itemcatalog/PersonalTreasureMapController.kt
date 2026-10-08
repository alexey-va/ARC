package ru.arc.itemcatalog

import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Tag
import org.bukkit.entity.Display
import org.bukkit.entity.ItemDisplay
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
import org.joml.Quaternionf
import org.joml.Vector3f
import org.bukkit.util.Transformation
import ru.arc.ARC
import ru.arc.core.ScheduledTask
import ru.arc.core.TaskScheduler
import ru.arc.core.Tasks
import ru.arc.hooks.HookRegistry
import ru.arc.onetime.OneTimeUseFingerprint
import ru.arc.paper.display.PacketItemDisplay
import ru.arc.paper.display.PaperPacketDisplays
import java.awt.Color
import java.awt.Graphics2D
import java.awt.image.BufferedImage
import java.util.Collections
import java.util.UUID
import java.util.WeakHashMap
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

    private val renderStates = ConcurrentHashMap<UUID, PersonalTreasureMapRenderState>()
    private val renderer = PersonalTreasureMapRenderer(renderStates)
    private val markerFailures = mutableSetOf<UUID>()
    private val mapViews = mutableMapOf<MapViewKey, MapView>()
    private var closed = false
    private var refreshTask: ScheduledTask? = scheduler.runTimer(20L, 20L) { refreshOnlinePlayers() }

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

    /** Refreshes one holder's map cursor and nearby marker without loading chunks or reading blocks. */
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
            clear(player.uniqueId)
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
        if (closeEnoughToSeeMarker) {
            try {
                marker.show(player, destination)
                markerFailures.remove(player.uniqueId)
            } catch (failure: Throwable) {
                marker.clear(player.uniqueId)
                if (markerFailures.add(player.uniqueId)) {
                    plugin.logger.warning("Personal treasure marker failed for ${player.uniqueId}: ${failure.javaClass.simpleName}")
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
        val targetWorld = destination?.world ?: policy?.world ?: return null
        val hint = destination?.hint ?: PersonalTreasureMapSearchPolicy.TARGET_HINT
        val onServer = currentServer() == targetServer
        val onWorld = onServer && player.world.name == targetWorld
        if (destination == null) {
            return PersonalTreasureMapGuidance(
                hint, null, null, onServer, onWorld, false, false, ownerBound = resolved != null,
            )
        }
        if (!onWorld) {
            return PersonalTreasureMapGuidance(destination.hint, null, null, onServer, false, false, true)
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
        )
    }

    /** Reconciles online holders after an external localized message or guidance action. */
    fun guide(player: Player) {
        refreshHeldMap(player)
    }

    fun clear(playerId: UUID) {
        renderStates.remove(playerId)
        marker.clear(playerId)
        markerFailures.remove(playerId)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) = clear(event.player.uniqueId)

    @EventHandler
    fun onWorldChange(event: PlayerChangedWorldEvent) {
        clear(event.player.uniqueId)
        scheduler.runLater(1L) {
            plugin.server.getPlayer(event.player.uniqueId)?.let(::refreshHeldMap)
        }
    }

    @EventHandler
    fun onItemHeld(event: PlayerItemHeldEvent) {
        scheduler.runLater(1L) {
            plugin.server.getPlayer(event.player.uniqueId)?.let(::refreshHeldMap)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        refreshTask?.cancel()
        refreshTask = null
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
            (policy != null && target.server == policy.server && target.world == policy.world) ||
                definition.legacyTargetPolicy?.let { legacy ->
                    target.server == legacy.server && target.world == legacy.world
                } == true
        } ?: true
        return identity.voucherId == voucherId &&
            identity.definitionId == definition.id &&
            identity.definitionFingerprint == definition.fingerprint &&
            identity.destinationIndex == definition.destinationIndex(voucherId) &&
            targetMatchesPolicy
    }

    private fun hasLegacyTarget(resolved: ResolvedMap): Boolean {
        val target = resolved.identity.target ?: return false
        val legacy = resolved.definition.legacyTargetPolicy ?: return false
        return target.server == legacy.server && target.world == legacy.world
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
                writeIdentity(meta.persistentDataContainer, resolved.identity.copy(target = null))
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
        currentServer() == policy.server && player.world.name == policy.world

    /** Searches a fixed number of points in loaded chunks only; each click is one bounded server-thread slice. */
    private fun selectOrRefreshTarget(
        player: Player,
        resolved: ResolvedMap,
        invalidateExisting: Boolean,
    ): Boolean {
        val policy = resolved.definition.searchPolicy ?: return false
        if (!isSearchLocation(player, policy)) return false
        val held = player.inventory.itemInMainHand
        val currentVoucher = PhysicalRewardVoucher.identity(held)
        val currentIdentity = PersonalTreasureMapIdentity.read(held)
        if (currentVoucher != resolved.voucher || currentIdentity != resolved.identity) return false
        if (currentIdentity.target != null && !invalidateExisting) return true

        val generation = currentIdentity.searchGeneration
        val target = findSafeTarget(player, policy, generation)
        val updatedIdentity = currentIdentity.copy(
            searchGeneration = if (generation == Int.MAX_VALUE) generation else generation + 1,
            target = target,
        )
        val updated = held.clone().apply {
            editMeta { meta -> writeIdentity(meta.persistentDataContainer, updatedIdentity) }
        }
        val decorated = decorateMap(player, updated) ?: return false
        val stillHeld = player.inventory.itemInMainHand
        if (PhysicalRewardVoucher.identity(stillHeld) != resolved.voucher) return false
        val latestIdentity = PersonalTreasureMapIdentity.read(stillHeld) ?: return false
        if (latestIdentity.ownerId != currentIdentity.ownerId ||
            latestIdentity.definitionFingerprint != currentIdentity.definitionFingerprint ||
            latestIdentity.searchGeneration != currentIdentity.searchGeneration
        ) return false
        player.inventory.setItemInMainHand(decorated)
        if (target == null) clear(player.uniqueId)
        return target != null
    }

    private fun findSafeTarget(
        player: Player,
        policy: PersonalTreasureMapSearchPolicy,
        generation: Int,
    ): PersonalTreasureMapDestination? {
        val world = player.world
        if (world.name != policy.world || currentServer() != policy.server) return null
        val playerLocation = player.location
        val centerX = floor(playerLocation.x).toInt()
        val centerZ = floor(playerLocation.z).toInt()
        val voucherId = PhysicalRewardVoucher.identity(player.inventory.itemInMainHand)?.id ?: return null
        val candidates = personalTreasureMapCandidateOrder(
            voucherId = voucherId,
            generation = generation,
            centerX = centerX,
            centerZ = centerZ,
            radius = policy.radius,
        )
        for ((x, z) in candidates) {
            val exactDistanceSquared = (x + 0.5 - playerLocation.x) * (x + 0.5 - playerLocation.x) +
                (z + 0.5 - playerLocation.z) * (z + 0.5 - playerLocation.z)
            if (exactDistanceSquared < PersonalTreasureMapSearchPolicy.MIN_TARGET_DISTANCE.toDouble().let { it * it } ||
                exactDistanceSquared > policy.radius.toDouble() * policy.radius
            ) continue
            if (!world.isChunkLoaded(x shr 4, z shr 4)) continue
            val highestY = world.getHighestBlockYAt(x, z)
            val lowestY = (highestY - SURFACE_LOOKBACK_BLOCKS).coerceAtLeast(world.minHeight)
            for (groundY in highestY downTo lowestY) {
                if (!isSafeGround(world.getBlockAt(x, groundY, z).type)) continue
                val targetY = groundY + 1
                if (targetY + 1 >= world.maxHeight) continue
                if (!isSafeAir(world.getBlockAt(x, targetY, z)) ||
                    !isSafeAir(world.getBlockAt(x, targetY + 1, z))
                ) continue
                val location = Location(world, x + 0.5, targetY.toDouble(), z + 0.5)
                if (!world.worldBorder.isInside(location) || isUnclaimedColumn(world, x, groundY, z) != true) continue
                return PersonalTreasureMapDestination(
                    policy.server,
                    policy.world,
                    location.x,
                    location.y,
                    location.z,
                    PersonalTreasureMapSearchPolicy.TARGET_HINT,
                )
            }
        }
        return null
    }

    private fun isSafeTarget(
        player: Player,
        destination: PersonalTreasureMapDestination,
        policy: PersonalTreasureMapSearchPolicy?,
    ): Boolean? {
        if (policy != null && (destination.server != policy.server || destination.world != policy.world)) return false
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
): List<Pair<Int, Int>> {
    require(radius in PersonalTreasureMapSearchPolicy.MIN_RADIUS..PersonalTreasureMapSearchPolicy.MAX_RADIUS)
    require(count in 1..PersonalTreasureMapSearchPolicy.CANDIDATE_LIMIT)
    val seed = voucherId.mostSignificantBits xor java.lang.Long.rotateLeft(voucherId.leastSignificantBits, 17) xor
        (generation.toLong() shl 32) xor (centerX.toLong() shl 16) xor centerZ.toLong()
    val random = Random(seed)
    val minimumSquared = PersonalTreasureMapSearchPolicy.MIN_TARGET_DISTANCE.toDouble().let { it * it }
    val radiusSquared = radius.toDouble() * radius
    return buildList(count) {
        repeat(count * 64) {
            if (size >= count) return@repeat
            val angle = random.nextDouble() * Math.PI * 2.0
            val distance = sqrt(minimumSquared + random.nextDouble() * (radiusSquared - minimumSquared))
            val dx = (kotlin.math.cos(angle) * distance).roundToInt()
            val dz = (kotlin.math.sin(angle) * distance).roundToInt()
            val squaredDistance = dx.toDouble() * dx + dz.toDouble() * dz
            if (squaredDistance in minimumSquared..radiusSquared) add(centerX + dx to centerZ + dz)
        }
    }
}

internal interface PersonalTreasureMapMarker : AutoCloseable {
    fun show(player: Player, destination: PersonalTreasureMapDestination)
    fun clear(playerId: UUID)
}

private class PacketPersonalTreasureMapMarker(plugin: Plugin) : PersonalTreasureMapMarker {
    private data class MarkerKey(val worldId: UUID, val x: Double, val y: Double, val z: Double)

    private val displays = PaperPacketDisplays(plugin, "personal-treasure-map")
    private val markers = mutableMapOf<UUID, Pair<MarkerKey, PacketItemDisplay>>()

    override fun show(player: Player, destination: PersonalTreasureMapDestination) {
        val key = MarkerKey(player.world.uid, destination.x, destination.y, destination.z)
        if (markers[player.uniqueId]?.first == key) return
        clear(player.uniqueId)
        var display: PacketItemDisplay? = null
        try {
            display = displays.spawnItem(
                Location(player.world, destination.x, destination.y + 0.7, destination.z),
                ItemStack(Material.CHEST),
            ).apply {
                isVisibleByDefault = false
                billboard = Display.Billboard.CENTER
                viewRange = 0.5f
                displayWidth = 1f
                displayHeight = 1f
                itemDisplayTransform = ItemDisplay.ItemDisplayTransform.GROUND
                transformation = Transformation(
                    Vector3f(0f, 0f, 0f), Quaternionf(),
                    Vector3f(0.8f, 0.8f, 0.8f), Quaternionf(),
                )
                showTo(player)
            }
            markers[player.uniqueId] = key to display
        } catch (failure: Throwable) {
            display?.remove()
            throw failure
        }
    }

    override fun clear(playerId: UUID) {
        markers.remove(playerId)?.second?.remove()
    }

    override fun close() {
        markers.keys.toList().forEach(::clear)
        displays.close()
    }
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
