package ru.arc.commands.arc.subcommands

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Location
import org.bukkit.Material
import org.mockbukkit.mockbukkit.entity.PlayerMock
import org.mockbukkit.mockbukkit.world.WorldMock
import ru.arc.KotestTestBase
import ru.arc.common.locationpools.LocationPool
import ru.arc.common.locationpools.LocationPoolManager
import ru.arc.config.ConfigManager
import ru.arc.treasure.core.Treasures
import ru.arc.treasurechests.ChestType
import ru.arc.treasurechests.TreasureHuntConfig
import ru.arc.treasurechests.TreasureHuntManager

class HuntSubCommandTabCompleteTest : KotestTestBase({

    lateinit var player: PlayerMock
    lateinit var world: WorldMock

    beforeTest {
        TreasureHuntManager.stopAll()
        LocationPoolManager.clear()
        Treasures.clear()
        world = server.addSimpleWorld("hunt-test-${System.nanoTime()}")
        player = server.addPlayer("HuntTester")
        player.teleport(Location(world, 401.0, 127.0, 268.0))
        player.addAttachment(plugin, "arc.treasure.hunt.admin", true)
    }

    afterTest {
        TreasureHuntManager.stopAll()
        LocationPoolManager.clear()
        Treasures.clear()
    }

    fun createPool(id: String, size: Int): LocationPool {
        val pool = LocationPoolManager.createPool(id)
        repeat(size) { index -> pool.addLocation(Location(world, 30.0 + index, 70.0, 30.0)) }
        return pool
    }

    fun loadPreset(
        typeId: String,
        locationPoolId: String,
        rewardPoolId: String,
        timeoutSeconds: Long = 900,
    ) {
        Treasures.getOrCreatePool(rewardPoolId)
        ConfigManager.moduleYamlPath(plugin.dataFolder.toPath(), "treasure-hunt.yml").toFile().writeText(
            """
            aliases:
              test_appearance: testplugin:test_chest
            treasure-hunt-types:
              $typeId:
                location-pool-id: $locationPoolId
                announce-start: false
                start-message: Preset announcement
                seconds-ttl: $timeoutSeconds
                launch-fireworks: false
                chest-types:
                  default:
                    type: VANILLA
                    treasure-pool-id: $rewardPoolId
                    weight: 1
            """.trimIndent(),
        )
        ConfigManager.reloadAll()
        TreasureHuntManager.loadTreasureHuntTypes()
    }

    describe("HuntSubCommand named arguments") {
        it("accepts key order and rejects unknown, duplicate, bare, empty, and extra equals") {
            HuntNamedArguments.parse(listOf("chests=12", "preset=easter"), setOf("preset", "chests")) shouldBe
                HuntNamedArgumentsResult.Parsed(mapOf("chests" to "12", "preset" to "easter"))

            listOf(
                listOf("preset=easter", "mystery=1"),
                listOf("pool=forest"),
                listOf("loot=rewards"),
                listOf("preset=easter", "PRESET=other"),
                listOf("chests="),
                listOf("easter", "chests=10"),
                listOf("preset=easter=other"),
            ).forEach { tokens ->
                (HuntNamedArguments.parse(tokens, setOf("preset", "chests")) as? HuntNamedArgumentsResult.Invalid)
                    .shouldNotBeNull()
            }
        }

        it("accepts only strict booleans, positive counts, and finite numbers") {
            HuntNamedArguments.positiveChestCount("1") shouldBe 1
            listOf("0", "-1", "many").forEach { HuntNamedArguments.positiveChestCount(it) shouldBe null }
            HuntNamedArguments.strictBoolean("true") shouldBe true
            HuntNamedArguments.strictBoolean("false") shouldBe false
            listOf("True", "yes", "1").forEach { HuntNamedArguments.strictBoolean(it) shouldBe null }
            HuntNamedArguments.finiteNumber("-42.5") shouldBe -42.5
            listOf("NaN", "Infinity", "-Infinity", "many").forEach { HuntNamedArguments.finiteNumber(it) shouldBe null }
        }

        it("rejects legacy positional forms and extra action arguments before changing active hunts") {
            val before = TreasureHuntManager.getActiveHunts().toSet()
            listOf(
                arrayOf("start", "custom", "pool=forest", "chests=2"),
                arrayOf("start", "easter"),
                arrayOf("start", "generate", "here", "radius=80"),
                arrayOf("stop", "forest"),
                arrayOf("status", "extra"),
                arrayOf("types", "extra"),
                arrayOf("stopall", "extra"),
            ).forEach { HuntSubCommand.execute(player, it) }

            TreasureHuntManager.getActiveHunts().toSet() shouldBe before
            val messages = buildList {
                repeat(7) {
                    val message = player.nextComponentMessage() ?: return@repeat
                    add(PlainTextComponentSerializer.plainText().serialize(message))
                }
            }
            messages.size shouldBe 7
            messages.count { it.contains("позиционные значения удалены") || it.contains("не принимает аргументы") || it.contains("locations=<active_location_pool>") } shouldBe 7
        }
    }

    describe("HuntSubCommand execution") {
        it("requires an explicit chest count for preset, pool, and generated starts") {
            val pool = createPool("required-count-${System.nanoTime()}", 3)
            loadPreset("required-count-preset", pool.id, "required-count-rewards")

            HuntSubCommand.execute(player, arrayOf("start", "preset=required-count-preset"))
            HuntSubCommand.execute(
                player,
                arrayOf("start", "locations=${pool.id}", "chest=vanilla", "rewards=required-count-rewards"),
            )
            HuntSubCommand.execute(
                player,
                arrayOf("start", "generate=true", "center=here", "radius=20", "chest=vanilla", "rewards=required-count-rewards"),
            )

            TreasureHuntManager.getActiveHunts().size shouldBe 0
        }

        it("starts a custom hunt with named keys in any order") {
            val locationPool = createPool("custom-${System.nanoTime()}", 12)
            loadPreset("unused-${System.nanoTime()}", locationPool.id, "base-rewards")
            Treasures.getOrCreatePool("override-rewards")

            HuntSubCommand.execute(
                player,
                arrayOf(
                    "start",
                    "rewards=override-rewards",
                    "chests=1",
                    "chest=vanilla",
                    "locations=${locationPool.id}",
                ),
            )

            val hunt = TreasureHuntManager.getByLocationPool(locationPool)
            hunt.shouldNotBeNull()
            hunt.config.locationPoolId shouldBe locationPool.id
            hunt.config.chestTypes.values().single().treasurePoolId shouldBe "override-rewards"
        }

        it("applies preset overrides while retaining inherited presentation and timeout settings") {
            val originalPool = createPool("preset-source-${System.nanoTime()}", 3)
            val overridePool = createPool("preset-target-${System.nanoTime()}", 4)
            loadPreset("seasonal", originalPool.id, "base-rewards", timeoutSeconds = 1_234)
            Treasures.getOrCreatePool("override-rewards")

            HuntSubCommand.execute(
                player,
                arrayOf(
                    "start",
                    "rewards=override-rewards",
                    "preset=seasonal",
                    "chests=2",
                    "locations=${overridePool.id}",
                    "chest=vanilla",
                ),
            )

            val hunt = TreasureHuntManager.getByLocationPool(overridePool)
            hunt.shouldNotBeNull()
            hunt.config.id shouldBe "seasonal"
            hunt.config.locationPoolId shouldBe overridePool.id
            hunt.config.timeoutSeconds shouldBe 1_234
            hunt.config.announcements.announceStart shouldBe false
            hunt.config.announcements.startMessage shouldBe "Preset announcement"
            hunt.config.effects.launchFireworks shouldBe false
            hunt.config.chestTypes.values().single().treasurePoolId shouldBe "override-rewards"
        }

        it("generates a preset hunt while retaining its inherited settings") {
            val originalPool = createPool("generated-preset-source-${System.nanoTime()}", 3)
            loadPreset("generated-season", originalPool.id, "generated-preset-rewards", timeoutSeconds = 1_678)
            player.teleport(Location(world, 401.0, 65.0, 268.0))
            for (x in 395..407) {
                for (z in 262..274) {
                    world.getBlockAt(x, 64, z).type = Material.STONE
                    for (y in 65 until minOf(69, world.maxHeight)) world.getBlockAt(x, y, z).type = Material.AIR
                }
            }

            HuntSubCommand.execute(
                player,
                arrayOf("start", "center=here", "generate=true", "radius=2", "chests=1", "preset=generated-season"),
            )

            val hunt = TreasureHuntManager.getActiveHunts().single()
            hunt.config.id shouldBe "generated-season"
            hunt.config.timeoutSeconds shouldBe 1_678
            LocationPoolManager.isEphemeralPool(hunt.config.locationPoolId) shouldBe true
        }

        it("rejects unknown rewards and appearances before replacing an active hunt") {
            val pool = createPool("active-${System.nanoTime()}", 3)
            loadPreset("unused-${System.nanoTime()}", pool.id, "known-rewards")
            HuntSubCommand.execute(
                player,
                arrayOf("start", "locations=${pool.id}", "chests=1", "chest=vanilla", "rewards=known-rewards"),
            )
            val active = TreasureHuntManager.getByLocationPool(pool)
            active.shouldNotBeNull()

            HuntSubCommand.execute(
                player,
                arrayOf("start", "replace=true", "locations=${pool.id}", "chests=1", "chest=unknown", "rewards=known-rewards"),
            )
            TreasureHuntManager.getByLocationPool(pool) shouldBe active

            HuntSubCommand.execute(
                player,
                arrayOf("start", "locations=${pool.id}", "chests=1", "chest=vanilla", "rewards=missing-rewards"),
            )
            TreasureHuntManager.getByLocationPool(pool) shouldBe active
        }

        it("rejects non-finite radius and malformed booleans before generating locations") {
            Treasures.getOrCreatePool("generated-rewards")
            HuntSubCommand.execute(
                player,
                arrayOf("start", "generate=true", "center=here", "radius=NaN", "chests=1", "chest=vanilla", "rewards=generated-rewards"),
            )
            HuntSubCommand.execute(
                player,
                arrayOf("start", "locations=unused", "chests=1", "chest=vanilla", "rewards=generated-rewards", "replace=TRUE"),
            )
            TreasureHuntManager.getActiveHunts().size shouldBe 0
        }

        it("rejects an unknown reward pool before config start or generated placement") {
            val pool = createPool("invalid-reward-${System.nanoTime()}", 1)
            val rewardPoolId = "missing-reward-${System.nanoTime()}"
            val invalidConfig = TreasureHuntConfig.simple(pool.id, pool.id, ChestType.vanilla(rewardPoolId))
            val ephemeralPoolsBefore =
                LocationPoolManager.getAll()
                    .filter { LocationPoolManager.isEphemeralPool(it.id) }
                    .map { it.id }
                    .toSet()

            TreasureHuntManager.startHunt(invalidConfig, 1, player, replaceExisting = true) shouldBe null
            TreasureHuntManager.startGeneratedHunt(
                player.location,
                10.0,
                1,
                invalidConfig,
                player,
                replaceExisting = true,
            ) shouldBe null

            TreasureHuntManager.getActiveHunts().size shouldBe 0
            LocationPoolManager.getAll()
                .filter { LocationPoolManager.isEphemeralPool(it.id) }
                .map { it.id }
                .toSet() shouldBe ephemeralPoolsBefore
            repeat(2) {
                val message = player.nextComponentMessage()
                message.shouldNotBeNull()
                PlainTextComponentSerializer.plainText().serialize(message).contains(rewardPoolId) shouldBe true
            }
        }
    }

    describe("HuntSubCommand tab completion") {
        it("offers only actions and named keys, never bare legacy mode tokens") {
            val pool = createPool("actions-${System.nanoTime()}", 2)
            val presetId = "actions-preset-${System.nanoTime()}"
            loadPreset(presetId, pool.id, "actions-rewards")

            val actions = HuntSubCommand.tabComplete(player, arrayOf(""))
            actions.shouldNotBeNull()
            actions shouldContain "status"
            actions shouldContain "start"

            val keys = HuntSubCommand.tabComplete(player, arrayOf("start", ""))
            keys.shouldNotBeNull()
            keys shouldContain "preset="
            keys shouldContain "locations="
            keys shouldContain "generate="
            keys shouldNotContain "custom"
            keys shouldNotContain "here"

            val presetValues = HuntSubCommand.tabComplete(player, arrayOf("start", "preset="))
            presetValues shouldContain "preset=$presetId"
            presetValues shouldNotContain presetId
        }

        it("suggests only loaded presets, persistent pools, configured appearances and rewards") {
            val pool = createPool("tab-pool-${System.nanoTime()}", 12)
            val presetId = "tab-preset-${System.nanoTime()}"
            loadPreset(presetId, pool.id, "tab-rewards")
            Treasures.getOrCreatePool("extra-rewards")
            val ephemeral = LocationPoolManager.createEphemeralPool()
            ephemeral.addLocation(player.location)

            HuntSubCommand.tabComplete(player, arrayOf("start", "preset=")) shouldContain "preset=$presetId"
            HuntSubCommand.tabComplete(player, arrayOf("start", "locations=")) shouldContain "locations=${pool.id}"
            HuntSubCommand.tabComplete(player, arrayOf("start", "locations=")) shouldNotContain "locations=${ephemeral.id}"
            HuntSubCommand.tabComplete(player, arrayOf("start", "chest=")) shouldContain "chest=test_appearance"
            HuntSubCommand.tabComplete(player, arrayOf("start", "chest=")) shouldContain "chest=vanilla"
            HuntSubCommand.tabComplete(player, arrayOf("start", "chest=")) shouldNotContain "chest=pumpkin_1"
            HuntSubCommand.tabComplete(player, arrayOf("start", "rewards=")) shouldContain "rewards=tab-rewards"
            HuntSubCommand.tabComplete(player, arrayOf("start", "rewards=")) shouldContain "rewards=extra-rewards"
        }

        it("caps suggested counts to known pool size while preserving the full-pool choice") {
            val pool = createPool("sized-pool-${System.nanoTime()}", 12)
            loadPreset("sized-preset", pool.id, "sized-rewards")

            val byLocation = HuntSubCommand.tabComplete(player, arrayOf("start", "locations=${pool.id}", "chests="))
            byLocation shouldContain "chests=6"
            byLocation shouldContain "chests=12"
            byLocation shouldNotContain "chests=18"

            val byPreset = HuntSubCommand.tabComplete(player, arrayOf("start", "preset=sized-preset", "chests="))
            byPreset shouldContain "chests=12"
            byPreset shouldNotContain "chests=18"

            val largePool = createPool("large-pool-${System.nanoTime()}", 64)
            val largePoolCounts = HuntSubCommand.tabComplete(player, arrayOf("start", "locations=${largePool.id}", "chests="))
            largePoolCounts shouldContain "chests=6"
            largePoolCounts shouldContain "chests=12"
            largePoolCounts shouldContain "chests=18"
            largePoolCounts shouldContain "chests=50"
            largePoolCounts shouldContain "chests=64"
        }

        it("hides used and conflicting keys and returns no player fallback to console") {
            val pool = createPool("completion-${System.nanoTime()}", 2)
            loadPreset("completion-preset", pool.id, "completion-rewards")
            val chestPrefix = HuntSubCommand.tabComplete(player, arrayOf("start", "chest=t"))
            chestPrefix shouldContain "chest=test_appearance"
            chestPrefix shouldNotContain "chest=vanilla"

            val afterLocation = HuntSubCommand.tabComplete(player, arrayOf("start", "locations=${pool.id}", ""))
            afterLocation shouldNotContain "locations="
            afterLocation shouldNotContain "generate="
            afterLocation shouldNotContain "center="

            val presetMode = HuntSubCommand.tabComplete(player, arrayOf("start", "preset=completion-preset", ""))
            presetMode shouldContain "generate="
            val presetPoolMode = HuntSubCommand.tabComplete(
                player,
                arrayOf("start", "preset=completion-preset", "locations=${pool.id}", ""),
            )
            presetPoolMode shouldNotContain "generate="
            val presetGeneratedMode = HuntSubCommand.tabComplete(
                player,
                arrayOf("start", "preset=completion-preset", "generate=true", ""),
            )
            presetGeneratedMode shouldContain "center="
            presetGeneratedMode shouldContain "chests="
            presetGeneratedMode shouldNotContain "locations="
            val generatedMode = HuntSubCommand.tabComplete(player, arrayOf("start", "generate=true", ""))
            generatedMode shouldContain "preset="

            val geometryBeforeGenerate = HuntSubCommand.tabComplete(player, arrayOf("start", "center=here", ""))
            geometryBeforeGenerate shouldContain "preset="
            geometryBeforeGenerate shouldContain "generate="
            geometryBeforeGenerate shouldContain "chests="
            geometryBeforeGenerate shouldContain "radius="
            geometryBeforeGenerate shouldNotContain "locations="
            geometryBeforeGenerate shouldNotContain "world="
            geometryBeforeGenerate shouldNotContain "x="
            HuntSubCommand.tabComplete(player, arrayOf("start", "center=here", "generate=")) shouldContain "generate=true"

            val generatedPresetCountHints = HuntSubCommand.tabComplete(
                player,
                arrayOf("start", "preset=completion-preset", "generate=true", "chests="),
            )
            generatedPresetCountHints shouldContain "chests=6"
            generatedPresetCountHints shouldContain "chests=12"
            generatedPresetCountHints shouldContain "chests=18"
            generatedPresetCountHints shouldContain "chests=50"
            generatedPresetCountHints shouldNotContain "chests=2"

            val afterHere = HuntSubCommand.tabComplete(player, arrayOf("start", "generate=true", "center=here", ""))
            val presetAfterHere = HuntSubCommand.tabComplete(
                player,
                arrayOf("start", "preset=completion-preset", "generate=true", "center=here", ""),
            )
            presetAfterHere shouldContain "radius="
            presetAfterHere shouldContain "chests="
            presetAfterHere shouldNotContain "world="
            presetAfterHere shouldNotContain "locations="
            afterHere shouldNotContain "world="
            afterHere shouldNotContain "x="
            afterHere shouldNotContain "y="
            afterHere shouldNotContain "z="

            val consoleCoordinates = HuntSubCommand.tabComplete(
                server.consoleSender,
                arrayOf("start", "generate=true", "world=${world.name}", "x="),
            )
            consoleCoordinates shouldBe emptyList()
            HuntSubCommand.tabComplete(server.consoleSender, arrayOf("start", "generate=true", "center=")) shouldBe emptyList()
            HuntSubCommand.tabComplete(player, arrayOf("start", "generate=true", "world=${world.name}", "x=")) shouldContain
                "x=${player.location.blockX}"
            HuntSubCommand.tabComplete(player, arrayOf("not-a-hunt-action", "")) shouldBe emptyList()
        }

        it("offers the active pool for stop and keeps unused keys hidden") {
            val pool = createPool("stop-active-${System.nanoTime()}", 4)
            loadPreset("stop-test", pool.id, "stop-rewards")
            HuntSubCommand.execute(
                player,
                arrayOf("start", "preset=stop-test", "chests=1"),
            )
            val stopValues = HuntSubCommand.tabComplete(player, arrayOf("stop", "locations="))
            stopValues shouldContain "locations=${pool.id}"
            stopValues shouldNotContain "locations=stop-inactive"
            val afterStopPool = HuntSubCommand.tabComplete(player, arrayOf("stop", "locations=${pool.id}", ""))
            afterStopPool shouldBe emptyList()
        }
    }
})
