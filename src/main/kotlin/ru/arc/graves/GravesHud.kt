package ru.arc.graves

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Color
import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
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

/** The rendered geometry determines how many client-only interaction cells it needs. */
internal data class GraveMarker(val grave: GraveView, val height: Int)

/** Nearby public tombs; no bossbars, world entities, block writes, or chunk loads. */
internal class GravesHud(
    plugin: Plugin,
    private val server: String,
    private val settings: GravesSettings,
    private val displays: PaperPacketDisplays = PaperPacketDisplays(plugin, "graves"),
    private val decorativeBarrier: (UUID, Block) -> Boolean = { _, _ -> false },
    private val onClear: (Player) -> Unit = {},
) : AutoCloseable {
    private inner class Marker(
        val x: Int, val y: Int, val z: Int,
        val parts: List<PacketDisplay>,
        val appearance: GraveAppearance,
        val ownerName: String?,
        var engraving: YamipaGraveEngravings.Handle?,
    ) {
        fun matches(grave: GraveView, selected: GraveAppearance): Boolean =
            x == grave.x && y == grave.y && z == grave.z && appearance == selected && ownerName == grave.ownerName && parts.all { it.isValid }
        fun remove() {
            impact?.cancel(parts)
            try { engraving?.removeAsync() }
            finally { parts.forEach(PacketDisplay::remove) }
        }
    }
    private class ViewerMarkers(val player: Player, val world: UUID, val entries: MutableMap<UUID, Marker> = mutableMapOf())
    private data class Cell(val x: Int, val y: Int, val z: Int)
    private val markers = mutableMapOf<UUID, ViewerMarkers>()
    private val logger = plugin.logger
    private var impact: GraveImpact? = null
    private var closed = false
    private var closeFuture: CompletableFuture<Void>? = null
    private var markerFailureLogged = false
    private var imageFailureLogged = false
    private var engravings: YamipaGraveEngravings? = null
    private var retiredEngravings = CompletableFuture.completedFuture<Void>(null)

    /** A fresh provider generation rebuilds the visible markers on the next normal refresh. */
    fun attachEngravings(owner: YamipaGraveEngravings) {
        check(!closed) { "Cannot attach grave images after HUD shutdown" }
        if (engravings === owner) return
        markers.values.map { it.player }.forEach(::clear)
        engravings?.let { retiredEngravings = CompletableFuture.allOf(retiredEngravings, it.closeAsync()) }
        engravings = owner
        imageFailureLogged = false
    }

    /** Returns selected, successfully allocated scenes; Core sends them asynchronously without a client render ACK. */
    fun refreshGlow(player: Player, graves: List<GraveView>): List<GraveMarker> {
        if (closed) return emptyList()
        if (player.isDead || !player.isOnline) { clear(player); return emptyList() }
        clearOtherWorldState(player)
        val world = player.world
        val eye = player.location
        fun distance(grave: GraveView): Double = eye.distanceSquared(Location(world, grave.x + .5, grave.y + .5, grave.z + .5))
        // Every recovery scene wins both the per-cell tie and the bounded visible-scene budget.
        val ownerFirst = compareBy<GraveView> { it.decorative }.thenBy { it.ownerId != player.uniqueId }
        val now = System.currentTimeMillis()
        val imagesAvailable = engravingsAvailable()
        val desired = graves.asSequence().filter { grave ->
            grave.server == server && grave.world == world.name && grave.active(now) &&
                grave.y >= world.minHeight && grave.y < world.maxHeight &&
                distance(grave) <= settings.nearbyGlowRadius.toDouble() * settings.nearbyGlowRadius &&
                world.isChunkLoaded(grave.x shr 4, grave.z shr 4) &&
                player.isChunkSent(Chunk.getChunkKey(grave.x shr 4, grave.z shr 4))
        }.filter { grave ->
            val block = world.getBlockAt(grave.x, grave.y, grave.z)
            if (grave.decorative) decorativeBarrier(grave.id, block) else block.isPassable
        }.sortedWith(ownerFirst.thenByDescending { it.createdAt }.thenBy { it.id })
            .distinctBy { Cell(it.x, it.y, it.z) }
            .sortedWith(ownerFirst.thenBy(::distance).thenByDescending { it.createdAt }.thenBy { it.id })
            .take(MAX_VISIBLE).map { grave ->
                grave to appearance(grave, player.world, imagesAvailable)
            }.toList()
        val current = markers.getOrPut(player.uniqueId) { ViewerMarkers(player, world.uid) }.entries
        val ids = desired.mapTo(hashSetOf()) { it.first.id }
        (current.keys - ids).forEach { current.remove(it)?.remove() }
        val rendered = mutableListOf<GraveMarker>()
        desired.forEach { (grave, appearance) ->
            val renderedMarker = GraveMarker(grave, if (appearance.model.engraved) 2 else 1)
            val existing = current[grave.id]
            if (existing?.matches(grave, appearance) == true) {
                // Pending image cleanup can temporarily exhaust Yamipa's retained-image budget.
                if (existing.engraving?.isValid == false) existing.engraving = null
                if (existing.appearance.model.engraved && existing.engraving == null)
                    existing.engraving = createEngraving(player, grave, existing.appearance.yaw)
                rendered += renderedMarker
                return@forEach
            }
            current.remove(grave.id)?.remove()
            val parts = mutableListOf<PacketDisplay>()
            try {
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
                    val inscriptionTilt = Math.toRadians(inscription.rotationX.toDouble()).toFloat()
                    inscription.placements(appearance.epitaph, grave.ownerName, !grave.decorative || !grave.ownerName.isNullOrBlank()).forEach { placement ->
                        val textAt = yaw.transform(
                            Quaternionf().rotationX(inscriptionTilt)
                                .transform(Vector3f(0f, (placement.bottomY - inscription.centerY).toFloat(), 0f))
                                .add(inscription.x.toFloat(), inscription.centerY.toFloat(), inscription.z.toFloat()),
                        )
                        displays.spawnText(
                            // Native fixed text faces Bukkit yaw; its +Z front becomes local −Z at 180°.
                            Location(world, grave.x + .5 + textAt.x, grave.y + textAt.y.toDouble(), grave.z + .5 + textAt.z, 180f - appearance.yaw, 0f),
                            Component.text(placement.text, TextColor.color(inscription.color)).decoration(TextDecoration.ITALIC, false),
                        ).also(parts::add).apply {
                            isVisibleByDefault = false
                            billboard = Display.Billboard.FIXED
                            viewRange = settings.nearbyGlowRadius / 64f
                            lineWidth = placement.lineWidth
                            isShadowed = false
                            isSeeThrough = false
                            backgroundColor = Color.fromARGB(0)
                            transformation = Transformation(Vector3f(), Quaternionf().rotationX(-inscriptionTilt), Vector3f(placement.scale), Quaternionf())
                        }
                    }
                }
                parts.forEach { it.showTo(player) }
                // Artwork failure must not remove the already visible tomb or its independent epitaph.
                val engraving = if (appearance.model.engraved) createEngraving(player, grave, appearance.yaw) else null
                current[grave.id] = Marker(grave.x, grave.y, grave.z, parts.toList(), appearance, grave.ownerName, engraving)
                rendered += renderedMarker
            } catch (failure: RuntimeException) {
                parts.forEach(PacketDisplay::remove)
                if (!markerFailureLogged) {
                    markerFailureLogged = true
                    logger.log(Level.WARNING, "Could not render grave ${grave.id}; its client interaction stays disabled", failure)
                }
            }
        }
        if (current.isEmpty()) clearMarkers(player)
        return rendered.filterNot { it.grave.decorative }
    }

    fun engravingsAvailable(): Boolean = engravings?.health()?.available == true

    /** The renderer only uses headroom reserved for the grave; placement also considers empty cells. */
    fun appearance(
        grave: GraveView,
        world: World,
        imagesAvailable: Boolean = engravingsAvailable(),
        decorativeHeadroom: (Block) -> Boolean = { decorativeBarrier(grave.id, it) },
    ): GraveAppearance {
        val roomForImage = imagesAvailable && world.isChunkLoaded(grave.x shr 4, grave.z shr 4) && grave.y < world.maxHeight - 1 &&
            world.getBlockAt(grave.x, grave.y + 1, grave.z).let { if (grave.decorative) decorativeHeadroom(it) else it.isPassable }
        val selected = GraveAppearances.select(grave.id, settings.appearance, roomForImage, grave.appearance)
        return grave.cosmeticYaw?.let { selected.copy(yaw = it) } ?: selected
    }

    private fun createEngraving(player: Player, grave: GraveView, yaw: Float): YamipaGraveEngravings.Handle? {
        val owner = engravings?.takeIf { it.health().available } ?: return null
        return try {
            val face = when (yaw.toInt()) {
                90 -> BlockFace.WEST
                180 -> BlockFace.SOUTH
                270 -> BlockFace.EAST
                else -> BlockFace.NORTH
            }
            owner.spawn(player, grave.id, YamipaGraveEngravings.variant(grave.id),
                Location(player.world, grave.x.toDouble(), grave.y + 1.0, grave.z.toDouble()), face)
        } catch (failure: RuntimeException) {
            if (!imageFailureLogged) {
                imageFailureLogged = true
                logger.log(Level.WARNING, "Could not attach grave engraving ${grave.id}; base tomb remains visible", failure)
            }
            null
        }
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

    fun decorativeHit(id: UUID, at: Location, appearance: GraveAppearance, hit: Int) {
        if (closed) return
        val feedback = impact()
        feedback.hit(at, appearance, hit)
        markers.values.forEach { viewer ->
            viewer.entries[id]?.let { feedback.shake(it.parts, it.appearance, hit) }
        }
    }

    fun decorativeBreak(at: Location, appearance: GraveAppearance) {
        if (!closed) impact().broken(at, appearance)
    }

    private fun impact(): GraveImpact = impact ?: GraveImpact().also { impact = it }

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
        impact?.close()
        markers.values.map { it.player }.forEach(::clear)
        return CompletableFuture.allOf(displays.closeAsync(), retiredEngravings,
            engravings?.closeAsync() ?: CompletableFuture.completedFuture(null)).also { closeFuture = it }
    }

    override fun close() { closeAsync() }

    private companion object { const val MAX_VISIBLE = 8 }
}
