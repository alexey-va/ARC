package ru.arc.commands.arc.subcommands

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Location
import org.mockbukkit.mockbukkit.entity.PlayerMock
import ru.arc.KotestTestBase
import ru.arc.common.locationpools.LocationPoolManager
import ru.arc.config.ConfigManager
import ru.arc.treasure.core.Treasures
import ru.arc.treasurechests.TreasureHuntManager

class HuntSubCommandTabCompleteTest : KotestTestBase({

    lateinit var player: PlayerMock

    beforeTest {
        player = server.addPlayer("HuntTabTester")
        player.addAttachment(plugin, "arc.treasure.hunt.admin", true)
    }

    fun loadPreset(typeId: String, locationPoolId: String = "none", treasurePoolId: String = "tab-treasure") {
        ConfigManager.moduleYamlPath(plugin.dataFolder.toPath(), "treasure-hunt.yml").toFile().writeText(
            """
            treasure-hunt-types:
              $typeId:
                location-pool-id: $locationPoolId
                chest-types:
                  default:
                    type: VANILLA
                    treasure-pool-id: $treasurePoolId
                    weight: 1
            """.trimIndent()
        )
        Treasures.getOrCreatePool(treasurePoolId)
        ConfigManager.reloadAll()
        TreasureHuntManager.loadTreasureHuntTypes()
    }

    describe("HuntSubCommand tabComplete") {
        it("suggests subcommands and filters by prefix for first arg") {
            val result = HuntSubCommand.tabComplete(player, arrayOf("sta"))
            result.shouldNotBeNull()
            result shouldContain "start"
            result shouldContain "status"
        }

        it("suggests named pool values and generate after start custom") {
            LocationPoolManager.createPool("tab-pool-hunt")
            val result = HuntSubCommand.tabComplete(player, arrayOf("start", "custom", ""))
            result.shouldNotBeNull()
            result shouldContain "pool=tab-pool-hunt"
            result shouldContain "pool="
            result shouldContain "generate"
            result shouldNotContain "tab-pool-hunt"
        }

        it("suggests only generate when custom mode starts with gen") {
            LocationPoolManager.createPool("tab-pool-hunt-gen")
            val result = HuntSubCommand.tabComplete(player, arrayOf("start", "custom", "gen"))
            result.shouldNotBeNull()
            result shouldContain "generate"
            result shouldNotContain "pool=tab-pool-hunt-gen"
        }

        it("suggests named here and coordinate forms after generate") {
            val world = server.addSimpleWorld("world")
            player.teleport(Location(world, 401.0, 127.0, 268.0))
            val result = HuntSubCommand.tabComplete(player, arrayOf("start", "custom", "generate", ""))
            result.shouldNotBeNull()
            result shouldContain "here"
            result shouldContain "world=world"
            result shouldContain "x=401"
            result shouldContain "y=127"
        }

        it("suggests radius values after named here anchor") {
            val result = HuntSubCommand.tabComplete(player, arrayOf("start", "custom", "generate", "here", "radius="))
            result.shouldNotBeNull()
            result shouldContain "radius=100"
        }

        it("suggests player coordinate values for explicit named world mode") {
            val world = server.addSimpleWorld("coord-world")
            player.teleport(Location(world, 401.0, 127.0, 268.0))
            val result = HuntSubCommand.tabComplete(
                player,
                arrayOf("start", "custom", "generate", "world=coord-world", "y="),
            )
            result.shouldNotBeNull()
            result shouldContain "y=127"
        }

        it("suggests pool size as chest count for named custom pool mode") {
            val pool = LocationPoolManager.createPool("sized-pool-named")
            repeat(12) { pool.addLocation(player.location) }
            val result = HuntSubCommand.tabComplete(
                player,
                arrayOf("start", "custom", "pool=sized-pool-named", "chests="),
            )
            result.shouldNotBeNull()
            result shouldContain "chests=12"
        }

        it("does not suggest preset names at top level") {
            loadPreset("daily-tab-type")
            val result = HuntSubCommand.tabComplete(player, arrayOf(""))
            result.shouldNotBeNull()
            result shouldContain "start"
            result shouldNotContain "preset=daily-tab-type"
        }

        it("suggests preset values and named chest counts") {
            loadPreset("daily-tab-type")
            val startModes = HuntSubCommand.tabComplete(player, arrayOf("start", ""))
            startModes.shouldNotBeNull()
            startModes shouldContain "preset=daily-tab-type"
            startModes shouldContain "custom"

            val chestCounts = HuntSubCommand.tabComplete(player, arrayOf("start", "preset=daily-tab-type", "chests="))
            chestCounts.shouldNotBeNull()
            chestCounts shouldContain "chests=5"
            chestCounts shouldContain "chests=10"
        }

        it("suggests the named stop key when no hunts are active") {
            val result = HuntSubCommand.tabComplete(player, arrayOf("stop", ""))
            result.shouldNotBeNull()
            result shouldContain "pool="
        }

        it("suggests chest model and loot values for named custom pool") {
            LocationPoolManager.createPool("ns-tab-pool-named")
            Treasures.getOrCreatePool("sixth-tab-treasure-named")
            val chestModels = HuntSubCommand.tabComplete(
                player,
                arrayOf("start", "custom", "pool=ns-tab-pool-named", "chest=van"),
            )
            chestModels.shouldNotBeNull()
            chestModels shouldContain "chest=vanilla"

            val treasurePools = HuntSubCommand.tabComplete(
                player,
                arrayOf("start", "custom", "pool=ns-tab-pool-named", "chest=vanilla", "loot=six"),
            )
            treasurePools.shouldNotBeNull()
            treasurePools shouldContain "loot=sixth-tab-treasure-named"
        }

        it("does not repeat named keys already used") {
            val result = HuntSubCommand.tabComplete(
                player,
                arrayOf("start", "custom", "pool=used-pool", "chests=10", ""),
            )
            result.shouldNotBeNull()
            result shouldNotContain "pool="
            result shouldNotContain "chests="
            result shouldContain "chest="
            result shouldContain "loot="
        }
    }

    describe("HuntNamedArguments") {
        it("accepts keys in any order") {
            val result = HuntNamedArguments.parse(listOf("chests=50", "preset=easter"), setOf("preset", "chests"))
            result shouldBe HuntNamedArgumentsResult.Parsed(mapOf("chests" to "50", "preset" to "easter"))
        }

        it("rejects unknown, duplicate, empty, and mixed arguments") {
            val cases = listOf(
                listOf("preset=easter", "mystery=1") to "допустимые ключи: chests, preset",
                listOf("preset=easter", "PRESET=other") to "ключ 'preset' указан повторно",
                listOf("chests=") to "для 'chests' укажите значение после '='",
                listOf("easter", "chests=10") to "используйте key=value и не смешивайте именованные аргументы с позиционными",
            )
            cases.forEach { (tokens, expectedReason) ->
                val result = HuntNamedArguments.parse(tokens, setOf("preset", "chests"))
                result shouldBe HuntNamedArgumentsResult.Invalid(expectedReason)
            }
        }

        it("rejects non-positive chest counts and non-finite coordinates or radii") {
            HuntNamedArguments.positiveChestCount("10") shouldBe 10
            HuntNamedArguments.positiveChestCount("0") shouldBe null
            HuntNamedArguments.positiveChestCount("-1") shouldBe null
            HuntNamedArguments.positiveChestCount("many") shouldBe null
            HuntNamedArguments.finiteNumber("-42.5") shouldBe -42.5
            HuntNamedArguments.finiteNumber("NaN") shouldBe null
            HuntNamedArguments.finiteNumber("Infinity") shouldBe null
            HuntNamedArguments.finiteNumber("-Infinity") shouldBe null
        }

        it("reports invalid or mixed named commands and never starts the hunt") {
            val activeHuntsBefore = TreasureHuntManager.getActiveHunts().toSet()

            HuntSubCommand.execute(player, arrayOf("start", "preset=easter", "chests=0"))
            HuntSubCommand.execute(player, arrayOf("start", "easter", "chests=10"))

            val plainText = PlainTextComponentSerializer.plainText()
            listOf(
                plainText.serialize(requireNotNull(player.nextComponentMessage())),
                plainText.serialize(requireNotNull(player.nextComponentMessage())),
            ) shouldBe listOf(
                "Аргументы охоты: chests должно быть положительным целым числом. " +
                    "Используйте формат key=value; не смешивайте его с позиционными аргументами.",
                "Аргументы охоты: используйте key=value и не смешивайте именованные аргументы с позиционными. " +
                    "Используйте формат key=value; не смешивайте его с позиционными аргументами.",
            )
            TreasureHuntManager.getActiveHunts().toSet() shouldBe activeHuntsBefore
        }
    }
})
