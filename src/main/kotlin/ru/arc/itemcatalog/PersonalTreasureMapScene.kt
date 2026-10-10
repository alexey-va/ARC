package ru.arc.itemcatalog

import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Tag
import org.bukkit.entity.Display
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.arc.paper.display.PacketDisplay
import ru.arc.paper.display.PaperPacketDisplays
import java.util.UUID
import kotlin.math.abs
import kotlin.math.floor

internal data class PersonalTreasureMapCacheSurface(
    val groundY: Int,
    val insideWorldBorder: Boolean,
    val safeGround: Boolean,
    val clearAbove: Boolean,
)

internal data class PersonalTreasureMapCacheDecoration(
    val offsetX: Int,
    val offsetZ: Int,
    val y: Int,
    val material: Material,
)

private data class PersonalTreasureMapCacheDecorationSpec(
    val offsetX: Int,
    val offsetZ: Int,
    val material: Material,
)

private val personalTreasureMapCacheDecorationSpecs = listOf(
    PersonalTreasureMapCacheDecorationSpec(-1, 0, Material.MOSSY_COBBLESTONE),
    PersonalTreasureMapCacheDecorationSpec(1, 0, Material.MOSSY_STONE_BRICKS),
    PersonalTreasureMapCacheDecorationSpec(-1, -1, Material.OAK_PLANKS),
    PersonalTreasureMapCacheDecorationSpec(0, -1, Material.OAK_PLANKS),
    PersonalTreasureMapCacheDecorationSpec(1, -1, Material.OAK_PLANKS),
    PersonalTreasureMapCacheDecorationSpec(0, 1, Material.LANTERN),
)

/** Pure layout rule; missing, blocked, unsafe or off-border terrain cells simply omit that decoration. */
internal fun personalTreasureMapCacheDecorations(
    baseFootY: Int,
    surfaces: Map<Pair<Int, Int>, PersonalTreasureMapCacheSurface>,
): List<PersonalTreasureMapCacheDecoration> = personalTreasureMapCacheDecorationSpecs.mapNotNull { spec ->
    val surface = surfaces[spec.offsetX to spec.offsetZ] ?: return@mapNotNull null
    if (!surface.insideWorldBorder || !surface.safeGround || !surface.clearAbove) return@mapNotNull null
    if (abs(surface.groundY - (baseFootY - 1)) > 1) return@mapNotNull null
    PersonalTreasureMapCacheDecoration(spec.offsetX, spec.offsetZ, surface.groundY + 1, spec.material)
}

/** Owner-only cache scene. Every handle is a packet display and has exactly one viewer. */
internal class PacketPersonalTreasureMapMarker(plugin: Plugin) : PersonalTreasureMapMarker {
    private data class MarkerKey(val worldId: UUID, val x: Double, val y: Double, val z: Double)
    private data class MarkerScene(val key: MarkerKey, val displays: List<PacketDisplay>)

    private val displays = PaperPacketDisplays(plugin, "personal-treasure-map")
    private val scenes = mutableMapOf<UUID, MarkerScene>()
    private var closed = false

    override fun show(player: Player, destination: PersonalTreasureMapDestination) {
        if (closed) return
        val playerId = player.uniqueId
        val key = MarkerKey(player.world.uid, destination.x, destination.y, destination.z)
        if (scenes[playerId]?.key == key) return
        clear(playerId)

        val world = player.world
        val footY = floor(destination.y).toInt()
        val blockX = floor(destination.x).toInt()
        val blockZ = floor(destination.z).toInt()
        val created = mutableListOf<PacketDisplay>()
        try {
            val chest = displays.spawnItem(
                Location(world, blockX + 0.5, footY + 0.5, blockZ + 0.5),
                ItemStack(Material.CHEST),
            ).apply {
                isVisibleByDefault = false
                billboard = Display.Billboard.FIXED
                itemDisplayTransform = ItemDisplay.ItemDisplayTransform.FIXED
                viewRange = 1f
                displayWidth = 1.1f
                displayHeight = 1.1f
                shadowRadius = 0f
                shadowStrength = 0f
                transformation = Transformation(
                    Vector3f(), Quaternionf(), Vector3f(2f), Quaternionf(),
                )
            }
            created += chest

            val decorations = personalTreasureMapCacheDecorations(
                footY,
                captureSurfaces(world, blockX, blockZ, footY),
            )
            decorations.forEach { decoration ->
                val decorationDisplay = displays.spawnBlock(
                    Location(
                        world,
                        (blockX + decoration.offsetX).toDouble(),
                        decoration.y.toDouble(),
                        (blockZ + decoration.offsetZ).toDouble(),
                    ),
                    decoration.material.createBlockData(),
                ).apply {
                    isVisibleByDefault = false
                    billboard = Display.Billboard.FIXED
                    viewRange = 1f
                    displayWidth = 1f
                    displayHeight = 1f
                    shadowRadius = 0f
                }
                created += decorationDisplay
            }

            created.forEach { it.showTo(player) }
            scenes[playerId] = MarkerScene(key, created.toList())
        } catch (failure: Throwable) {
            created.forEach(PacketDisplay::remove)
            throw failure
        }
    }

    private fun captureSurfaces(
        world: org.bukkit.World,
        baseX: Int,
        baseZ: Int,
        baseFootY: Int,
    ): Map<Pair<Int, Int>, PersonalTreasureMapCacheSurface> {
        val result = linkedMapOf<Pair<Int, Int>, PersonalTreasureMapCacheSurface>()
        personalTreasureMapCacheDecorationSpecs.forEach { spec ->
            val x = baseX + spec.offsetX
            val z = baseZ + spec.offsetZ
            val center = Location(world, x + 0.5, baseFootY.toDouble(), z + 0.5)
            if (!world.worldBorder.isInside(center)) return@forEach
            if (!world.isChunkLoaded(x shr 4, z shr 4)) return@forEach

            // Read only this loaded column and a one-block terrain band around the target.
            val groundYs = listOf(baseFootY - 1, baseFootY, baseFootY - 2)
            for (groundY in groundYs) {
                if (groundY < world.minHeight || groundY + 2 >= world.maxHeight) continue
                val ground = world.getBlockAt(x, groundY, z)
                if (!isSafeGround(ground.type)) continue
                val clearAbove = (1..2).all { dy -> isSafeAir(world.getBlockAt(x, groundY + dy, z)) }
                if (!clearAbove) continue
                result[spec.offsetX to spec.offsetZ] = PersonalTreasureMapCacheSurface(
                    groundY = groundY,
                    insideWorldBorder = true,
                    safeGround = true,
                    clearAbove = true,
                )
                break
            }
        }
        return result
    }

    private fun isSafeGround(material: Material): Boolean =
        material.isSolid && !material.isAir && !Tag.LEAVES.isTagged(material) && material !in UNSAFE_MATERIALS

    private fun isSafeAir(block: org.bukkit.block.Block): Boolean =
        block.isPassable && !block.isLiquid && block.type !in UNSAFE_MATERIALS

    override fun clear(playerId: UUID) {
        scenes.remove(playerId)?.displays?.forEach(PacketDisplay::remove)
    }

    override fun close() {
        if (closed) return
        closed = true
        scenes.keys.toList().forEach(::clear)
        displays.close()
    }

    private companion object {
        val UNSAFE_MATERIALS = setOf(
            Material.CACTUS,
            Material.CAMPFIRE,
            Material.FIRE,
            Material.LAVA,
            Material.MAGMA_BLOCK,
            Material.POWDER_SNOW,
            Material.SOUL_CAMPFIRE,
            Material.SOUL_FIRE,
            Material.SWEET_BERRY_BUSH,
            Material.WITHER_ROSE,
        )
    }
}
