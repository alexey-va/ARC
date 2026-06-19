package ru.arc.leafdecay

import com.destroystokyo.paper.ParticleBuilder
import com.jeff_media.customblockdata.CustomBlockData
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.persistence.PersistentDataType
import ru.arc.ARC
import ru.arc.configs.Config
import ru.arc.configs.ConfigManager
import ru.arc.core.ScheduledTask
import ru.arc.core.repeating
import ru.arc.core.repeatingAsync
import ru.arc.core.ticks
import ru.arc.util.ParticleManager
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.ConcurrentSkipListSet
import java.util.concurrent.atomic.AtomicInteger

object LeafDecayManager {

    lateinit var leafChecker: LeafChecker

    private val config: Config get() = ConfigManager.ofModule(ARC.instance.dataPath, "leafdecay.yml")

    private var checkTask: ScheduledTask? = null
    private var decayTask: ScheduledTask? = null
    private var tagClearTask: ScheduledTask? = null

    private val chunkQueue = ConcurrentSkipListSet<org.bukkit.Chunk>(
        compareBy({ it.x }, { it.z })
    )
    private val leafQueue = ConcurrentLinkedDeque<Location>()
    private val awaitingTagClearing = ConcurrentLinkedDeque<Block>()

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
        checkTask?.cancel()
        decayTask?.cancel()
        tagClearTask?.cancel()
        checkTask = null
        decayTask = null
        tagClearTask = null
    }

    private fun start() {
        cancel()
        val counter = AtomicInteger()

        checkTask = repeating(period = checkInterval.ticks, delay = 60.ticks) {
            var count = 0
            if (leafQueue.size <= 10000) {
                while (chunkQueue.isNotEmpty()) {
                    if (count++ > 5) break
                    val chunk = chunkQueue.pollFirst() ?: break
                    if (worlds.contains(chunk.world.name)) checkChunk(chunk)
                }
            }
            if (counter.incrementAndGet() >= checkWorldEach) {
                counter.set(0)
                for (worldName in worlds) {
                    Bukkit.getWorld(worldName)?.let { pollChunksInWorld(it) }
                }
            }
        }

        decayTask = repeating(period = decayInterval.ticks, delay = 60.ticks) {
            repeat(leafBatchSize.toInt()) {
                val location = leafQueue.poll() ?: return@repeating
                val block = location.block
                if (block.type.isAir) return@repeating
                val type = block.type
                block.breakNaturally()
                if (playSound) block.world.playSound(block.location, Sound.BLOCK_GRASS_BREAK, 1.0f, 1.0f)
                if (playParticles) {
                    val nearbyPlayers: Collection<Player> = block.world.getNearbyPlayers(block.location, 32.0)
                    ParticleManager.queue(
                        ParticleBuilder(Particle.BLOCK)
                            .count(2)
                            .location(block.location.add(0.5, 0.5, 0.5))
                            .extra(0.1)
                            .data(type.createBlockData())
                            .offset(0.3, 0.3, 0.3)
                            .receivers(nearbyPlayers)
                    )
                }
            }
        }

        tagClearTask = repeatingAsync(period = 1.ticks, delay = 60.ticks) {
            while (awaitingTagClearing.isNotEmpty()) {
                val block = awaitingTagClearing.pollFirst() ?: return@repeatingAsync
                val data = CustomBlockData(block, ARC.instance)
                if (data.has(playerPlacedKey)) {
                    data.remove(playerPlacedKey)
                }
            }
        }
    }

    private fun checkChunk(chunk: org.bukkit.Chunk) {
        val world = chunk.world
        val neighborsLoaded = world.isChunkLoaded(chunk.x + 1, chunk.z) &&
            world.isChunkLoaded(chunk.x - 1, chunk.z) &&
            world.isChunkLoaded(chunk.x, chunk.z + 1) &&
            world.isChunkLoaded(chunk.x, chunk.z - 1)
        if (!neighborsLoaded) return
        if (::leafChecker.isInitialized) leafQueue.addAll(leafChecker.checkChunk(chunk))
    }

    private fun pollChunksInWorld(world: World) {
        if (leafQueue.size > 10000) return
        world.players
            .map { it.location.chunk }
            .flatMap { chunk ->
                listOf(
                    chunk,
                    world.getChunkAt(chunk.x + 1, chunk.z),
                    world.getChunkAt(chunk.x - 1, chunk.z),
                    world.getChunkAt(chunk.x, chunk.z + 1),
                    world.getChunkAt(chunk.x, chunk.z - 1),
                    world.getChunkAt(chunk.x + 1, chunk.z + 1),
                    world.getChunkAt(chunk.x - 1, chunk.z - 1),
                    world.getChunkAt(chunk.x + 1, chunk.z - 1),
                    world.getChunkAt(chunk.x - 1, chunk.z + 1)
                )
            }
            .toSet()
            .let { chunkQueue.addAll(it) }
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
}
