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
import ru.arc.paper.scene.SpatialReach
import ru.arc.paper.scene.SpatialSceneKey
import ru.arc.paper.scene.SpatialSceneScope
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin

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

/** Private packet visuals; shared external ray controls leave ordinary block use and mining alone. */
internal class HomeMarkerScene(
    private val plugin: Plugin,
    private val settings: HomeMarkerSettings,
    private val onActivate: (Player, HomeMarker) -> Unit,
) : AutoCloseable {
    private class Viewer(val scene: PacketDisplayScene<String>, var markers: List<HomeMarker>)
    private val displays = PaperPacketDisplays(plugin, "home-markers")
    private val tasks = LifecycleTaskScope(Tasks.scheduler, true)
    private val viewers = mutableMapOf<UUID, Viewer>()
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
            if (scene != key(player.uniqueId)) return null
            val state = viewers[player.uniqueId] ?: return null
            return inputFrame(player, state.markers)
        }

        override fun activate(player: Player, scene: SpatialSceneKey, context: List<HomeMarker>, action: String) {
            if (!player.isSneaking || scene != key(player.uniqueId)) return
            val marker = viewers[player.uniqueId]?.markers?.firstOrNull { markerKey(it) == action } ?: return
            if (marker in context && nearbyHomeMarkers(player.uniqueId, player.location, listOf(marker), settings.viewDistance).isNotEmpty()) {
                onActivate(player, marker)
            }
        }

        override fun inputFailed(player: Player, scene: SpatialSceneKey, context: List<HomeMarker>, failure: Exception) {
            clear(player.uniqueId)
        }
    })

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
            controls.apply(key(player.uniqueId), inputFrame(player, selected))
        } catch (failure: Exception) {
            try { clear(player.uniqueId) } catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    fun intercept(player: Player): Boolean = prepareInput(player) && controls.intercept(key(player.uniqueId), player)

    private fun prepareInput(player: Player): Boolean {
        if (closed || !player.isOnline || player.isDead || !player.isSneaking) return false
        val state = viewers[player.uniqueId] ?: return false
        // VERTICAL billboards follow camera yaw in the client; derive the same input planes at the gesture.
        controls.apply(key(player.uniqueId), inputFrame(player, state.markers))
        return true
    }

    private fun inputFrame(player: Player, markers: List<HomeMarker>): SpatialControlFrame<List<HomeMarker>, String> {
        val eligible = nearbyHomeMarkers(player.uniqueId, player.location, markers, settings.viewDistance).toSet()
        val yaw = player.eyeLocation.yaw
        val angle = Math.toRadians(yaw.toDouble())
        val right = Vector(cos(angle), 0.0, sin(angle))
        val forward = Vector(-sin(angle), 0.0, cos(angle))
        val entries = markers.map { marker ->
            val anchor = marker.location(player.world)
            val body = anchor.clone().add(0.0, geometry.centerY, 0.0).apply { this.yaw = yaw }
            val floor = anchor.clone().add(0.0, geometry.floorY, 0.0)
            SpatialControl(
                markerKey(marker), markerKey(marker), listOf(
                    SpatialHitPlane(body, geometry.width, geometry.height, false),
                    SpatialHitPlane(floor, right, forward, geometry.width, geometry.width, false),
                ), null, marker in eligible, true,
            )
        }
        return SpatialControlFrame(
            markers, SceneAudience.Private(player.uniqueId, null), SpatialSceneScope.Viewer(player.uniqueId), entries,
            SpatialInputProfile.EXTERNAL_RAY, SpatialReach.NativeInteraction, true, emptyList(),
        )
    }

    private fun parts(player: Player, markers: List<HomeMarker>): List<ScenePart<String>> = buildList {
        markers.forEach { marker ->
            val anchor = marker.location(player.world)
            val id = markerKey(marker)
            add(ScenePart.Item(
                "$id/item", ScenePose(anchor.clone().add(0.0, geometry.itemY, 0.0), transform(settings.scale)),
                style(marker, model != null), items.getValue(marker.kind),
                if (model != null) ItemDisplay.ItemDisplayTransform.FIXED else ItemDisplay.ItemDisplayTransform.NONE, true,
            ))
            add(ScenePart.Text(
                "$id/label", ScenePose(anchor.clone().add(0.0, geometry.labelY, 0.0), transform(settings.labelScale)),
                style(marker, true), settings.label(marker),
                SceneTextStyle(240, Color.fromARGB(140, 15, 23, 30), 255.toByte(), true, false, false, TextDisplay.TextAlignment.CENTER), true,
            ))
        }
    }

    private fun style(marker: HomeMarker, textured: Boolean) = SceneDisplayStyle(
        Display.Billboard.VERTICAL, Display.Brightness(15, 15), 0, 0, (settings.viewDistance / 64.0).toFloat(),
        0f, 0f, 0f, 0f, !textured, if (textured) null else settings.color(marker.kind),
    )

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
        try { controls.clear(key(playerId)) } finally { viewers.remove(playerId)?.scene?.close() }
    }

    override fun close() {
        if (closed) return
        closed = true
        modelListener?.let(HandlerList::unregisterAll)
        tasks.close()
        try {
            controls.close()
        } finally {
            try { viewers.values.forEach { it.scene.close() } } finally { viewers.clear(); displays.close() }
        }
    }

    private fun key(playerId: UUID) = SpatialSceneKey("home-markers:$playerId")
    private fun markerKey(marker: HomeMarker) = "${marker.kind.name}:${marker.id}"
    private fun transform(scale: Float) = Transformation(Vector3f(), Quaternionf(), Vector3f(scale), Quaternionf())
}

/** ItemsAdder may publish its item registry after plugin enable and again on pack reload. */
private class HomeMarkerModelListener(private val loaded: () -> Unit) : Listener {
    @EventHandler
    fun onLoaded(@Suppress("UNUSED_PARAMETER") event: ItemsAdderLoadDataEvent) { loaded() }
}
