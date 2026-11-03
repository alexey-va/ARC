package ru.arc.graves

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Color
import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.Display
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.arc.paper.display.PacketDisplay
import ru.arc.paper.display.PaperPacketDisplays
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.logging.Level

/** Nearby public tombs; no bossbars, world entities, block writes, or chunk loads. */
internal class GravesHud(
    plugin: Plugin,
    private val server: String,
    private val settings: GravesSettings,
    private val displays: PaperPacketDisplays = PaperPacketDisplays(plugin, "graves"),
    private val onClear: (Player) -> Unit = {},
) : AutoCloseable {
    private class Marker(val x: Int, val y: Int, val z: Int, val parts: List<PacketDisplay>) {
        fun matches(grave: GraveView): Boolean = x == grave.x && y == grave.y && z == grave.z && parts.all { it.isValid }
        fun remove() = parts.forEach(PacketDisplay::remove)
    }
    private class ViewerMarkers(val player: Player, val world: UUID, val entries: MutableMap<UUID, Marker> = mutableMapOf())
    private data class Cell(val x: Int, val y: Int, val z: Int)
    private val markers = mutableMapOf<UUID, ViewerMarkers>()
    private val logger = plugin.logger
    private var closed = false
    private var closeFuture: CompletableFuture<Void>? = null
    private var markerFailureLogged = false

    /** Returns selected, successfully allocated scenes; Core sends them asynchronously without a client render ACK. */
    fun refreshGlow(player: Player, graves: List<GraveView>): List<GraveView> {
        if (closed) return emptyList()
        if (player.isDead || !player.isOnline) { clear(player); return emptyList() }
        clearOtherWorldState(player)
        val world = player.world
        val eye = player.location
        fun distance(grave: GraveView): Double = eye.distanceSquared(Location(world, grave.x + .5, grave.y + .5, grave.z + .5))
        val ownerFirst = compareBy<GraveView> { it.ownerId != player.uniqueId }
        val now = System.currentTimeMillis()
        val desired = graves.asSequence().filter { grave ->
            grave.server == server && grave.world == world.name && grave.active(now) &&
                grave.y >= world.minHeight && grave.y < world.maxHeight - 1 &&
                distance(grave) <= settings.nearbyGlowRadius.toDouble() * settings.nearbyGlowRadius &&
                world.isChunkLoaded(grave.x shr 4, grave.z shr 4) &&
                player.isChunkSent(Chunk.getChunkKey(grave.x shr 4, grave.z shr 4))
        }.sortedWith(ownerFirst.thenByDescending { it.createdAt }.thenBy { it.id })
            .distinctBy { Cell(it.x, it.y, it.z) }
            .sortedWith(ownerFirst.thenBy(::distance).thenByDescending { it.createdAt }.thenBy { it.id })
            .filter { world.getBlockAt(it.x, it.y, it.z).isPassable && world.getBlockAt(it.x, it.y + 1, it.z).isPassable }
            .take(MAX_VISIBLE).toList()
        val current = markers.getOrPut(player.uniqueId) { ViewerMarkers(player, world.uid) }.entries
        val ids = desired.mapTo(hashSetOf()) { it.id }
        (current.keys - ids).forEach { current.remove(it)?.remove() }
        val rendered = mutableListOf<GraveView>()
        desired.forEach { grave ->
            if (current[grave.id]?.matches(grave) == true) { rendered += grave; return@forEach }
            current.remove(grave.id)?.remove()
            val parts = mutableListOf<PacketDisplay>()
            try {
                val appearance = GraveAppearances.select(grave.id, settings.appearance)
                val yaw = Quaternionf().rotationY(Math.toRadians(appearance.yaw.toDouble()).toFloat())
                appearance.model.parts.forEach { part ->
                    val center = yaw.transform(Vector3f(
                        (part.x + part.width / 2).toFloat(),
                        (part.y + part.height / 2).toFloat(),
                        (part.z + part.depth / 2).toFloat(),
                    ))
                    val rotation = Quaternionf(yaw).mul(Quaternionf().rotationXYZ(
                        Math.toRadians(part.rotationX.toDouble()).toFloat(),
                        Math.toRadians(part.rotationY.toDouble()).toFloat(),
                        Math.toRadians(part.rotationZ.toDouble()).toFloat(),
                    ))
                    displays.spawnBlock(
                        Location(world, grave.x + .5 + center.x, grave.y + center.y.toDouble(), grave.z + .5 + center.z),
                        part.material.createBlockData(),
                    ).also(parts::add).apply {
                        isVisibleByDefault = false
                        billboard = Display.Billboard.FIXED
                        viewRange = settings.nearbyGlowRadius / 64f
                        transformation = Transformation(
                            rotation.transform(Vector3f(-part.width / 2, -part.height / 2, -part.depth / 2)),
                            rotation, Vector3f(part.width, part.height, part.depth), Quaternionf(),
                        )
                    }
                }
                if (appearance.epitaph.isNotBlank()) {
                    val inscription = appearance.model.inscription
                    val placement = inscription.placement(appearance.epitaph)
                    val textAt = yaw.transform(Vector3f(inscription.x.toFloat(), placement.bottomY.toFloat(), inscription.z.toFloat()))
                    displays.spawnText(
                        // Native fixed text faces Bukkit yaw; its +Z front becomes local −Z at 180°.
                        Location(world, grave.x + .5 + textAt.x, grave.y + textAt.y.toDouble(), grave.z + .5 + textAt.z, 180f - appearance.yaw, 0f),
                        Component.text(appearance.epitaph, TextColor.color(inscription.color)).decoration(TextDecoration.ITALIC, false),
                    ).also(parts::add).apply {
                        isVisibleByDefault = false
                        billboard = Display.Billboard.FIXED
                        viewRange = settings.nearbyGlowRadius / 64f
                        lineWidth = inscription.lineWidth
                        isShadowed = false
                        isSeeThrough = false
                        backgroundColor = Color.fromARGB(0)
                        transformation = Transformation(Vector3f(), Quaternionf(), Vector3f(placement.scale), Quaternionf())
                    }
                }
                parts.forEach { it.showTo(player) }
                current[grave.id] = Marker(grave.x, grave.y, grave.z, parts.toList())
                rendered += grave
            } catch (failure: RuntimeException) {
                parts.forEach(PacketDisplay::remove)
                if (!markerFailureLogged) {
                    markerFailureLogged = true
                    logger.log(Level.WARNING, "Could not render grave ${grave.id}; its client interaction stays disabled", failure)
                }
            }
        }
        if (current.isEmpty()) clearMarkers(player)
        return rendered
    }

    private fun clearOtherWorldState(player: Player) {
        val current = markers[player.uniqueId]
        if (current != null && (current.world != player.world.uid || current.player !== player)) {
            clear(player)
        }
    }

    private fun clearMarkers(player: Player) {
        try { markers.remove(player.uniqueId)?.entries?.values?.forEach(Marker::remove) }
        finally { onClear(player) }
    }

    fun clear(player: Player) {
        clearMarkers(player)
    }

    /** Call only after the owning grave transaction has acknowledged durable claim completion. */
    fun claimSucceeded(player: Player) {
        if (closed || !player.isOnline || player.isDead) return
        player.playSound(player.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, .35f, 1.2f)
        val at = player.location.clone().add(0.0, .65, 0.0)
        player.spawnParticle(Particle.END_ROD, at, 4, .18, .18, .18, .01)
    }

    /** The owning module must observe this future before starting a replacement runtime. */
    fun closeAsync(): CompletableFuture<Void> {
        closeFuture?.let { return it }
        closed = true
        markers.values.map { it.player }.forEach(::clear)
        return displays.closeAsync().also { closeFuture = it }
    }

    override fun close() { closeAsync() }

    private companion object { const val MAX_VISIBLE = 8 }
}
