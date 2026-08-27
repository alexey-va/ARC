package ru.arc.buildertools

import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.data.type.Leaves
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.time.Duration
import java.util.UUID

/** Generic protection and plan boundary consumed by the copy/paste lifecycle. */
internal interface BuilderClipboardHost {
    fun ensureCopyPermission(player: Player)
    fun ensurePastePermission(player: Player)
    fun requiredSelection(player: Player): BuilderSelection
    fun world(worldId: UUID): World
    fun ensureInRangeAndLoaded(player: Player, block: Block)
    fun ensureProtected(player: Player, block: Block)
    fun ensureMutable(player: Player, block: Block)
    fun createPastePlan(
        player: Player,
        changes: List<BuilderBlockChange>,
        costs: List<BuilderItemAmount>,
    ): BuilderPlan
    fun failUnsafe(player: Player, block: Block): Nothing
    fun fail(path: String): Nothing
}

/**
 * Main-thread owner of bounded, non-durable player clipboards.
 *
 * Creation, expiry, copy filtering, paste planning, quit cleanup and shutdown
 * cleanup stay together. Generic permissions, Lands/range checks and plan
 * transactions remain delegated to [BuilderClipboardHost].
 */
internal class BuilderClipboardController(
    private val safety: BuilderBlockSafety,
    private val selections: BuilderSelectionController,
    private val maximumBlocks: Int,
    clipboardTtl: Duration,
    private val host: BuilderClipboardHost,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    private val ttlMillis = clipboardTtl.toMillis()
    private val clipboards = mutableMapOf<UUID, BuilderClipboard>()
    private var closed = false

    init {
        require(maximumBlocks in 1..BuilderPlan.ABSOLUTE_MAX_CHANGES) {
            "Builder clipboard maximum blocks must stay inside the absolute plan bound"
        }
        require(ttlMillis in Duration.ofMinutes(1).toMillis()..Duration.ofHours(2).toMillis()) {
            "Builder clipboard TTL must stay inside the configured safety bound"
        }
    }

    fun copy(player: Player): BuilderClipboard {
        check(!closed) { "Builder clipboard controller is closed" }
        host.ensureCopyPermission(player)
        val selection = host.requiredSelection(player)
        val world = host.world(selection.worldId)
        val blocks = mutableListOf<BuilderClipboardBlock>()
        selection.positionsBottomUp().forEach { position ->
            val block = world.getBlockAt(position.x, position.y, position.z)
            host.ensureInRangeAndLoaded(player, block)
            if (block.type.isAir) return@forEach
            if (safety.isReplaceable(block)) return@forEach
            if (!safety.isSafeExisting(block)) host.failUnsafe(player, block)
            host.ensureProtected(player, block)
            val copiedData = block.blockData.clone().also { data ->
                if (data is Leaves) data.isPersistent = true
            }
            blocks += BuilderClipboardBlock(
                dx = position.x - selection.minX,
                dy = position.y - selection.minY,
                dz = position.z - selection.minZ,
                blockData = copiedData.asString,
            )
            if (blocks.size > maximumBlocks) host.fail("errors.selection-too-large")
        }
        if (blocks.isEmpty()) host.fail("errors.empty-copy")
        val now = nowMillis()
        val clipboard = BuilderClipboard(
            blocks = blocks,
            sizeX = selection.sizeX,
            sizeY = selection.sizeY,
            sizeZ = selection.sizeZ,
            createdAtMillis = now,
            expiresAtMillis = Math.addExact(now, ttlMillis),
        ).validated(maximumBlocks)
        clipboards[player.uniqueId] = clipboard
        return clipboard
    }

    fun planPaste(player: Player): BuilderPlan {
        check(!closed) { "Builder clipboard controller is closed" }
        host.ensurePastePermission(player)
        val clipboard = current(player.uniqueId) ?: host.fail("errors.expired")
        val anchor = selections.first(player.uniqueId, player.world.uid) ?: host.fail("errors.selection-missing")
        val world = host.world(anchor.worldId)
        val costs = mutableListOf<ItemStack>()
        val changes = clipboard.blocks.mapNotNull { copied ->
            val position = BuilderBlockPos(
                worldId = anchor.worldId,
                x = Math.addExact(anchor.x, copied.dx),
                y = Math.addExact(anchor.y, copied.dy),
                z = Math.addExact(anchor.z, copied.dz),
            ).validated()
            val block = world.getBlockAt(position.x, position.y, position.z)
            val after = Bukkit.createBlockData(copied.blockData)
            if (!safety.isSafePlacement(after)) host.failUnsafe(player, block)
            if (block.blockData.asString == after.asString) return@mapNotNull null
            if (!safety.isReplaceable(block)) host.failUnsafe(player, block)
            host.ensureMutable(player, block)
            if (BuilderGameModePolicy.usesInventory(player.gameMode)) {
                costs += BuilderPlacementCost.item(after)
            }
            BuilderBlockChange(position, block.blockData.asString, after.asString)
        }
        if (changes.isEmpty()) host.fail("errors.nothing-to-change")
        return host.createPastePlan(player, changes, BuilderItemCodec.aggregate(costs))
    }

    fun current(playerId: UUID): BuilderClipboard? {
        if (closed) return null
        val clipboard = clipboards[playerId] ?: return null
        if (clipboard.expiresAtMillis <= nowMillis()) {
            clipboards.remove(playerId, clipboard)
            return null
        }
        return clipboard
    }

    fun clear(playerId: UUID) {
        clipboards.remove(playerId)
    }

    internal fun hasState(playerId: UUID): Boolean = playerId in clipboards

    override fun close() {
        if (closed) return
        closed = true
        clipboards.clear()
    }
}
