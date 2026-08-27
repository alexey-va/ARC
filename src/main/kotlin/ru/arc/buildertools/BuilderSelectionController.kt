package ru.arc.buildertools

import org.bukkit.Particle
import org.bukkit.entity.Player
import java.util.UUID

internal data class BuilderSelectionUpdate(
    val selection: BuilderSelection?,
    val worldReset: Boolean,
)

/**
 * Owns one player's two-corner selection from the first point through visual
 * cleanup. All methods are Paper-primary-thread only.
 */
internal class BuilderSelectionController(
    private val previewRadius: Double,
    private val previewSpacing: Double,
    private val maximumOutlinePoints: Int,
) {
    private data class Draft(
        var first: BuilderBlockPos? = null,
        var second: BuilderBlockPos? = null,
    )

    private val drafts = mutableMapOf<UUID, Draft>()

    init {
        require(previewRadius > 0.0 && previewRadius.isFinite()) { "Selection preview radius must be positive" }
        require(previewSpacing > 0.0 && previewSpacing.isFinite()) { "Selection preview spacing must be positive" }
        require(maximumOutlinePoints > 0) { "Selection preview point limit must be positive" }
    }

    fun set(playerId: UUID, position: BuilderBlockPos, first: Boolean): BuilderSelectionUpdate {
        val checked = position.validated()
        val draft = drafts.getOrPut(playerId, ::Draft)
        val worldReset = sequenceOf(draft.first, draft.second)
            .filterNotNull()
            .any { it.worldId != checked.worldId }
        if (worldReset) {
            draft.first = null
            draft.second = null
        }
        if (first) draft.first = checked else draft.second = checked
        return BuilderSelectionUpdate(
            selection = selection(playerId, checked.worldId),
            worldReset = worldReset,
        )
    }

    fun selection(playerId: UUID, viewerWorldId: UUID): BuilderSelection? {
        val draft = drafts[playerId] ?: return null
        val first = draft.first ?: return null
        val second = draft.second ?: return null
        if (first.worldId != second.worldId || first.worldId != viewerWorldId) return null
        return BuilderSelection(first, second)
    }

    fun first(playerId: UUID, viewerWorldId: UUID): BuilderBlockPos? =
        drafts[playerId]?.first?.takeIf { it.worldId == viewerWorldId }

    fun clear(playerId: UUID): Boolean = drafts.remove(playerId) != null

    fun clear() = drafts.clear()

    fun render(player: Player) {
        val draft = drafts[player.uniqueId] ?: return
        draft.first?.let { showPoint(player, it, Particle.END_ROD) }
        draft.second?.let { showPoint(player, it, Particle.HAPPY_VILLAGER) }
        selection(player.uniqueId, player.world.uid)?.let { showOutline(player, it) }
    }

    private fun showPoint(player: Player, position: BuilderBlockPos, particle: Particle) {
        if (position.worldId != player.world.uid) return
        val x = position.x + 0.5
        val y = position.y + 0.5
        val z = position.z + 0.5
        val eye = player.eyeLocation
        val dx = x - eye.x
        val dy = y - eye.y
        val dz = z - eye.z
        if (dx * dx + dy * dy + dz * dz > previewRadius * previewRadius) return
        player.spawnParticle(particle, x, y, z, 3, 0.08, 0.08, 0.08, 0.0)
    }

    private fun showOutline(player: Player, selection: BuilderSelection) {
        val eye = player.eyeLocation
        BuilderSelectionPreviewGeometry.visibleOutline(
            selection = selection,
            viewerX = eye.x,
            viewerY = eye.y,
            viewerZ = eye.z,
            radius = previewRadius,
            spacing = previewSpacing,
            maximumPoints = maximumOutlinePoints,
        ).forEach { point ->
            player.spawnParticle(Particle.FLAME, point.x, point.y, point.z, 1, 0.0, 0.0, 0.0, 0.0)
        }
    }
}
