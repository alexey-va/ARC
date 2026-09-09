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

internal fun claimGuideChunks(center: GuideChunk, radius: Int): Set<GuideChunk> {
    require(radius in 0..4)
    return buildSet {
        for (x in center.x - radius..center.x + radius)
            for (z in center.z - radius..center.z + radius) add(GuideChunk(x, z))
    }
}

/** Looking into the sky must not turn off the held-item preview. */
internal fun claimGuideTarget(placementX: Int?, placementZ: Int?, playerX: Int, playerZ: Int): GuideChunk =
    if (placementX != null && placementZ != null) GuideChunk(placementX shr 4, placementZ shr 4)
    else GuideChunk(playerX shr 4, playerZ shr 4)

/** A personal HUD anchor independent of blocks and terrain; never mutates the eye location. */
internal fun claimGuideLabelLocation(eye: Location): Location =
    eye.clone().add(eye.direction.multiply(4.0)).add(0.0, 0.35, 0.0)

internal fun claimGuideLandText(template: Component, landName: String?): Component =
    template.replaceText { it.matchLiteral("{land}").replacement(Component.text(landName.orEmpty())) }
