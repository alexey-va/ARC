package ru.arc.onboarding

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
