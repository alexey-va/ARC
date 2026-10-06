package ru.arc.itemcatalog

import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
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
) : Listener, AutoCloseable {
    private data class ResolvedMap(
        val voucher: PhysicalRewardVoucherIdentity,
        val identity: PersonalTreasureMapIdentity,
        val spec: PhysicalRewardSpec,
        val definition: PersonalTreasureMapDefinition,
        val destination: PersonalTreasureMapDestination,
    )

    private val renderStates = ConcurrentHashMap<UUID, PersonalTreasureMapRenderState>()
    private val renderer = PersonalTreasureMapRenderer(renderStates)
    private val markerFailures = mutableSetOf<UUID>()
    private var mapView: MapView? = null
    private var mapViewServer: String? = null
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
        )
        val bound = stack.clone()
        bound.editMeta { meta -> writeIdentity(meta.persistentDataContainer, identity) }
        return decorateMap(player, bound)
    }

    /** Rebinds a bound map to this runtime's view, including after a node transfer or plugin reload. */
    fun decorateMap(player: Player, stack: ItemStack): ItemStack? {
        if (closed || resolve(stack) == null) return null
        val copy = stack.clone()
        val view = ensureMapView(player, copy) ?: return null
        copy.editMeta { meta ->
            val mapMeta = meta as? MapMeta ?: return@editMeta
            mapMeta.isScaling = false
            mapMeta.mapView = view
            val data = mapMeta.persistentDataContainer
            data.set(PersonalTreasureMapIdentity.mapViewServerKey, PersistentDataType.STRING, currentServer())
            data.set(PersonalTreasureMapIdentity.mapViewIdKey, PersistentDataType.INTEGER, view.id)
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
            refreshHeldMap(player)
            return PersonalTreasureMapUseDecision.OPEN_MAP
        }
        val resolved = resolve(stack) ?: return PersonalTreasureMapUseDecision.REJECT
        if (resolved.voucher != voucher || tokenFailure(player, resolved) != null) {
            return PersonalTreasureMapUseDecision.REJECT
        }
        refreshHeldMap(player)
        return if (locationFailure(player, resolved.destination) == null) {
            PersonalTreasureMapUseDecision.CLAIM
        } else {
            PersonalTreasureMapUseDecision.OPEN_MAP
        }
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
        return tokenFailure(player, resolved) ?: locationFailure(player, resolved.destination)
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
        val resolved = resolve(player.inventory.itemInMainHand) ?: return null
        if (tokenFailure(player, resolved) != null) return null
        val destination = resolved.destination
        val onServer = currentServer() == destination.server
        val onWorld = onServer && player.world.name == destination.world
        if (!onWorld) {
            return PersonalTreasureMapGuidance(destination.hint, null, null, onServer, false, false)
        }
        val location = player.location
        val dx = destination.x - location.x
        val dz = destination.z - location.z
        return PersonalTreasureMapGuidance(
            hint = destination.hint,
            distance = sqrt(dx * dx + (destination.y - location.y) * (destination.y - location.y) + dz * dz),
            bearingDegrees = Math.toDegrees(atan2(dx, -dz)).let { (it + 360.0) % 360.0 },
            onDestinationServer = true,
            onDestinationWorld = true,
            withinClaimRadius = locationFailure(player, destination) == null,
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
        mapView?.removeRenderer(renderer)
        mapView = null
        mapViewServer = null
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
        return ResolvedMap(voucher, identity, spec, definition, definition.destinations[identity.destinationIndex])
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
    ): Boolean = identity.voucherId == voucherId &&
        identity.definitionId == definition.id &&
        identity.definitionFingerprint == definition.fingerprint &&
        identity.destinationIndex == definition.destinationIndex(voucherId)

    private fun ensureMapView(player: Player, stack: ItemStack): MapView? {
        val identity = PersonalTreasureMapIdentity.read(stack) ?: return null
        val server = currentServer()
        var view = mapView?.takeIf { mapViewServer == server }
        if (view == null && identity.mapViewServer == server && identity.mapViewId != null) {
            view = Bukkit.getMap(identity.mapViewId)
        }
        if (view == null) view = runCatching { mapViewFactory(player.world) }.getOrElse { failure ->
            plugin.logger.warning("Personal treasure map view creation failed: ${failure.javaClass.simpleName}")
            return null
        }
        if (view !== mapView || mapViewServer != server || view.getRenderers().none { it === renderer }) {
            view.getRenderers().toList().forEach(view::removeRenderer)
            view.setScale(MapView.Scale.CLOSEST)
            view.setTrackingPosition(false)
            view.setUnlimitedTracking(false)
            view.setLocked(true)
            view.addRenderer(renderer)
            mapView = view
            mapViewServer = server
        }
        return view
    }

    private fun writeIdentity(data: org.bukkit.persistence.PersistentDataContainer, identity: PersonalTreasureMapIdentity) {
        data.set(PersonalTreasureMapIdentity.versionKey, PersistentDataType.STRING, PersonalTreasureMapIdentity.VERSION)
        data.set(PersonalTreasureMapIdentity.ownerKey, PersistentDataType.STRING, identity.ownerId.toString())
        data.set(PersonalTreasureMapIdentity.definitionKey, PersistentDataType.STRING, identity.definitionId)
        data.set(PersonalTreasureMapIdentity.fingerprintKey, PersistentDataType.STRING, identity.definitionFingerprint.sha256)
        data.set(PersonalTreasureMapIdentity.destinationIndexKey, PersistentDataType.INTEGER, identity.destinationIndex)
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
        ).any { data.has(it) }
    }

    private fun PersonalTreasureMapDestination.toLocation(world: org.bukkit.World): Location =
        Location(world, x, y, z)

    companion object {
        private const val CLAIM_DISTANCE_SQUARED = 9.0
        private const val MARKER_DISTANCE_SQUARED = 64.0 * 64.0
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
