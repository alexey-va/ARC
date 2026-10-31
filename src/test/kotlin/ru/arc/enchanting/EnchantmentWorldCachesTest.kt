package ru.arc.enchanting

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.GameMode
import org.bukkit.HeightMap
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Server
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.Chest
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataContainer
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID
import java.util.logging.Logger

class EnchantmentWorldCachesTest : StringSpec({
    "chunk selection is stable and keeps negative coordinates and every neighbour inside its chunk" {
        val world = UUID.fromString("b490ca0c-9c54-4c81-aa89-34da92e4aef4")
        val columns = mutableSetOf<EnchantmentWorldCacheColumn>()
        for (x in listOf(-1000, -2, -1, 0, 1, 1000)) for (z in listOf(-1000, -1, 0, 1000)) {
            val column = requireNotNull(enchantmentWorldCacheColumn(world, x, z, 1))
            column shouldBe enchantmentWorldCacheColumn(world, x, z, 1)
            (column.x shr 4) shouldBe x
            (column.z shr 4) shouldBe z
            ((column.x and 15) in 2..13) shouldBe true
            ((column.z and 15) in 2..13) shouldBe true
            for (offset in -1..1) {
                ((column.x + offset) shr 4) shouldBe x
                ((column.z + offset) shr 4) shouldBe z
            }
            columns += column
        }
        columns.size shouldBe 24
        val chosen = (0..1023).mapNotNull { enchantmentWorldCacheColumn(world, it, -1, 128) }
        (chosen.size in 1..100) shouldBe true
        chosen shouldBe (0..1023).mapNotNull { enchantmentWorldCacheColumn(world, it, -1, 128) }
        shouldThrow<IllegalArgumentException> { enchantmentWorldCacheColumn(world, 0, 0, 0) }
    }

    "only the explicit natural ground palette is accepted" {
        for (ground in listOf(Material.STONE, Material.DEEPSLATE, Material.GRASS_BLOCK, Material.SAND)) {
            isEnchantmentWorldCacheGround(ground) shouldBe true
        }
        for (ground in listOf(Material.WATER, Material.LAVA, Material.OAK_LEAVES, Material.POWDER_SNOW, Material.OAK_PLANKS, Material.CHEST)) {
            isEnchantmentWorldCacheGround(ground) shouldBe false
        }
    }

    "movement inside a chunk, cancelled events and ineligible players do no terrain work" {
        MockBukkitTestRuntime.open().use {
            CacheFixture().use { f ->
                val player = f.player(0, 0)
                val location = player.location
                f.controller.onMove(PlayerMoveEvent(player, location, location.clone().add(1.0, 0.0, 0.0)))
                f.enter(player, cancelled = true)
                for (mode in listOf(GameMode.CREATIVE, GameMode.ADVENTURE, GameMode.SPECTATOR)) {
                    every { player.gameMode } returns mode
                    f.enter(player)
                }
                f.scheduler.tick(3)
                f.heights shouldBe 0
                f.markers.size shouldBe 0
                f.scheduler.pendingCount() shouldBe 0
            }
        }
    }

    "a surface probe commits its marker before applying one named chest with one AE box" {
        MockBukkitTestRuntime.open().use {
            CacheFixture().use { f ->
                val player = f.player(-1, -2)
                f.enter(player)
                f.markers.size shouldBe 0
                f.scheduler.tick()
                f.heights shouldBe 1
                f.markers.keys shouldBe setOf(-1 to -2)
                f.applied.size shouldBe 1
                val chest = f.applied.single()
                chest.name shouldBe Component.text("Таинственный тайник чар")
                chest.items.filterNotNull().filterNot { it.type.isAir } shouldBe listOf(f.reward)
                chest.items[13] shouldBe f.reward
                (chest.items[13] === f.reward) shouldBe false
                chest.markerExisted shouldBe true
                verify(exactly = 0) { f.world.getChunkAtAsync(any<Int>(), any<Int>(), any<Boolean>()) }
                f.blockReads.all { (x, _, z) -> (x shr 4) == -1 && (z shr 4) == -2 } shouldBe true
            }
        }
    }

    "empty broken and missing chests never regenerate even in a fresh controller" {
        MockBukkitTestRuntime.open().use {
            CacheFixture().use { f ->
                val player = f.player(2, 3)
                f.enter(player)
                f.scheduler.tick()
                f.applied.size shouldBe 1
                val target = f.applied.single().location
                f.types[Triple(target.blockX, target.blockY, target.blockZ)] = Material.AIR
                f.controller.close()
                f.controller = f.newController()
                repeat(3) { f.enter(player); f.scheduler.tick() }
                f.applied.size shouldBe 1
                f.heights shouldBe 1
            }
        }
    }

    "underground visits defer the attempt until someone crosses the same chunk on the surface" {
        MockBukkitTestRuntime.open().use {
            CacheFixture().use { f ->
                val player = f.player(0, 0, y = 20.0)
                f.enter(player)
                f.scheduler.tick()
                f.markers.size shouldBe 0
                f.applied.size shouldBe 0
                f.locations[player.uniqueId] = player.location.clone().apply { y = 65.0 }
                f.enter(player)
                f.scheduler.tick()
                f.applied.size shouldBe 1
                f.heights shouldBe 2
            }
        }
    }

    "unsafe terrain unavailable provenance and protected volume are permanent failed surface attempts" {
        MockBukkitTestRuntime.open().use {
            for (case in listOf("placed", "unknown-protection", "ground-claim", "head-claim", "water", "grass", "container", "roof", "border")) {
                CacheFixture().use { f ->
                    val column = f.column(0, 0)
                    when (case) {
                        "placed" -> f.natural = false
                        "unknown-protection" -> f.unclaimed = { null }
                        "ground-claim" -> f.unclaimed = { it.blockY != 64 }
                        "head-claim" -> f.unclaimed = { it.blockY != 66 }
                        "water" -> f.types[Triple(column.x, 64, column.z)] = Material.WATER
                        "grass" -> f.types[Triple(column.x, 65, column.z)] = Material.SHORT_GRASS
                        "container" -> f.types[Triple(column.x + 1, 65, column.z)] = Material.BARREL
                        "roof" -> f.types[Triple(column.x, 64, column.z)] = Material.OAK_PLANKS
                        "border" -> f.insideBorder = false
                    }
                    f.enter(f.player(0, 0))
                    f.scheduler.tick()
                    f.markers.keys shouldBe setOf(0 to 0)
                    f.applied.size shouldBe 0
                    f.rewardCalls shouldBe 0
                }
            }
        }
    }

    "invalid empty overflow and unavailable presets never create a chest or drop a replacement" {
        MockBukkitTestRuntime.open().use {
            for (case in listOf("empty", "air", "amount", "overflow", "not-ae", "wrong-key", "reader-failure", "unavailable")) {
                CacheFixture().use { f ->
                    if (case == "wrong-key") f.readTreasureKey = { "sf" }
                    if (case == "reader-failure") f.readTreasureKey = { error("Custom data unavailable") }
                    f.resolve = {
                        when (case) {
                            "empty" -> emptyList()
                            "air" -> listOf(ItemStack(Material.AIR))
                            "amount" -> listOf(f.reward.clone().apply { amount = 2 })
                            "overflow" -> listOf(f.reward, f.reward)
                            "not-ae", "wrong-key" -> listOf(ItemStack(Material.PAPER))
                            "reader-failure" -> listOf(f.reward)
                            else -> error("catalogue unavailable")
                        }
                    }
                    f.enter(f.player(0, 0))
                    f.scheduler.tick()
                    f.markers.keys shouldBe setOf(0 to 0)
                    f.applied.size shouldBe 0
                    f.types.size shouldBe 0
                    verify(exactly = 0) { f.world.dropItemNaturally(any(), any()) }
                }
            }
        }
    }

    "final revalidation preserves a block changed while preparing the reward" {
        MockBukkitTestRuntime.open().use {
            CacheFixture().use { f ->
                val column = f.column(0, 0)
                f.resolve = {
                    f.types[Triple(column.x, 65, column.z)] = Material.DIAMOND_BLOCK
                    listOf(f.reward)
                }
                f.enter(f.player(0, 0))
                f.scheduler.tick()
                f.applied.size shouldBe 0
                f.types[Triple(column.x, 65, column.z)] shouldBe Material.DIAMOND_BLOCK
                f.markers.keys shouldBe setOf(0 to 0)
            }
        }
    }

    "queue deduplicates bounds pending work to 64 and processes one candidate per tick" {
        MockBukkitTestRuntime.open().use {
            CacheFixture().use { f ->
                f.natural = false
                val players = (0..64).map { f.player(it, 0) }
                players.forEach { f.enter(it); f.enter(it) }
                f.scheduler.pendingCount() shouldBe 1
                f.heights shouldBe 0
                f.scheduler.tick()
                f.heights shouldBe 1
                f.scheduler.tick(63)
                f.heights shouldBe 64
                f.markers.size shouldBe 64
                ((64 to 0) in f.markers) shouldBe false
                f.enter(players.last())
                f.scheduler.tick()
                f.heights shouldBe 65
                f.markers.size shouldBe 65
                f.applied.size shouldBe 0
            }
        }
    }

    "unloaded chunks and players who left are skipped without creating markers or loading terrain" {
        MockBukkitTestRuntime.open().use {
            for (case in listOf("unloaded", "left", "offline")) CacheFixture().use { f ->
                val player = f.player(0, 0)
                f.enter(player)
                when (case) {
                    "unloaded" -> f.loaded = false
                    "left" -> f.locations[player.uniqueId] = Location(f.world, 80.0, 65.0, 80.0)
                    "offline" -> every { player.isOnline } returns false
                }
                f.scheduler.tick()
                f.heights shouldBe 0
                f.markers.size shouldBe 0
                verify(exactly = 0) { f.world.getChunkAt(any<Int>(), any<Int>()) }
            }
        }
    }

    "shutdown cancels queued work and a close during preparation prevents a stale write" {
        MockBukkitTestRuntime.open().use {
            for (duringPreparation in listOf(false, true)) CacheFixture().use { f ->
                if (duringPreparation) f.resolve = { f.controller.close(); listOf(f.reward) }
                f.enter(f.player(0, 0))
                if (!duringPreparation) f.controller.close()
                f.scheduler.tick(3)
                f.applied.size shouldBe 0
                f.scheduler.pendingCount() shouldBe 0
                f.enter(f.player(1, 0))
                f.scheduler.tick()
                f.applied.size shouldBe 0
            }
        }
    }

    "settings snapshots cannot change a running generation through a mutable world set" {
        MockBukkitTestRuntime.open().use {
            val worlds = mutableSetOf("survival")
            CacheFixture(EnchantmentWorldCacheSettings(true, true, worlds, 1)).use { f ->
                worlds.clear()
                f.controller.settingsSnapshot().worldNames shouldBe setOf("survival")
                shouldThrow<UnsupportedOperationException> {
                    (f.controller.settingsSnapshot().worldNames as MutableSet<String>).clear()
                }
                f.enter(f.player(0, 0))
                f.scheduler.tick()
                f.applied.size shouldBe 1
            }
        }
    }
})

/** Records the verified Paper snapshot-copy boundary; MockBukkit 4.116.1 does not implement copy(Location). */
private class CacheFixture(
    val settings: EnchantmentWorldCacheSettings = EnchantmentWorldCacheSettings(true, true, setOf("survival"), 1),
) : AutoCloseable {
    data class Applied(val location: Location, val name: Component?, val items: List<ItemStack?>, val markerExisted: Boolean)
    val worldId = UUID.fromString("cce4558f-c5ef-4866-9e37-168b648927b2")
    val world = mockk<World>()
    val plugin = mockk<Plugin>()
    val server = mockk<Server>()
    val scheduler = TestTaskScheduler()
    val locations = mutableMapOf<UUID, Location>()
    val players = mutableMapOf<UUID, Player>()
    val markers = mutableMapOf<Pair<Int, Int>, Byte>()
    val types = mutableMapOf<Triple<Int, Int, Int>, Material>()
    val blockReads = mutableListOf<Triple<Int, Int, Int>>()
    val applied = mutableListOf<Applied>()
    val reward = ItemStack(Material.PAPER)
    var readTreasureKey: (ItemStack) -> String? = { if (it === reward) "ae" else null }
    var resolve: () -> List<ItemStack> = { listOf(reward) }
    var unclaimed: (Location) -> Boolean? = { true }
    var natural = true
    var loaded = true
    var insideBorder = true
    var heights = 0
    var rewardCalls = 0
    var controller: EnchantmentWorldCaches

    init {
        every { plugin.name } returns "WorldCachesTest"
        every { plugin.namespace() } returns "worldcachestest"
        every { plugin.server } returns server
        every { plugin.logger } returns mockk<Logger>(relaxed = true)
        every { server.getWorld(worldId) } returns world
        every { server.getPlayer(any<UUID>()) } answers { players[firstArg()] }
        every { world.uid } returns worldId
        every { world.name } returns "survival"
        every { world.minHeight } returns -64
        every { world.maxHeight } returns 320
        every { world.isChunkLoaded(any<Int>(), any<Int>()) } answers { loaded }
        every { world.getChunkAt(any<Int>(), any<Int>()) } answers { chunk(firstArg(), secondArg()) }
        every { world.getHighestBlockYAt(any<Int>(), any<Int>(), HeightMap.WORLD_SURFACE) } answers { heights++; 64 }
        every { world.getBlockAt(any<Int>(), any<Int>(), any<Int>()) } answers { block(firstArg(), secondArg(), thirdArg()) }
        every { world.worldBorder.isInside(any()) } answers { insideBorder }
        controller = newController()
    }

    fun column(x: Int, z: Int): EnchantmentWorldCacheColumn = requireNotNull(enchantmentWorldCacheColumn(worldId, x, z, 1))

    fun player(chunkX: Int, chunkZ: Int, y: Double = 65.0): Player {
        val player = mockk<Player>()
        val id = UUID.randomUUID()
        val column = column(chunkX, chunkZ)
        locations[id] = Location(world, column.x + 0.5, y, column.z + 0.5)
        players[id] = player
        every { player.uniqueId } returns id
        every { player.location } answers { locations.getValue(id).clone() }
        every { player.gameMode } returns GameMode.SURVIVAL
        every { player.isOnline } returns true
        every { player.isDead } returns false
        return player
    }

    fun enter(player: Player, cancelled: Boolean = false) {
        val to = player.location
        controller.onMove(PlayerMoveEvent(player, to.clone().subtract(16.0, 0.0, 0.0), to).also { it.isCancelled = cancelled })
    }

    fun newController(): EnchantmentWorldCaches = EnchantmentWorldCaches(
        settings = settings,
        plugin = plugin,
        isNaturalBlock = { natural },
        isUnclaimed = { unclaimed(it) },
        resolveReward = { rewardCalls++; resolve() },
        scheduler = scheduler,
        createChestState = ::chest,
        readTreasureKey = { this.readTreasureKey(it) },
    )

    private fun chunk(x: Int, z: Int): Chunk {
        val key = x to z
        val data = mockk<PersistentDataContainer>()
        every { data.has(any<NamespacedKey>()) } answers { key in markers }
        every { data.set(any<NamespacedKey>(), PersistentDataType.BYTE, any<Byte>()) } answers { markers[key] = thirdArg() }
        return mockk<Chunk>().also { every { it.persistentDataContainer } returns data }
    }

    private fun block(x: Int, y: Int, z: Int): Block {
        val key = Triple(x, y, z)
        blockReads += key
        return mockk<Block>().also { block ->
            every { block.type } answers { types[key] ?: if (y == 64) Material.GRASS_BLOCK else Material.AIR }
        }
    }

    private fun chest(location: Location): Chest {
        val inventory = Bukkit.createInventory(null, 27)
        var name: Component? = null
        return mockk<Chest>().also { chest ->
            every { chest.type } returns Material.CHEST
            every { chest.isPlaced } returns true
            every { chest.location } returns location.clone()
            every { chest.lootTable = null } answers { }
            every { chest.customName(any()) } answers { name = firstArg() }
            every { chest.snapshotInventory } returns inventory
            every { chest.update(true, false) } answers {
                applied += Applied(location.clone(), name, inventory.contents.map { it?.clone() }, (location.blockX shr 4 to (location.blockZ shr 4)) in markers)
                types[Triple(location.blockX, location.blockY, location.blockZ)] = Material.CHEST
                true
            }
        }
    }

    override fun close() = controller.close()
}
