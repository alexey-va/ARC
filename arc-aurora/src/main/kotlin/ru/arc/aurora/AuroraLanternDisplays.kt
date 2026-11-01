package ru.arc.aurora

import org.bukkit.Location
import org.bukkit.entity.Display
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.arc.paper.display.PacketItemDisplay
import ru.arc.paper.display.PaperPacketDisplays
import java.util.UUID
import java.util.concurrent.CompletableFuture

/** Main-thread-owned handles. Core owns private audience tracking, ordered async packets and retirement. */
class AuroraLanternDisplays(plugin: Plugin) {
    private data class Scene(val model: AuroraSkyModel, val world: UUID, val handles: MutableList<PacketItemDisplay?>)

    private val displays = PaperPacketDisplays(plugin, "aurora-lanterns")
    private val viewers = mutableMapOf<UUID, MutableMap<Any, Scene>>()
    private var closed = false
    private var retirement: CompletableFuture<Void>? = null

    fun update(key: Any, player: Player, model: AuroraSkyModel, centers: List<Location>, progress: Double,
        yawRadians: Double, updateTicks: Int) {
        if (closed) return
        require(centers.size in 1..AuroraLanternMotion.MAX_LANTERNS)
        val viewer = player.uniqueId
        val world = player.world.uid
        if (centers.any { it.world?.uid != world }) {
            viewers[viewer]?.remove(key)?.remove()
            return
        }
        val scenes = viewers.getOrPut(viewer) { mutableMapOf() }
        val previous = scenes[key]
        if (previous != null && (previous.model != model || previous.world != world || previous.handles.size != centers.size)) {
            previous.remove()
            scenes.remove(key)
        }
        val scene = scenes.getOrPut(key) { Scene(model, world, MutableList(centers.size) { null }) }
        val interpolation = updateTicks.coerceIn(1, 59)
        for (index in centers.indices) {
            val pose = AuroraLanternMotion.displayPose(index, progress, yawRadians, model.scale, model.pivot)
            val handle = scene.handles[index]
            if (pose.scale <= 0f) {
                handle?.remove()
                scene.handles[index] = null
                continue
            }
            val center = centers[index].clone().apply { yaw = 0f; pitch = 0f }
            val transform = Transformation(Vector3f(pose.translation.x.toFloat(), pose.translation.y.toFloat(), pose.translation.z.toFloat()),
                Quaternionf(pose.rotationX, pose.rotationY, pose.rotationZ, pose.rotationW), Vector3f(pose.scale), Quaternionf())
            if (handle == null) {
                val created = displays.spawnItem(center, item(model))
                scene.handles[index] = created // Retain ownership even if later metadata preparation fails.
                created.apply {
                    isVisibleByDefault = false
                    billboard = Display.Billboard.FIXED
                    itemDisplayTransform = ItemDisplay.ItemDisplayTransform.NONE
                    brightness = Display.Brightness(15, 15)
                    shadowRadius = 0f
                    shadowStrength = 0f
                    viewRange = 2f
                    // The engine checks the full swept model envelope; avoid an origin-based client box cutting off its bottom.
                    displayWidth = 0f
                    displayHeight = 0f
                    interpolationDelay = 0
                    interpolationDuration = interpolation
                    teleportDuration = interpolation
                    transformation = transform
                    showTo(player)
                }
            } else {
                handle.interpolationDelay = 0
                handle.interpolationDuration = interpolation
                handle.teleportDuration = interpolation
                handle.teleport(center)
                handle.transformation = transform
            }
        }
    }

    fun retain(viewer: UUID, keys: Set<Any>) {
        val scenes = viewers[viewer] ?: return
        val iterator = scenes.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key !in keys) {
                entry.value.remove()
                iterator.remove()
            }
        }
        if (scenes.isEmpty()) viewers.remove(viewer)
    }

    fun removeViewer(viewer: UUID) {
        viewers.remove(viewer)?.values?.forEach { it.remove() }
    }

    fun closeAsync(): CompletableFuture<Void> {
        retirement?.let { return it }
        closed = true
        viewers.clear()
        return displays.closeAsync().also { retirement = it }
    }

    private fun Scene.remove() { handles.forEach { it?.remove() } }

    private fun item(model: AuroraSkyModel): ItemStack = ItemStack(model.material).also { item ->
        model.customModelData?.let { value ->
            val meta = item.itemMeta
            meta.setCustomModelData(value)
            item.itemMeta = meta
        }
    }
}
