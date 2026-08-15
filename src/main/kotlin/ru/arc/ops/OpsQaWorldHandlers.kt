package ru.arc.ops

import com.jeff_media.customblockdata.CustomBlockData
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Difficulty
import org.bukkit.GameRule
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.WorldCreator
import org.bukkit.WorldType
import org.bukkit.block.Block
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import ru.arc.ARC
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

object OpsQaWorldHandlers {
    const val WORLD_NAME = "arc_qa_flat"
    const val FIXTURE_VERSION = 2
    const val FLOOR_Y = 63
    private const val MARKER_SCHEMA = 1
    internal val platformX = -8..8
    internal val platformZ = -5..5

    internal val fixtureBlocks =
        listOf(
            QaFixtureBlock("trail_target", 0, FLOOR_Y, 0, Material.GRASS_BLOCK),
            QaFixtureBlock("grass_sample", -3, FLOOR_Y, 3, Material.GRASS_BLOCK),
            QaFixtureBlock("dirt_sample", 0, FLOOR_Y, 3, Material.DIRT),
            QaFixtureBlock("coarse_dirt_sample", 3, FLOOR_Y, 3, Material.COARSE_DIRT),
            QaFixtureBlock("dirt_path_sample", 6, FLOOR_Y, 3, Material.DIRT_PATH),
        )

    internal val toolSlots =
        linkedMapOf(
            5 to QaToolSpec("ordinary_stick", Material.STICK, null),
            6 to QaToolSpec("inspect", Material.STICK, "inspect"),
            7 to QaToolSpec("advance", Material.IRON_SHOVEL, "advance"),
        )

    private val fixturesByCoordinate = fixtureBlocks.associateBy { it.x to it.z }

    fun status(): Map<String, Any?> = OpsBukkitSync.call { statusSync(Bukkit.getWorld(WORLD_NAME)) }

    fun prepare(
        playerName: String?,
        config: OpsHttpConfig,
    ): Map<String, Any?> =
        OpsBukkitSync.call(timeoutSeconds = 60) {
            playerName?.let { requireAllowedPlayer(it, config) }
            val (world, created) = ensureWorld()
            configureWorld(world)
            resetFixture(world)
            val player = playerName?.let { onlinePlayer(it) }
            player?.let { teleport(it, world) }
            statusSync(world) +
                mapOf(
                    "created" to created,
                    "prepared" to true,
                    "teleportedPlayer" to player?.name,
                )
        }

    fun teleport(
        playerName: String,
        config: OpsHttpConfig,
    ): Map<String, Any?> =
        OpsBukkitSync.call {
            requireAllowedPlayer(playerName, config)
            val world = Bukkit.getWorld(WORLD_NAME) ?: throw IllegalStateException("QA world is not loaded; prepare it first")
            verifyMarker(world)
            val player = onlinePlayer(playerName)
            teleport(player, world)
            mapOf(
                "world" to WORLD_NAME,
                "player" to player.name,
                "teleported" to true,
                "position" to locationSummary(player.location),
            )
        }

    fun equip(
        playerName: String,
        config: OpsHttpConfig,
    ): Map<String, Any?> =
        OpsBukkitSync.call {
            requireAllowedPlayer(playerName, config)
            val world = Bukkit.getWorld(WORLD_NAME) ?: throw IllegalStateException("QA world is not loaded; prepare it first")
            verifyMarker(world)
            val player = onlinePlayer(playerName)
            check(player.world.uid == world.uid) { "Player '$playerName' must be inside '$WORLD_NAME' before equipping QA tools" }
            val occupied = toolSlots.keys.filter { slot -> player.inventory.getItem(slot)?.type?.isAir == false }
            check(occupied.isEmpty()) { "QA hotbar slots are occupied: ${occupied.sorted().joinToString()}" }
            val trailsPlugin =
                Bukkit.getPluginManager().getPlugin("Trails")
                    ?.takeIf { it.isEnabled }
                    ?: throw IllegalStateException("Trails is not enabled")
            toolSlots.forEach { (slot, spec) -> player.inventory.setItem(slot, qaTool(spec, trailsPlugin)) }
            mapOf(
                "world" to WORLD_NAME,
                "player" to player.name,
                "equipped" to true,
                "items" to
                    toolSlots.map { (slot, spec) ->
                        mapOf(
                            "id" to spec.id,
                            "quickbarSlot" to slot,
                            "material" to spec.material.name,
                            "taggedKind" to spec.taggedKind,
                        )
                    },
            )
        }

    internal fun requireAllowedPlayer(
        playerName: String,
        config: OpsHttpConfig,
    ) {
        require(PLAYER_NAME.matches(playerName)) { "Invalid Minecraft player name" }
        require(playerName.lowercase() in config.qaWorldAllowedPlayers) {
            "Player '$playerName' is not in qa-world-allowed-players"
        }
    }

    private fun ensureWorld(): Pair<World, Boolean> {
        Bukkit.getWorld(WORLD_NAME)?.let { world ->
            verifyMarker(world)
            return world to false
        }

        val worldFolder = Bukkit.getWorldContainer().toPath().resolve(WORLD_NAME).normalize()
        val existed = Files.exists(worldFolder)
        if (existed && !Files.isRegularFile(markerPath())) {
            throw IllegalStateException("Refusing to load unowned world folder '$WORLD_NAME' without an ARC QA marker")
        }
        val world =
            WorldCreator(WORLD_NAME)
                .environment(World.Environment.NORMAL)
                .type(WorldType.FLAT)
                .generateStructures(false)
                .seed(0L)
                .createWorld()
                ?: throw IllegalStateException("Paper could not create or load QA world '$WORLD_NAME'")
        if (existed) {
            verifyMarker(world)
        } else {
            writeMarker(world)
        }
        return world to !existed
    }

    private fun configureWorld(world: World) {
        world.difficulty = Difficulty.PEACEFUL
        world.time = 6000L
        world.setStorm(false)
        world.isThundering = false
        world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false)
        world.setGameRule(GameRule.DO_WEATHER_CYCLE, false)
        world.setGameRule(GameRule.DO_MOB_SPAWNING, false)
        world.setGameRule(GameRule.KEEP_INVENTORY, true)
        world.setGameRule(GameRule.RANDOM_TICK_SPEED, 0)
        world.worldBorder.setCenter(0.0, 0.0)
        world.worldBorder.size = 64.0
        world.setSpawnLocation(spawnLocation(world))
    }

    private fun resetFixture(world: World) {
        val trailsPlugin = Bukkit.getPluginManager().getPlugin("Trails")
        for (x in platformX) {
            for (z in platformZ) {
                val block = world.getBlockAt(x, FLOOR_Y, z)
                if (trailsPlugin != null) CustomBlockData(block, trailsPlugin).clear()
                block.setType(expectedSurfaceMaterial(x, z), false)
                for (y in FLOOR_Y + 1..FLOOR_Y + 4) {
                    world.getBlockAt(x, y, z).setType(Material.AIR, false)
                }
            }
        }
    }

    private fun statusSync(world: World?): Map<String, Any?> {
        val marker = Files.isRegularFile(markerPath())
        if (world == null) {
            return mapOf(
                "world" to WORLD_NAME,
                "fixtureVersion" to FIXTURE_VERSION,
                "loaded" to false,
                "owned" to marker,
                "prepared" to false,
                "blocks" to emptyList<Map<String, Any?>>(),
            )
        }
        verifyMarker(world)
        val blocks = fixtureBlocks.map { fixture -> fixtureSummary(world, fixture) }
        val surfaceBlocks =
            platformX.flatMap { x ->
                platformZ.map { z ->
                    val block = world.getBlockAt(x, FLOOR_Y, z)
                    QaSurfaceBlock(x, z, expectedSurfaceMaterial(x, z), block.type, trailData(block))
                }
            }
        val materialMismatches = surfaceBlocks.filter { it.material != it.expectedMaterial }
        val trailDataBlocks = surfaceBlocks.filter { it.trailData != null }
        return mapOf(
            "world" to WORLD_NAME,
            "fixtureVersion" to FIXTURE_VERSION,
            "loaded" to true,
            "owned" to true,
            "prepared" to (materialMismatches.isEmpty() && trailDataBlocks.isEmpty()),
            "spawn" to locationSummary(spawnLocation(world)),
            "surface" to
                mapOf(
                    "xMin" to platformX.first,
                    "xMax" to platformX.last,
                    "zMin" to platformZ.first,
                    "zMax" to platformZ.last,
                    "floorY" to FLOOR_Y,
                    "defaultMaterial" to Material.GRASS_BLOCK.name,
                    "blockCount" to surfaceBlocks.size,
                    "materialMismatchCount" to materialMismatches.size,
                    "trailDataBlockCount" to trailDataBlocks.size,
                    "mismatchSamples" to materialMismatches.take(12).map(::surfaceSummary),
                    "trailDataSamples" to trailDataBlocks.take(12).map(::surfaceSummary),
                ),
            "blocks" to blocks,
        )
    }

    internal fun expectedSurfaceMaterial(
        x: Int,
        z: Int,
    ): Material = fixturesByCoordinate[x to z]?.material ?: Material.GRASS_BLOCK

    private fun surfaceSummary(block: QaSurfaceBlock): Map<String, Any?> =
        mapOf(
            "x" to block.x,
            "y" to FLOOR_Y,
            "z" to block.z,
            "expectedMaterial" to block.expectedMaterial.name,
            "material" to block.material.name,
            "trailData" to block.trailData,
        )

    private fun fixtureSummary(
        world: World,
        fixture: QaFixtureBlock,
    ): Map<String, Any?> {
        val block = fixture.block(world)
        return mapOf(
            "id" to fixture.id,
            "x" to fixture.x,
            "y" to fixture.y,
            "z" to fixture.z,
            "expectedMaterial" to fixture.material.name,
            "material" to block.type.name,
            "matchesExpected" to (block.type == fixture.material),
            "trailData" to trailData(block),
        )
    }

    private fun trailData(block: Block): Map<String, Any?>? {
        val trailsPlugin = Bukkit.getPluginManager().getPlugin("Trails") ?: return null
        val data = CustomBlockData(block, trailsPlugin)
        if (data.isEmpty) return null
        return mapOf(
            "walks" to data.get(NamespacedKey(trailsPlugin, "w"), PersistentDataType.INTEGER),
            "identity" to data.get(NamespacedKey(trailsPlugin, "n"), PersistentDataType.STRING),
        )
    }

    private fun onlinePlayer(name: String) =
        Bukkit.getPlayerExact(name) ?: throw NoSuchElementException("Player '$name' is not online on this server")

    private fun qaTool(
        spec: QaToolSpec,
        trailsPlugin: org.bukkit.plugin.Plugin,
    ): ItemStack =
        ItemStack(spec.material).apply {
            val kind = spec.taggedKind ?: return@apply
            itemMeta =
                itemMeta.apply {
                    itemName(Component.text("Trails QA: $kind"))
                    persistentDataContainer.set(
                        NamespacedKey(trailsPlugin, "trail_tool_kind"),
                        PersistentDataType.STRING,
                        kind,
                    )
                    setEnchantmentGlintOverride(true)
                }
        }

    private fun teleport(
        player: org.bukkit.entity.Player,
        world: World,
    ) {
        check(player.teleport(spawnLocation(world), PlayerTeleportEvent.TeleportCause.PLUGIN)) {
            "Teleport of '${player.name}' to '$WORLD_NAME' was rejected"
        }
    }

    private fun spawnLocation(world: World): Location = Location(world, -2.5, FLOOR_Y + 1.0, 0.5, -90f, 0f)

    private fun locationSummary(location: Location): Map<String, Any?> =
        mapOf(
            "world" to location.world?.name,
            "x" to location.x,
            "y" to location.y,
            "z" to location.z,
            "yaw" to location.yaw,
            "pitch" to location.pitch,
        )

    private fun markerPath() = ARC.instance.dataPath.resolve("qa-world/$WORLD_NAME.marker")

    private fun writeMarker(world: World) {
        val target = markerPath()
        Files.createDirectories(target.parent)
        val temporary = Files.createTempFile(target.parent, ".$WORLD_NAME-", ".tmp")
        try {
            Files.writeString(temporary, "schema=$MARKER_SCHEMA\nuuid=${world.uid}\n")
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun verifyMarker(world: World) {
        val marker = markerPath()
        check(Files.isRegularFile(marker)) { "QA world '$WORLD_NAME' is missing its ARC ownership marker" }
        val fields =
            Files.readAllLines(marker)
                .mapNotNull { line -> line.indexOf('=').takeIf { it > 0 }?.let { line.substring(0, it) to line.substring(it + 1) } }
                .toMap()
        check(fields["schema"] == MARKER_SCHEMA.toString() && fields["uuid"] == world.uid.toString()) {
            "QA world '$WORLD_NAME' ownership marker does not match the loaded world"
        }
    }

    private val PLAYER_NAME = Regex("[A-Za-z0-9_]{3,16}")
}

internal data class QaFixtureBlock(
    val id: String,
    val x: Int,
    val y: Int,
    val z: Int,
    val material: Material,
) {
    fun block(world: World): Block = world.getBlockAt(x, y, z)
}

internal data class QaToolSpec(
    val id: String,
    val material: Material,
    val taggedKind: String?,
)

private data class QaSurfaceBlock(
    val x: Int,
    val z: Int,
    val expectedMaterial: Material,
    val material: Material,
    val trailData: Map<String, Any?>?,
)
