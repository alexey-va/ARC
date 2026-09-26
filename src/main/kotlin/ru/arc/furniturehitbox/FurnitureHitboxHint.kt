package ru.arc.furniturehitbox

import org.bukkit.GameMode
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.bukkit.util.BoundingBox
import ru.arc.hooks.economyshop.FurnitureGalleryInteractionRuntime
import ru.arc.util.Logging.error
import ru.arc.util.Logging.info
import java.util.UUID

/** The native target IA will receive clicks for; never a visual-model approximation. */
internal data class FurnitureHitboxTarget(val root: Entity, val bounds: BoundingBox)

internal fun interface FurnitureHitboxSource {
    fun target(player: Player): FurnitureHitboxTarget?
    fun refreshAvailability() {}
}

internal interface FurnitureHitboxOutline : AutoCloseable {
    fun show(player: Player, target: FurnitureHitboxTarget)
    fun clear(viewerId: UUID)
}

/** Shares ItemInfo's server-thread heartbeat, but has no item-info preference or world gate. */
internal class FurnitureHitboxHint(
    private val source: FurnitureHitboxSource,
    private val outline: FurnitureHitboxOutline,
    private val onFailure: (Player, Exception) -> Unit,
) : AutoCloseable {
    private val failedViewers = mutableSetOf<UUID>()

    fun update(player: Player, refreshTarget: Boolean) {
        if (!player.isOnline || !player.isSneaking || player.isDead || player.gameMode == GameMode.SPECTATOR) {
            outline.clear(player.uniqueId)
            return
        }
        if (!refreshTarget || player.uniqueId in failedViewers) return
        try {
            val target = source.target(player)
            if (target == null || !target.root.isValid || target.root.world.uid != player.world.uid) {
                outline.clear(player.uniqueId)
            } else {
                outline.show(player, target)
            }
        } catch (failure: Exception) {
            outline.clear(player.uniqueId)
            failedViewers += player.uniqueId
            onFailure(player, failure)
        }
    }

    fun refreshAvailability() = source.refreshAvailability()

    fun reset(player: Player) {
        outline.clear(player.uniqueId)
        failedViewers.remove(player.uniqueId)
    }

    override fun close() {
        outline.close()
        failedViewers.clear()
    }

    companion object {
        fun create(plugin: Plugin, models: FurnitureGalleryInteractionRuntime?): FurnitureHitboxHint? {
            val source = ItemsAdderFurnitureHitboxSource.create(plugin, models) ?: return null
            val outline = PacketFurnitureHitboxOutline(plugin)
            info("Furniture hitbox hint initialized for all players and worlds; hold sneak to show the native click box")
            return FurnitureHitboxHint(source, outline) { player, failure ->
                error("Furniture hitbox hint failed for {} in {}; disabled until viewer reset", player.name, player.world.name, failure)
            }
        }
    }
}
