package ru.arc.onboarding

import org.bukkit.Location
import net.kyori.adventure.text.Component

/** Chunk-space geometry; internal shared edges never become a region border. */
internal data class GuideChunk(val x: Int, val z: Int)
internal data class GuideEdge(val x: Int, val z: Int, val alongX: Boolean)

internal fun claimGuideEdges(chunks: Set<GuideChunk>): List<GuideEdge> = buildList {
    chunks.forEach { (x, z) ->
        if (GuideChunk(x, z - 1) !in chunks) add(GuideEdge(x * 16, z * 16, true))
        if (GuideChunk(x, z + 1) !in chunks) add(GuideEdge(x * 16, z * 16 + 16, true))
        if (GuideChunk(x - 1, z) !in chunks) add(GuideEdge(x * 16, z * 16, false))
        if (GuideChunk(x + 1, z) !in chunks) add(GuideEdge(x * 16 + 16, z * 16, false))
    }
}

internal fun claimGuideEdgeOutside(edge: GuideEdge, interior: Set<GuideChunk>): GuideChunk {
    val base = GuideChunk(edge.x shr 4, edge.z shr 4)
    val negative = if (edge.alongX) GuideChunk(base.x, base.z - 1) else GuideChunk(base.x - 1, base.z)
    return if (negative in interior) base else negative
}

internal fun claimGuideChunks(center: GuideChunk, radius: Int): Set<GuideChunk> {
    require(radius in 0..4)
    return buildSet {
        for (x in center.x - radius..center.x + radius)
            for (z in center.z - radius..center.z + radius) add(GuideChunk(x, z))
    }
}

/** Thin grid edges for free chunks; internal free separators are retained once. */
internal fun claimGuideWildernessEdges(
    chunks: Set<GuideChunk>,
    occupied: Set<GuideChunk>,
): List<GuideEdge> = buildList {
    val free = chunks - occupied
    free.forEach { chunk ->
        val north = GuideChunk(chunk.x, chunk.z - 1)
        val south = GuideChunk(chunk.x, chunk.z + 1)
        val west = GuideChunk(chunk.x - 1, chunk.z)
        val east = GuideChunk(chunk.x + 1, chunk.z)
        if (north !in occupied) add(GuideEdge(chunk.x * 16, chunk.z * 16, true))
        if (south !in free && south !in occupied) add(GuideEdge(chunk.x * 16, chunk.z * 16 + 16, true))
        if (west !in occupied) add(GuideEdge(chunk.x * 16, chunk.z * 16, false))
        if (east !in free && east !in occupied) add(GuideEdge(chunk.x * 16 + 16, chunk.z * 16, false))
    }
}.distinct()

/** Looking into the sky must not turn off the held-item preview. */
internal fun claimGuideTarget(placementX: Int?, placementZ: Int?, playerX: Int, playerZ: Int): GuideChunk =
    if (placementX != null && placementZ != null) GuideChunk(placementX shr 4, placementZ shr 4)
    else GuideChunk(playerX shr 4, playerZ shr 4)

/** A personal HUD anchor independent of blocks and terrain; never mutates the eye location. */
internal fun claimGuideLabelLocation(eye: Location): Location =
    eye.clone().add(eye.direction.multiply(4.0)).add(0.0, 0.35, 0.0)

internal fun claimGuideLandText(template: Component, landName: String?): Component =
    template.replaceText { it.matchLiteral("{land}").replacement(Component.text(landName.orEmpty())) }

/** Kept below and to the right of the placement crosshair; no world collision entity. */
internal fun claimGuideButtonLocation(eye: Location): Location {
    val yaw = Math.toRadians(eye.yaw.toDouble())
    return eye.clone().add(eye.direction.multiply(4.0))
        .add(kotlin.math.cos(yaw) * 1.9, -1.60, kotlin.math.sin(yaw) * 1.9)
}

internal fun claimGuideButtonGesture(action: org.bukkit.event.block.Action, hand: org.bukkit.inventory.EquipmentSlot?, sneaking: Boolean): Boolean =
    sneaking && hand == org.bukkit.inventory.EquipmentSlot.HAND &&
        (action == org.bukkit.event.block.Action.LEFT_CLICK_AIR || action == org.bukkit.event.block.Action.LEFT_CLICK_BLOCK)

/** Ray against the personal billboard, rather than an entity that could steal right clicks. */
internal fun claimGuideButtonHit(eye: Location, button: Location): Boolean {
    if (eye.world != button.world) return false
    // TextDisplay grows upward from its anchor; target the middle of its three short lines.
    val center = button.toVector().add(org.bukkit.util.Vector(0.0, 0.44, 0.0))
    val offset = center.clone().subtract(eye.toVector())
    if (offset.lengthSquared() < 0.01 || offset.lengthSquared() > 36.0) return false
    val normal = offset.clone().normalize()
    val direction = eye.direction
    val denominator = direction.dot(normal)
    if (denominator <= 0.001) return false
    val distance = offset.dot(normal) / denominator
    if (distance > 6.0) return false
    val point = eye.toVector().add(direction.multiply(distance)).subtract(center)
    val yaw = Math.toRadians(button.yaw.toDouble())
    val horizontal = org.bukkit.util.Vector(kotlin.math.cos(yaw), 0.0, kotlin.math.sin(yaw))
    val up = normal.clone().crossProduct(horizontal).normalize()
    val right = up.clone().crossProduct(normal).normalize()
    return kotlin.math.abs(point.dot(right)) <= 2.30 && kotlin.math.abs(point.dot(up)) <= 0.68
}
