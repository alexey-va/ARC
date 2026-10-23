package ru.arc.iteminfo

import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import ru.arc.core.BoundedAsyncExecutor
import ru.arc.hooks.economyshop.FURNITURE_GALLERY_WORLD
import ru.arc.paper.api.ArcInspectionFrame
import ru.arc.paper.api.ArcInspectionProvider
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.RejectedExecutionException

/** Paper captures and polls; the owned worker only formats detached immutable input. */
internal class ItemInfoInspectionProvider(
    private val settings: ItemInfoSettings,
    private val resolveTarget: (Player) -> ItemInfoTarget?,
    private val preferences: (Player) -> ItemInfoPreferences,
    private val galleryPurchasePrice: (Player, String) -> String? = { _, _ -> null },
    private val work: BoundedAsyncExecutor = BoundedAsyncExecutor("arc-item-info", capacity = 64),
) : ArcInspectionProvider, AutoCloseable {
    private data class Input(val worldId: UUID, val target: ItemInfoTarget, val preferences: ItemInfoPreferences)
    private data class Pending(val input: Input, val result: CompletableFuture<ArcInspectionFrame>)
    private data class Ready(val input: Input, val frame: ArcInspectionFrame)
    private class Viewer {
        var desired: Input? = null
        var pending: Pending? = null
        var ready: Ready? = null
    }

    private val viewers = mutableMapOf<UUID, Viewer>()
    private var closed = false

    override fun resolve(player: Player): ArcInspectionFrame? {
        if (closed || !settings.enabled) return null
        val target = resolveTarget(player) ?: return absent(player)
        val world = player.world
        val price = if (world.name == FURNITURE_GALLERY_WORLD) {
            runCatching { galleryPurchasePrice(player, target.namespacedId) }
                .getOrNull()
                ?.replace('\n', ' ')
                ?.replace('\r', ' ')
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?.take(256)
        } else null
        // Only a current gallery offer may expose an otherwise excluded ID.
        if (target.namespacedId in settings.excludedItemIds && price == null) return absent(player)

        val input = Input(world.uid, target.copy(purchasePrice = price), preferences(player))
        val viewer = viewers.getOrPut(player.uniqueId, ::Viewer)
        if (viewer.desired != input) {
            viewer.desired = input
            viewer.ready = null
        }
        poll(viewer)
        viewer.ready?.takeIf { it.input == input }?.let { return it.frame }
        if (viewer.pending == null) {
            val capturedSettings = settings
            viewer.pending = Pending(input, work.submit {
                ArcInspectionFrame(
                    capturedSettings.hologramText(input.target, input.preferences.showNamespacedId),
                    capturedSettings.bossbarText(input.target, input.preferences.showNamespacedId),
                )
            })
        }
        // Occupied targets must not expose a lower-priority source while preparing.
        return PENDING_FRAME
    }

    private fun poll(viewer: Viewer) {
        val pending = viewer.pending?.takeIf { it.result.isDone } ?: return
        viewer.pending = null
        val frame = try {
            pending.result.getNow(null)
        } catch (failure: CompletionException) {
            if (failure.cause is RejectedExecutionException) return
            throw failure
        }
        if (pending.input == viewer.desired) viewer.ready = Ready(pending.input, requireNotNull(frame))
    }

    fun clear(player: Player) {
        viewers.remove(player.uniqueId)
    }

    private fun absent(player: Player): ArcInspectionFrame? {
        clear(player)
        return null
    }

    override fun close() {
        if (closed) return
        closed = true
        viewers.clear()
        work.close()
    }

    private companion object {
        val PENDING_FRAME = ArcInspectionFrame(Component.empty(), Component.empty())
    }
}
