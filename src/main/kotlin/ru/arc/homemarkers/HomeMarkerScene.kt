package ru.arc.homemarkers

import dev.lone.itemsadder.api.CustomStack
import dev.lone.itemsadder.api.Events.ItemsAdderLoadDataEvent
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Display
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.PotionMeta
import org.bukkit.plugin.Plugin
import org.bukkit.util.Transformation
import org.bukkit.util.Vector
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.Tasks
import ru.arc.paper.display.PaperPacketDisplays
import ru.arc.paper.scene.PacketDisplayScene
import ru.arc.paper.scene.PaperSpatialControls
import ru.arc.paper.scene.SceneAudience
import ru.arc.paper.scene.SceneDisplayStyle
import ru.arc.paper.scene.SceneFrame
import ru.arc.paper.scene.ScenePart
import ru.arc.paper.scene.ScenePose
import ru.arc.paper.scene.SceneTextStyle
import ru.arc.paper.scene.SpatialControl
import ru.arc.paper.scene.SpatialControlController
import ru.arc.paper.scene.SpatialControlFrame
import ru.arc.paper.scene.SpatialHitPlane
import ru.arc.paper.scene.SpatialInputProfile
import ru.arc.paper.scene.SpatialNativeTarget
import ru.arc.paper.scene.SpatialReach
import ru.arc.paper.scene.SpatialSceneKey
import ru.arc.paper.scene.SpatialSceneScope
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** The loot model's floor is one block below its item origin; its authored height is 1.5. */
internal data class HomeMarkerGeometry(val width: Double, val height: Double, val itemY: Double, val labelY: Double) {
    val floorY: Double get() = 0.05
    val centerY: Double get() = floorY + height / 2.0
}

internal fun homeMarkerGeometry(scale: Float, lootModel: Boolean): HomeMarkerGeometry {
    val size = scale.toDouble()
    val height = size * if (lootModel) 1.5 else 1.0
    return HomeMarkerGeometry(size, height, 0.05 + size * if (lootModel) 1.0 else 0.5, 0.05 + height + 0.12)
}

private const val HOME_MARKER_LABEL_LINE_WIDTH = 240

/** Stable bounds match the native Interaction square and do not become stale when the camera turns. */
internal data class HomeMarkerHitBox(val bottomY: Double, val width: Double, val height: Double) {
    fun nativeTarget(anchor: Location) = SpatialNativeTarget(anchor.clone().add(0.0, bottomY, 0.0), width.toFloat(), height.toFloat())

    fun planes(anchor: Location): List<SpatialHitPlane> {
        val center = anchor.clone().add(0.0, bottomY + height / 2, 0.0)
        val x = Vector(1.0, 0.0, 0.0)
        val y = Vector(0.0, 1.0, 0.0)
        val z = Vector(0.0, 0.0, 1.0)
        return listOf(-1, 1).flatMap { side -> listOf(
            SpatialHitPlane(center.clone().add(0.0, 0.0, side * width / 2), x, y, width, height, false),
            SpatialHitPlane(center.clone().add(side * width / 2, 0.0, 0.0), z, y, width, height, false),
            SpatialHitPlane(center.clone().add(0.0, side * height / 2, 0.0), x, z, width, width, false),
        ) }
    }
}

/** Separate body and caption bands avoid a caption-wide target through the whole light column. */
internal fun homeMarkerHitBox(geometry: HomeMarkerGeometry, labelScale: Float, label: Boolean): HomeMarkerHitBox {
    val margin = 0.2
    // Keep the existing conservative envelope for native capture; visual planes validate the hit.
    if (!label) return HomeMarkerHitBox(geometry.floorY, geometry.width * sqrt(2.0) + 2 * margin, geometry.height + margin)
    val pixel = 0.025 * labelScale
    // Two authored rows, full wrapping width and background edges, plus the same 20 cm allowance.
    return HomeMarkerHitBox(geometry.labelY - margin,
        (HOME_MARKER_LABEL_LINE_WIDTH + 2) * pixel + 2 * margin, (2 * 10 + 2) * pixel + 2 * margin)
}

/** Native boxes capture empty-hand clicks; these planes recheck reach and occlusion at the actual visual. */
internal fun homeMarkerVisualHitPlanes(anchor: Location, cameraYaw: Float, geometry: HomeMarkerGeometry,
    labelScale: Float): List<SpatialHitPlane> {
    val angle = Math.toRadians(anchor.yaw.toDouble())
    val right = Vector(cos(angle), 0.0, sin(angle))
    val forward = Vector(-sin(angle), 0.0, cos(angle))
    val label = homeMarkerHitBox(geometry, labelScale, true)
    fun upright(y: Double, width: Double, height: Double, yaw: Float = anchor.yaw) = SpatialHitPlane(
        anchor.clone().add(0.0, y, 0.0).apply { this.yaw = yaw }, width, height, false,
    )
    return listOf(
        upright(geometry.centerY, geometry.width + 0.4, geometry.height + 0.4),
        upright(label.bottomY + label.height / 2, label.width, label.height, cameraYaw),
        SpatialHitPlane(anchor.clone().add(0.0, geometry.floorY, 0.0), right, forward,
            geometry.width + 0.4, geometry.width + 0.4, false),
    )
}

/** The authored item keeps its world orientation; only the caption follows the camera. */
internal fun homeMarkerParts(marker: HomeMarker, anchor: Location, settings: HomeMarkerSettings,
    item: ItemStack, lootModel: Boolean): List<ScenePart<String>> {
    val geometry = homeMarkerGeometry(settings.scale, lootModel)
    val id = "${marker.kind.name}:${marker.id}"
    fun transform(scale: Float) = Transformation(Vector3f(), Quaternionf(), Vector3f(scale), Quaternionf())
    fun style(textured: Boolean, billboard: Display.Billboard) = SceneDisplayStyle(
        billboard, Display.Brightness(15, 15), 0, 0, (settings.viewDistance / 64.0).toFloat(),
        0f, 0f, 0f, 0f, !textured, if (textured) null else settings.color(marker.kind),
    )
    return listOf(
        ScenePart.Item(
            "$id/item", ScenePose(anchor.clone().add(0.0, geometry.itemY, 0.0), transform(settings.scale)),
            style(lootModel, Display.Billboard.FIXED), item,
            if (lootModel) ItemDisplay.ItemDisplayTransform.FIXED else ItemDisplay.ItemDisplayTransform.NONE, true,
        ),
        ScenePart.Text(
            "$id/label", ScenePose(anchor.clone().add(0.0, geometry.labelY, 0.0), transform(settings.labelScale)),
            style(true, Display.Billboard.VERTICAL), settings.label(marker),
            SceneTextStyle(HOME_MARKER_LABEL_LINE_WIDTH, Color.fromARGB(140, 15, 23, 30), 255.toByte(), true, false, false, TextDisplay.TextAlignment.CENTER), true,
        ),
    )
}

/** Selection is bounded by the shared scene's 64-control limit; nearest owned points win. */
internal fun nearbyHomeMarkers(owner: UUID, at: Location, markers: List<HomeMarker>, distance: Double): List<HomeMarker> =
    markers.asSequence()
        .filter { it.ownerId == owner && it.world == at.world?.name }
        .map { it to ((it.x - at.x) * (it.x - at.x) + (it.y - at.y) * (it.y - at.y) + (it.z - at.z) * (it.z - at.z)) }
        .filter { it.second <= distance * distance }
        .sortedBy { it.second }
        .map { it.first }
        .distinctBy { it.kind to it.id }
        .take(64)
        .toList()

/** Private packet visuals; native right-click targets exist only while their owner is sneaking. */
internal class HomeMarkerScene(
    private val plugin: Plugin,
    private val settings: HomeMarkerSettings,
    private val onActivate: (Player, HomeMarker) -> Unit,
) : AutoCloseable {
    private class Viewer(val scene: PacketDisplayScene<String>, var markers: List<HomeMarker>)
    private val displays = PaperPacketDisplays(plugin, "home-markers")
    private val tasks = LifecycleTaskScope(Tasks.scheduler, true)
    private val viewers = mutableMapOf<UUID, Viewer>()
    private val validationFrames = mutableMapOf<UUID, SpatialControlFrame<List<HomeMarker>, String>>()
    private var model = resolveModel()
    private val geometry get() = homeMarkerGeometry(settings.scale, model != null)
    private var items = itemTemplates()
    private var closed = false
    private val modelListener: Listener? = if (settings.modelId.isNotEmpty() && plugin.server.pluginManager.isPluginEnabled("ItemsAdder")) {
        HomeMarkerModelListener { tasks.runSync { reloadModel() } }.also { plugin.server.pluginManager.registerEvents(it, plugin) }
    } else null

    private fun itemTemplates() = HomeMarkerKind.entries.associateWith { kind ->
        (model?.clone() ?: ItemStack(Material.BEACON)).apply {
            if (itemMeta is PotionMeta) editMeta(PotionMeta::class.java) { it.color = settings.color(kind) }
        }
    }
    private val controls = PaperSpatialControls(plugin, displays, object : SpatialControlController<List<HomeMarker>, String>() {
        override fun current(player: Player, scene: SpatialSceneKey): SpatialControlFrame<List<HomeMarker>, String>? {
            if (scene == validationKey(player.uniqueId)) return validationFrames[player.uniqueId]
            val label = when (scene) {
                key(player.uniqueId, false) -> false
                key(player.uniqueId, true) -> true
                else -> return null
            }
            val state = viewers[player.uniqueId] ?: return null
            return inputFrame(player, state.markers, label, player.isSneaking)
        }

        override fun activate(player: Player, scene: SpatialSceneKey, context: List<HomeMarker>, action: String) {
            if (!player.isSneaking || (scene != key(player.uniqueId, false) && scene != key(player.uniqueId, true))) return
            val marker = viewers[player.uniqueId]?.markers?.firstOrNull { markerKey(it) == action } ?: return
            if (marker in context && nearbyHomeMarkers(player.uniqueId, player.location, listOf(marker), settings.viewDistance).isNotEmpty()
                && visualTarget(player, marker)) {
                onActivate(player, marker)
            }
        }

        override fun inputFailed(player: Player, scene: SpatialSceneKey, context: List<HomeMarker>, failure: Exception) {
            clear(player.uniqueId)
        }
    })

    private fun visualTarget(player: Player, marker: HomeMarker): Boolean {
        val id = player.uniqueId
        val key = validationKey(id)
        val frame = SpatialControlFrame(listOf(marker), SceneAudience.Private(id, null), SpatialSceneScope.Viewer(id),
            listOf(SpatialControl(markerKey(marker), markerKey(marker),
                homeMarkerVisualHitPlanes(marker.location(player.world), player.eyeLocation.yaw, geometry, settings.labelScale),
                null, true, true)), SpatialInputProfile.EXTERNAL_RAY, SpatialReach.NativeInteraction, true, emptyList())
        validationFrames[id] = frame
        return try {
            controls.apply(key, frame)
            controls.target(key, player)?.action == markerKey(marker)
        } finally {
            try { controls.clear(key) } finally { validationFrames.remove(id) }
        }
    }

    private fun reloadModel() {
        if (closed) return
        model = resolveModel()
        items = itemTemplates()
        viewers.toMap().forEach { (id, state) ->
            val player = plugin.server.getPlayer(id)
            if (player == null) clear(id) else reconcile(player, state.markers)
        }
    }

    fun reconcile(player: Player, markers: List<HomeMarker>) {
        if (closed) return
        val selected = if (player.isOnline && !player.isDead) nearbyHomeMarkers(
            player.uniqueId, player.location, markers, settings.viewDistance,
        ) else emptyList()
        if (selected.isEmpty()) { clear(player.uniqueId); return }
        val state = viewers.getOrPut(player.uniqueId) { Viewer(PacketDisplayScene(displays), emptyList()) }
        state.markers = selected
        try {
            state.scene.apply(SceneFrame(parts(player, selected), SceneAudience.Private(player.uniqueId, null), 0))
            applyInput(player, selected, player.isSneaking)
        } catch (failure: Exception) {
            try { clear(player.uniqueId) } catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    /** ToggleSneak fires before Player.isSneaking changes; use its new state for immediate admission. */
    fun updateSneaking(player: Player, sneaking: Boolean) {
        if (closed || !player.isOnline || player.isDead) return
        val state = viewers[player.uniqueId] ?: return
        try { applyInput(player, state.markers, sneaking) } catch (failure: Exception) {
            try { clear(player.uniqueId) } catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    private fun applyInput(player: Player, markers: List<HomeMarker>, sneaking: Boolean) {
        listOf(false, true).forEach { label ->
            controls.apply(key(player.uniqueId, label), inputFrame(player, markers, label, sneaking))
        }
    }

    private fun inputFrame(player: Player, markers: List<HomeMarker>, label: Boolean,
        sneaking: Boolean): SpatialControlFrame<List<HomeMarker>, String> {
        val eligible = nearbyHomeMarkers(player.uniqueId, player.location, markers, settings.viewDistance).toSet()
        val box = homeMarkerHitBox(geometry, settings.labelScale, label)
        val entries = markers.map { marker ->
            val anchor = marker.location(player.world)
            SpatialControl(
                markerKey(marker), markerKey(marker), box.planes(anchor),
                if (sneaking) box.nativeTarget(anchor) else null, marker in eligible && sneaking, true,
            )
        }
        return SpatialControlFrame(
            markers, SceneAudience.Private(player.uniqueId, null), SpatialSceneScope.Viewer(player.uniqueId), entries,
            SpatialInputProfile.ENTITY_RIGHT, SpatialReach.NativeInteraction, sneaking, emptyList(),
        )
    }

    private fun parts(player: Player, markers: List<HomeMarker>): List<ScenePart<String>> = markers.flatMap { marker ->
        homeMarkerParts(marker, marker.location(player.world), settings, items.getValue(marker.kind), model != null)
    }

    private fun resolveModel(): ItemStack? {
        if (settings.modelId.isEmpty()) return null
        if (plugin.server.pluginManager.isPluginEnabled("ItemsAdder")) {
            val item = CustomStack.getInstance(settings.modelId)?.itemStack
            if (item?.itemMeta is PotionMeta) return item.clone()
        }
        plugin.logger.warning("Home marker model '${settings.modelId}' is unavailable or not a potion; using vanilla beacons")
        return null
    }

    fun clear(playerId: UUID) {
        try { controls.clear(key(playerId, false)) } finally {
            try { controls.clear(key(playerId, true)) } finally {
                try { controls.clear(validationKey(playerId)) } finally {
                    validationFrames.remove(playerId)
                    viewers.remove(playerId)?.scene?.close()
                }
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        modelListener?.let(HandlerList::unregisterAll)
        tasks.close()
        try {
            controls.close()
        } finally {
            try { viewers.values.forEach { it.scene.close() } } finally { viewers.clear(); validationFrames.clear(); displays.close() }
        }
    }

    private fun key(playerId: UUID, label: Boolean) = SpatialSceneKey("home-markers:$playerId:${if (label) "label" else "body"}")
    private fun validationKey(playerId: UUID) = SpatialSceneKey("home-markers:$playerId:visual-check")
    private fun markerKey(marker: HomeMarker) = "${marker.kind.name}:${marker.id}"
}

/** ItemsAdder may publish its item registry after plugin enable and again on pack reload. */
private class HomeMarkerModelListener(private val loaded: () -> Unit) : Listener {
    @EventHandler
    fun onLoaded(@Suppress("UNUSED_PARAMETER") event: ItemsAdderLoadDataEvent) { loaded() }
}
