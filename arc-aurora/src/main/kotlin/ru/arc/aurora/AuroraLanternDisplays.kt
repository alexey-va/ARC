package ru.arc.aurora

import org.bukkit.Location
import org.bukkit.Material
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
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CompletableFuture

/** Main owns the handles. Core tracks their audience, sends ordered updates and retires them. */
class AuroraLanternDisplays internal constructor(private val displays: PaperPacketDisplays) {
    constructor(plugin: Plugin) : this(PaperPacketDisplays(plugin, "aurora-lanterns"))

    private class Scene(val model: Any, val world: UUID, count: Int) {
        val handles = MutableList<PacketItemDisplay?>(count) { null }
        val visible = BooleanArray(count)
    }
    internal data class Part(val material: Material, val transform: Transformation, val customModelData: Int? = null)

    private val viewers = mutableMapOf<UUID, MutableMap<Any, Scene>>()
    private class ShowcaseAudience(displays: PaperPacketDisplays, var members: Set<UUID>) {
        val group = displays.createAudienceGroup { it.uniqueId in members }
    }
    private val showcaseAudiences = mutableMapOf<UUID, ShowcaseAudience>()
    private var closed = false
    private var retirement: CompletableFuture<Void>? = null

    /** All parts of a show use one gate, including parts that appear after a spectator arrives. */
    internal fun setShowcaseAudience(owner: UUID, audience: Set<UUID>?) {
        if (closed) return
        if (audience == null) {
            showcaseAudiences.remove(owner)?.group?.close()
        } else {
            val current = showcaseAudiences[owner]
            if (current == null) showcaseAudiences[owner] = ShowcaseAudience(displays, audience)
            else current.members = audience
        }
    }

    fun update(key: Any, player: Player, model: AuroraSkyModel, centers: List<Location>, progress: Double,
        yawRadians: Double, updateTicks: Int) {
        if (closed) return
        require(centers.size in 1..AuroraLanternMotion.MAX_LANTERNS)
        updateParts(key, player, model, centers.size, 0, centers, updateTicks) { index ->
            val pose = AuroraLanternMotion.displayPose(index, progress, yawRadians, model.scale, model.pivot)
            Part(model.material, Transformation(Vector3f(pose.translation.x.toFloat(), pose.translation.y.toFloat(), pose.translation.z.toFloat()),
                Quaternionf(pose.rotationX, pose.rotationY, pose.rotationZ, pose.rotationW), Vector3f(pose.scale), Quaternionf()),
                model.customModelData)
        }
    }

    internal fun updateMiniature(key: Any, player: Player, profile: AuroraProfile, firstPart: Int, centers: List<Location>,
        progress: Double, yawRadians: Double, pitchRadians: Double, size: Double) {
        if (closed) return
        val count = AuroraSkyMiniatures.partCount(profile.shape)
        require(firstPart in 0 until count && centers.size in 1..(count - firstPart))
        val orientation = Quaternionf().rotateY(yawRadians.toFloat()).rotateX(pitchRadians.toFloat())
        updateParts(key, player, profile.shape, count, firstPart, centers, profile.updateTicks) { index ->
            val part = AuroraSkyMiniatures.part(profile.shape, index, progress)
            Part(part.material, Transformation(Vector3f(), Quaternionf(orientation).mul(part.rotation),
                Vector3f(part.scale).mul(size.toFloat()), Quaternionf()))
        }
    }

    /** A shared fixed origin lets every part send its motion in one metadata update. */
    internal fun updateStory(key: Any, player: Player, profile: AuroraProfile, anchor: Location,
        parts: List<Part>, firstPart: Int, endPart: Int, interpolation: Int): Boolean {
        if (closed) return false
        val billboard = if (profile.shape.isIllustration) Display.Billboard.CENTER else Display.Billboard.FIXED
        return updateParts(key, player, profile.shape, parts.size, firstPart,
            Collections.nCopies(endPart - firstPart, anchor), interpolation, fixedAnchor = true, billboardMode = billboard,
            partAt = parts::get)
    }

    private inline fun updateParts(key: Any, player: Player, model: Any, count: Int, firstPart: Int,
        centers: List<Location>, updateTicks: Int, fixedAnchor: Boolean = false,
        billboardMode: Display.Billboard = Display.Billboard.FIXED, partAt: (Int) -> Part): Boolean {
        val viewer = player.uniqueId
        val world = player.world.uid
        if (centers.any { it.world?.uid != world }) {
            viewers[viewer]?.remove(key)?.remove()
            return false
        }
        val scenes = viewers.getOrPut(viewer) { mutableMapOf() }
        val previous = scenes[key]
        if (previous != null && (previous.model != model || previous.world != world || previous.handles.size != count)) {
            previous.remove()
            scenes.remove(key)
        }
        val scene = scenes.getOrPut(key) { Scene(model, world, count) }
        val interpolation = updateTicks.coerceIn(1, 59)
        for ((offset, location) in centers.withIndex()) {
            val index = firstPart + offset
            val part = partAt(index)
            val handle = scene.handles[index]
            val visible = part.transform.scale.lengthSquared() > 0f
            val wasVisible = scene.visible[index]
            scene.visible[index] = visible
            if (!visible) {
                if (fixedAnchor) {
                    if (handle != null && wasVisible) {
                        handle.interpolationDuration = 0
                        handle.transformation = part.transform
                    }
                } else {
                    handle?.remove()
                    scene.handles[index] = null
                }
                continue
            }
            val center = if (fixedAnchor) location else location.clone().apply { yaw = 0f; pitch = 0f }
            if (handle == null) {
                val created = displays.spawnItem(center, item(part))
                scene.handles[index] = created // Retain ownership even if later metadata preparation fails.
                created.apply {
                    val shared = showcaseAudiences[viewer]
                    if (shared == null) {
                        isVisibleByDefault = false
                        showTo(player)
                    } else {
                        audienceGroup = shared.group
                    }
                    billboard = billboardMode
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
                    transformation = part.transform
                }
            } else {
                handle.interpolationDelay = 0
                handle.interpolationDuration = if (fixedAnchor && !wasVisible) 0 else interpolation
                if (!fixedAnchor) {
                    handle.teleportDuration = interpolation
                    handle.teleport(center)
                }
                handle.transformation = part.transform
            }
        }
        return scene.visible.any { it }
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
        setShowcaseAudience(viewer, null)
    }

    fun closeAsync(): CompletableFuture<Void> {
        retirement?.let { return it }
        closed = true
        viewers.clear()
        showcaseAudiences.clear()
        return displays.closeAsync().also { retirement = it }
    }

    private fun Scene.remove() { handles.forEach { it?.remove() } }

    private fun item(part: Part): ItemStack = ItemStack(part.material).also { item ->
        part.customModelData?.let { value ->
            val meta = item.itemMeta
            meta.setCustomModelData(value)
            item.itemMeta = meta
        }
    }
}
