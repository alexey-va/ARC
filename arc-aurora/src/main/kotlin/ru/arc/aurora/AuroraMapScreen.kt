package ru.arc.aurora

import com.destroystokyo.paper.event.player.PlayerPostRespawnEvent
import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.netty.channel.ChannelHelper
import com.github.retrooper.packetevents.protocol.component.ComponentTypes
import com.github.retrooper.packetevents.protocol.entity.data.EntityData
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes
import com.github.retrooper.packetevents.protocol.item.ItemStack
import com.github.retrooper.packetevents.protocol.item.type.ItemTypes
import com.github.retrooper.packetevents.protocol.world.BlockFace
import com.github.retrooper.packetevents.util.Vector3d
import com.github.retrooper.packetevents.wrapper.PacketWrapper
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerMapData
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity
import io.papermc.paper.event.packet.PlayerChunkUnloadEvent
import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.HeightMap
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.plugin.java.JavaPlugin
import ru.arc.paper.api.VisualPacketAdmission
import ru.arc.paper.packet.PaperVisualPackets
import java.io.InputStream
import java.util.Optional
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

internal interface AuroraMapScreen {
    fun update(viewers: Collection<Player>)
    fun closeAsync(): CompletableFuture<Void>
}

internal class AuroraMapScreenFactory(private val plugin: JavaPlugin) {
    private val tiles = requireNotNull(plugin.getResource("maps/sakura-idol.bin")) {
        "Missing maps/sakura-idol.bin"
    }.use(AuroraMapAsset::read)
    private val packets by lazy(LazyThreadSafetyMode.NONE) { PaperVisualPackets(plugin, "showcase-map") }

    // Client map textures are keyed by map ID and can outlive the frames. Reserve one
    // immutable set per asset, with independent entity IDs for each presentation.
    private val mapIds by lazy(LazyThreadSafetyMode.NONE) { IntArray(tiles.size) { -nextMapEntityId() } }

    fun create(owner: Player): AuroraMapScreen? {
        val eye = owner.eyeLocation
        val world = owner.world
        val initial = AuroraMapPlacement.inFrontOf(eye.x, eye.y, eye.z, eye.yaw)
        val columns = (0 until AuroraMapAsset.COLUMNS).map { initial.position(it, 0) }
        if (columns.any { !world.isChunkLoaded(it.x shr 4, it.z shr 4) }) return null

        val surface = columns.maxOf { world.getHighestBlockYAt(it.x, it.z, HeightMap.WORLD_SURFACE) }
        val placement = initial.above(surface, world.minHeight, world.maxHeight) ?: return null
        for (row in 0 until AuroraMapAsset.ROWS) for (column in 0 until AuroraMapAsset.COLUMNS) {
            val pos = placement.position(column, row)
            val block = world.getBlockAt(pos.x, pos.y, pos.z)
            if (!block.isPassable || block.isLiquid) return null
        }

        val carriers = tiles.mapIndexed { index, tile ->
            AuroraMapCarrier(nextMapEntityId(), UUID.randomUUID(), mapIds[index], placement.face,
                placement.position(tile.column, tile.row), tile)
        }
        return PacketAuroraMapScreen(plugin, world.uid, carriers, packets)
    }
}

@Suppress("DEPRECATION")
private fun nextMapEntityId() = Bukkit.getUnsafe().nextEntityId()

internal data class AuroraMapTile(
    val column: Int,
    val row: Int,
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
    val colors: ByteArray,
)

internal object AuroraMapAsset {
    const val COLUMNS = 8
    const val ROWS = 12
    const val SIDE = 128
    const val WIDTH = COLUMNS * SIDE
    const val HEIGHT = ROWS * SIDE

    fun read(input: InputStream): List<AuroraMapTile> {
        val source = input.readNBytes(WIDTH * HEIGHT + 1)
        require(source.size == WIDTH * HEIGHT) { "Sakura map must contain exactly ${WIDTH * HEIGHT} palette bytes" }
        val tiles = buildList {
            for (row in 0 until ROWS) for (column in 0 until COLUMNS) {
                val start = row * SIDE * WIDTH + column * SIDE
                var left = SIDE; var top = SIDE; var right = -1; var bottom = -1
                for (y in 0 until SIDE) for (x in 0 until SIDE) {
                    if (source[start + y * WIDTH + x] == 0.toByte()) continue
                    left = minOf(left, x)
                    top = minOf(top, y)
                    right = maxOf(right, x)
                    bottom = maxOf(bottom, y)
                }
                if (right < 0) continue
                val width = right - left + 1
                val height = bottom - top + 1
                val colors = ByteArray(width * height)
                for (y in 0 until height) {
                    val offset = start + (top + y) * WIDTH + left
                    source.copyInto(colors, y * width, offset, offset + width)
                }
                add(AuroraMapTile(column, row, left, top, width, height, colors))
            }
        }
        require(tiles.isNotEmpty()) { "Sakura map contains no visible pixels" }
        return tiles
    }
}

internal enum class AuroraMapFace(val protocolFace: BlockFace, val direction: Int, val yaw: Float,
    val rightX: Int, val rightZ: Int) {
    SOUTH(BlockFace.SOUTH, 3, 0f, 1, 0),
    WEST(BlockFace.WEST, 4, 90f, 0, 1),
    NORTH(BlockFace.NORTH, 2, 180f, -1, 0),
    EAST(BlockFace.EAST, 5, -90f, 0, -1),
}

internal data class AuroraMapPosition(val x: Int, val y: Int, val z: Int) {
    val chunk: Long get() = Chunk.getChunkKey(x shr 4, z shr 4)
}

/** Rows descend from the top, and columns increase towards the observer's right. */
internal data class AuroraMapPlacement(val face: AuroraMapFace, val leftX: Int, val topY: Int, val leftZ: Int) {
    fun position(column: Int, row: Int) = AuroraMapPosition(
        leftX + column * face.rightX, topY - row, leftZ + column * face.rightZ,
    )

    fun above(surface: Int, minHeight: Int, maxHeight: Int): AuroraMapPlacement? {
        val lower = maxOf(topY - AuroraMapAsset.ROWS + 1, minHeight, surface + 2)
        val upper = lower + AuroraMapAsset.ROWS - 1
        return if (upper < maxHeight) copy(topY = upper) else null
    }

    companion object {
        fun inFrontOf(x: Double, y: Double, z: Double, yaw: Float): AuroraMapPlacement {
            val angle = Math.toRadians(yaw.toDouble())
            val face = AuroraMapFace.entries[Math.floorMod((yaw / 90f).roundToInt() + 2, 4)]
            val centerX = floor(x + forwardOffset(-sin(angle))).toInt()
            val centerZ = floor(z + forwardOffset(cos(angle))).toInt()
            val topY = floor(y + 14.9).toInt() + AuroraMapAsset.ROWS / 2
            return AuroraMapPlacement(face, centerX - face.rightX * (AuroraMapAsset.COLUMNS / 2),
                topY, centerZ - face.rightZ * (AuroraMapAsset.COLUMNS / 2))
        }

        private fun forwardOffset(component: Double) = if (abs(component) < 1e-12) 0.0 else component * 32.5
    }
}

private data class AuroraMapCarrier(val entityId: Int, val uuid: UUID, val mapId: Int, val face: AuroraMapFace,
    val pos: AuroraMapPosition, val tile: AuroraMapTile) {
    fun packets(): List<PacketWrapper<*>> {
        val item = ItemStack.builder().type(ItemTypes.FILLED_MAP).amount(1).component(ComponentTypes.MAP_ID, mapId).build()
        return listOf(
            WrapperPlayServerSpawnEntity(entityId, Optional.of(uuid), EntityTypes.GLOW_ITEM_FRAME,
                Vector3d(pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble()), 0f, face.yaw, face.yaw,
                face.direction, Optional.empty()),
            // Paper 1.21.11: AbstractHangingEntity direction at 8, then ItemFrame item and rotation.
            WrapperPlayServerEntityMetadata(entityId, listOf(
                EntityData(0, EntityDataTypes.BYTE, 0x20.toByte()),
                EntityData(8, EntityDataTypes.BLOCK_FACE, face.protocolFace),
                EntityData(9, EntityDataTypes.ITEMSTACK, item),
                EntityData(10, EntityDataTypes.INT, 0),
            )),
            WrapperPlayServerMapData(mapId, 0, false, true, emptyList(),
                tile.width, tile.height, tile.left, tile.top, tile.colors),
        )
    }
}

private class PacketAuroraMapScreen(private val plugin: JavaPlugin, private val world: UUID,
    private val carriers: List<AuroraMapCarrier>, private val packets: PaperVisualPackets) : AuroraMapScreen, Listener {
    private data class View(val channel: Any, val delivery: AuroraMapDelivery)

    private val chunks = carriers.mapTo(hashSetOf()) { it.pos.chunk }
    private val viewers = mutableMapOf<UUID, View>()
    private val retired = mutableListOf<CompletableFuture<Void>>()
    private var retirement: CompletableFuture<Void>? = null

    init { plugin.server.pluginManager.registerEvents(this, plugin) }

    override fun update(viewers: Collection<Player>) {
        if (retirement != null) return
        retired.removeIf { it.isDone }
        this.viewers.entries.removeIf { it.value.delivery.finished && !it.value.delivery.failed }

        val eligible = hashSetOf<UUID>()
        for (player in viewers) {
            if (!player.isOnline || player.world.uid != world || !chunks.all(player::isChunkSent)) continue
            val channel = PacketEvents.getAPI().playerManager.getChannel(player) ?: continue
            val id = player.uniqueId
            eligible += id
            val previous = this.viewers[id]
            // A retired view on the same channel keeps its slot until ordered cleanup settles.
            if (previous?.channel === channel) continue
            if (previous != null) retired += previous.delivery.closeAsync()

            val delivery = AuroraMapDelivery(carriers.size,
                sendTile = { packets.write(channel, carriers[it].packets()) },
                destroy = { count -> packets.write(channel,
                    listOf(WrapperPlayServerDestroyEntities(*IntArray(count) { carriers[it].entityId })), cleanup = true) },
                enqueue = { ChannelHelper.runInEventLoop(channel, Runnable(it)) },
                retry = { PaperVisualPackets.retry(channel, it) },
                flush = { ChannelHelper.flush(channel) },
                isOpen = { ChannelHelper.isOpen(channel) },
                reportFailure = { plugin.logger.log(Level.WARNING, "ArcAurora map showcase delivery failed", it) },
            )
            this.viewers[id] = View(channel, delivery)
            delivery.start()
        }
        this.viewers.entries.removeIf { (id, view) ->
            if (id in eligible) false else {
                view.delivery.retire()
                view.delivery.finished
            }
        }
    }

    private fun retire(player: Player) {
        val view = viewers[player.uniqueId] ?: return
        view.delivery.retire()
        if (view.delivery.finished) viewers.remove(player.uniqueId)
    }

    @EventHandler fun chunkUnload(event: PlayerChunkUnloadEvent) {
        if (event.chunk.world.uid == world && event.chunk.chunkKey in chunks) retire(event.player)
    }

    @EventHandler fun respawn(event: PlayerPostRespawnEvent) = retire(event.player)
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun teleport(event: PlayerTeleportEvent) = retire(event.player)
    @EventHandler fun changeWorld(event: PlayerChangedWorldEvent) = retire(event.player)
    @EventHandler fun quit(event: PlayerQuitEvent) = retire(event.player)

    override fun closeAsync(): CompletableFuture<Void> {
        retirement?.let { return it.copy() }
        HandlerList.unregisterAll(this)
        val drains = retired + viewers.values.map { it.delivery.closeAsync() }
        viewers.clear()
        retired.clear()
        return CompletableFuture.allOf(*drains.toTypedArray()).also { retirement = it }.copy()
    }
}

/** One bounded ordered sender per channel. Only the event loop touches the send cursor. */
internal class AuroraMapDelivery(
    private val tileCount: Int,
    private val sendTile: (Int) -> VisualPacketAdmission,
    private val destroy: (Int) -> VisualPacketAdmission,
    private val enqueue: (() -> Unit) -> Unit,
    private val retry: (() -> Unit) -> Unit,
    private val flush: () -> Unit,
    private val isOpen: () -> Boolean,
    private val reportFailure: (Exception) -> Unit,
) {
    private val pumping = AtomicBoolean()
    private val closing = AtomicBoolean()
    private val completion = CompletableFuture<Void>()
    val finished: Boolean get() = completion.isDone
    val failed: Boolean get() = completion.isCompletedExceptionally

    private var nextTile = 0
    private var possiblySent = 0
    private var sendFailure: Exception? = null

    fun start() = signal()

    fun retire() {
        if (closing.compareAndSet(false, true)) signal()
    }

    fun closeAsync(): CompletableFuture<Void> {
        retire()
        return completion.copy()
    }

    private fun signal() {
        if (!finished && pumping.compareAndSet(false, true)) schedule(enqueue)
    }

    private fun schedule(scheduler: (() -> Unit) -> Unit) {
        try {
            scheduler(::drain)
        } catch (failure: Exception) {
            finish(if (isOpen()) failure else sendFailure)
        }
    }

    private fun drain() {
        if (!isOpen()) { finish(sendFailure); return }
        if (closing.get()) { clean(); return }

        val before = nextTile
        val end = minOf(before + TILES_PER_PASS, tileCount)
        try {
            while (nextTile < end && !closing.get()) {
                // Encoding/writing can fail after the frame was partly written.
                possiblySent = nextTile + 1
                val admission = sendTile(nextTile)
                if (admission != VisualPacketAdmission.ALLOWED) {
                    possiblySent = nextTile // A deferred transaction writes nothing.
                    check(admission != VisualPacketAdmission.CLOSED) { "ArcCore visual packet budget closed" }
                    break
                }
                nextTile++
            }
            if (nextTile != before) flush()
        } catch (failure: Exception) {
            sendFailure = failure
            closing.set(true)
        }

        if (closing.get() || nextTile < tileCount) schedule(retry)
        else {
            pumping.set(false)
            if (closing.get()) signal()
        }
    }

    private fun clean() {
        if (possiblySent == 0) { finish(sendFailure); return }
        try {
            when (destroy(possiblySent)) {
                VisualPacketAdmission.ALLOWED -> {
                    flush()
                    finish(sendFailure)
                }
                VisualPacketAdmission.CLOSED -> finish(IllegalStateException("ArcCore closed before map showcase cleanup", sendFailure))
                else -> schedule(retry)
            }
        } catch (failure: Exception) { finish(failure) }
    }

    private fun finish(failure: Exception?) {
        closing.set(true)
        if (failure == null) completion.complete(null)
        else if (completion.completeExceptionally(failure)) reportFailure(failure)
    }

    private companion object {
        const val TILES_PER_PASS = 4
    }
}
