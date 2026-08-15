package ru.arc.ops

import com.jeff_media.customblockdata.CustomBlockData
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
import org.bukkit.persistence.PersistentDataType
import ru.arc.ARC
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

object OpsQaWorldHandlers {
    const val WORLD_NAME = "arc_qa_flat"
    const val FIXTURE_VERSION = 1
    const val FLOOR_Y = 63

    internal val fixtureBlocks =
        listOf(
            QaFixtureBlock("trail_target", 0, FLOOR_Y, 0, Material.GRASS_BLOCK),
            QaFixtureBlock("grass_sample", -3, FLOOR_Y, 3, Material.GRASS_BLOCK),
            QaFixtureBlock("dirt_sample", 0, FLOOR_Y, 3, Material.DIRT),
            QaFixtureBlock("coarse_dirt_sample", 3, FLOOR_Y, 3, Material.COARSE_DIRT),
            QaFixtureBlock("dirt_path_sample", 6, FLOOR_Y, 3, Material.DIRT_PATH),
        )

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
        world.worldBorder.setCenter(0.0, 0.0)
        world.worldBorder.size = 64.0
        world.setSpawnLocation(spawnLocation(world))
    }

    private fun resetFixture(world: World) {
        for (x in -8..8) {
            for (z in -5..5) {
                world.getBlockAt(x, FLOOR_Y, z).setType(Material.SMOOTH_STONE, false)
                for (y in FLOOR_Y + 1..FLOOR_Y + 4) {
                    world.getBlockAt(x, y, z).setType(Material.AIR, false)
                }
            }
        }
        val trailsPlugin = Bukkit.getPluginManager().getPlugin("Trails")
        fixtureBlocks.forEach { fixture ->
            val block = fixture.block(world)
            if (trailsPlugin != null) CustomBlockData(block, trailsPlugin).clear()
            block.setType(fixture.material, false)
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
        return mapOf(
            "world" to WORLD_NAME,
            "fixtureVersion" to FIXTURE_VERSION,
            "loaded" to true,
            "owned" to true,
            "prepared" to blocks.all { it["matchesExpected"] == true && it["trailData"] == null },
            "spawn" to locationSummary(spawnLocation(world)),
            "blocks" to blocks,
        )
    }

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
            Files.writeString(temporary, "schema=$FIXTURE_VERSION\nuuid=${world.uid}\n")
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
        check(fields["schema"] == FIXTURE_VERSION.toString() && fields["uuid"] == world.uid.toString()) {
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
