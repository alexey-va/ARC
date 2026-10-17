package ru.arc.leafdecay

import com.destroystokyo.paper.ParticleBuilder
import com.jeff_media.customblockdata.CustomBlockData
import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.persistence.PersistentDataType
import ru.arc.ARC
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.core.ScheduledTask
import ru.arc.core.repeating
import ru.arc.core.ticks
import ru.arc.util.ParticleManager
import ru.arc.util.Logging.error
import java.util.ArrayDeque
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentSkipListSet
import java.util.concurrent.atomic.AtomicInteger

object LeafDecayManager {

    lateinit var leafChecker: LeafChecker

    private val config: Config get() = ConfigManager.ofModule(ARC.instance.dataPath, "leafdecay.yml")

    private var checkTask: ScheduledTask? = null
    private var decayTask: ScheduledTask? = null
    private var tagClearTask: ScheduledTask? = null

    private val chunkQueue = LeafChunkQueue()
    private data class DecayCandidate(val location: Location, val expectedData: String)
    private val leafQueue = ArrayDeque<DecayCandidate>()
    private val awaitingTagClearing = ArrayDeque<Block>()
    private var generation = 0L
    private val activeScans = mutableSetOf<Chunk>()
    private var polling = false
    private var decaying = false
    private var clearing = false

    private val playerPlacedKey by lazy { NamespacedKey(ARC.instance, "lf") }

    private var decayInterval = 5L
    private var checkInterval = 20L
    private var checkWorldEach = 1000L
    private var leafBatchSize = 100L
    private var leafMaterials: Set<Material> = emptySet()
    private var trunkMaterials: Set<Material> = emptySet()
    private var playParticles = true
    private var playSound = true
    private var worlds: Set<String> = emptySet()

    @JvmStatic
    fun init() {
        reload()
    }

    @JvmStatic
    fun reload() {
        cancel()
        decayInterval = config.integer("decay-interval", 5).toLong()
        checkInterval = config.integer("global-check-interval", 20).toLong()
        checkWorldEach = config.integer("world-check-interval", 1000).toLong()
        leafBatchSize = config.integer("leaf-batch-size", 100).toLong()
        leafMaterials = config.stringList("leaf-materials")
            .map { Material.valueOf(it.uppercase()) }
            .toSet()
        trunkMaterials = config.stringList("trunk-materials")
            .map { Material.valueOf(it.uppercase()) }
            .toSet()
        leafChecker = LeafChecker(config, leafMaterials, trunkMaterials)
        playParticles = config.bool("play-particles", true)
        playSound = config.bool("play-sound", true)
        worlds = config.stringList("leaf-decay-worlds").toSet()
        start()
    }

    @JvmStatic
    fun cancel() {
        generation++
        stopTasks()
        activeScans.clear()
        polling = false
        decaying = false
        clearing = false
        chunkQueue.clear()
        leafQueue.clear()
        awaitingTagClearing.clear()
    }

    private fun stopTasks() {
        checkTask?.cancel()
        decayTask?.cancel()
        tagClearTask?.cancel()
        checkTask = null
        decayTask = null
        tagClearTask = null
    }

    private fun start() {
        stopTasks()
        val epoch = generation
        val work = ARC.instance.nativeWork
        val counter = AtomicInteger()

        checkTask = repeating(period = checkInterval.ticks, delay = 60.ticks) {
            var count = 0
            if (leafQueue.size <= 10000) {
                while (!chunkQueue.isEmpty()) {
                    if (count++ >= MAX_CHUNKS_PER_TICK || activeScans.size >= MAX_CHUNKS_PER_TICK) break
                    val chunk = chunkQueue.poll() ?: break
                    if (worlds.contains(chunk.world.name)) checkChunk(chunk, epoch)
                }
            }
            if (counter.incrementAndGet() >= checkWorldEach) {
                counter.set(0)
                pollChunks(epoch)
            }
        }

        decayTask = repeating(period = decayInterval.ticks, delay = 60.ticks) {
            if (decaying || leafQueue.isEmpty()) return@repeating
            decaying = true
            var left = leafBatchSize.coerceAtLeast(0)
            observe(work.submit({ generation == epoch }) {
                if (left-- <= 0) return@submit true
                val candidate = leafQueue.poll() ?: return@submit true
                decay(candidate)
                left <= 0 || leafQueue.isEmpty()
            }, epoch) { decaying = false }
        }

        tagClearTask = repeating(period = 1.ticks, delay = 60.ticks) {
            if (clearing || awaitingTagClearing.isEmpty()) return@repeating
            clearing = true
            var left = MAX_TAG_CLEARS_PER_TICK
            observe(work.submit({ generation == epoch }) {
                val block = awaitingTagClearing.pollFirst() ?: return@submit true
                if (!block.world.isChunkLoaded(block.x shr 4, block.z shr 4)) return@submit --left <= 0 || awaitingTagClearing.isEmpty()
                val data = CustomBlockData(block, ARC.instance)
                if (data.has(playerPlacedKey)) {
                    data.remove(playerPlacedKey)
                }
                --left <= 0 || awaitingTagClearing.isEmpty()
            }, epoch) { clearing = false }
        }
    }

    private fun checkChunk(chunk: Chunk, epoch: Long) {
        val world = chunk.world
        val neighborsLoaded = world.isChunkLoaded(chunk.x, chunk.z) && world.isChunkLoaded(chunk.x + 1, chunk.z) &&
            world.isChunkLoaded(chunk.x - 1, chunk.z) &&
            world.isChunkLoaded(chunk.x, chunk.z + 1) &&
            world.isChunkLoaded(chunk.x, chunk.z - 1)
        if (!neighborsLoaded) return
        if (!::leafChecker.isInitialized || !activeScans.add(chunk)) return
        val scan = leafChecker.beginChunk(chunk)
        var candidates: Iterator<Location>? = null
        observe(ARC.instance.nativeWork.submit({ generation == epoch && chunk.isLoaded }) {
            if (candidates == null) {
                if (!scan.step()) return@submit false
                candidates = scan.result().iterator()
                return@submit false
            }
            val locations = requireNotNull(candidates)
            if (!locations.hasNext()) return@submit true
            val location = locations.next()
            val block = location.block
            if (block.type in leafMaterials || block.type in trunkMaterials) {
                leafQueue.add(DecayCandidate(location, block.blockData.asString))
            }
            !locations.hasNext()
        }, epoch) { activeScans.remove(chunk) }
    }

    private fun pollChunks(epoch: Long) {
        if (leafQueue.size > 10000 || polling) return
        polling = true
        val worldNames = worlds.iterator()
        var players = emptyList<Player>().iterator()
        val steps = sequence {
            while (true) {
                if (!players.hasNext()) {
                    if (!worldNames.hasNext()) break
                    players = Bukkit.getWorld(worldNames.next())?.players.orEmpty().iterator()
                    yield(Unit)
                    continue
                }
                val player = players.next()
                if (!player.isOnline || player.world.name !in worlds) { yield(Unit); continue }
                val center = player.location
                val world = player.world
                for (dx in -1..1) for (dz in -1..1) {
                    val x = (center.blockX shr 4) + dx
                    val z = (center.blockZ shr 4) + dz
                    if (world.isChunkLoaded(x, z)) chunkQueue.add(world.getChunkAt(x, z))
                    yield(Unit)
                }
            }
        }.iterator()
        observe(ARC.instance.nativeWork.submit({ generation == epoch }) {
            if (!steps.hasNext()) true else { steps.next(); false }
        }, epoch) { polling = false }
    }

    private fun decay(candidate: DecayCandidate) {
        val location = candidate.location
        val world = location.world ?: return
        if (!world.isChunkLoaded(location.blockX shr 4, location.blockZ shr 4)) return
        val block = location.block
        // Scans may span ticks: never destroy a replacement or a newly tagged player block.
        if (block.blockData.asString != candidate.expectedData || CustomBlockData.hasCustomBlockData(block, ARC.instance)) return
        val type = block.type
        if (type !in leafMaterials && type !in trunkMaterials) return
        block.breakNaturally()
        if (playSound) world.playSound(location, Sound.BLOCK_GRASS_BREAK, 1.0f, 1.0f)
        if (playParticles) {
            ParticleManager.queue(ParticleBuilder(Particle.BLOCK).count(2).location(location.clone().add(0.5, 0.5, 0.5))
                .extra(0.1).data(type.createBlockData()).offset(0.3, 0.3, 0.3).receivers(world.getNearbyPlayers(location, 32.0)))
        }
    }

    internal fun inspect(location: Location): CompletableFuture<Pair<Boolean, Int>> {
        val epoch = generation
        val scan = leafChecker.beginInspection(location)
        return ARC.instance.nativeWork.submit({ generation == epoch }, scan::step).thenApply { scan.result() }
    }

    private fun observe(result: CompletableFuture<*>, epoch: Long, release: () -> Unit) {
        result.whenComplete { _, failure ->
            if (generation == epoch) release()
            if (failure != null && failure !is CancellationException) error("Leaf decay native work failed", failure)
        }
    }

    @JvmStatic
    fun markAsPlayerPlaced(block: Block) {
        if (!leafMaterials.contains(block.type) && !trunkMaterials.contains(block.type)) return
        val data = CustomBlockData(block, ARC.instance)
        data.set(playerPlacedKey, PersistentDataType.BOOLEAN, true)
    }

    @JvmStatic
    fun clearPlayerPlaced(block: Block) {
        awaitingTagClearing.add(block)
    }

    private const val MAX_CHUNKS_PER_TICK = 5
    private const val MAX_TAG_CLEARS_PER_TICK = 200
}

internal class LeafChunkQueue {
    private val chunks =
        ConcurrentSkipListSet(
            compareBy<Chunk>({ it.world.uid }, { it.x }, { it.z }),
        )

    fun add(chunk: Chunk) {
        chunks.add(chunk)
    }

    fun poll(): Chunk? = chunks.pollFirst()

    fun isEmpty(): Boolean = chunks.isEmpty()

    fun size(): Int = chunks.size

    fun clear() {
        chunks.clear()
    }
}
