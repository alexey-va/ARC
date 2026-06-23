package ru.arc.commands.arc.subcommands

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import org.mockbukkit.mockbukkit.entity.PlayerMock
import ru.arc.KotestTestBase
import ru.arc.common.locationpools.LocationPoolManager
import ru.arc.configs.ConfigManager
import ru.arc.treasure.core.Treasures
import ru.arc.treasurechests.TreasureHuntManager

class HuntSubCommandTabCompleteTest : KotestTestBase({

    lateinit var player: PlayerMock

    beforeTest {
        player = server.addPlayer("HuntTabTester")
        player.addAttachment(plugin, "arc.treasure-hunt", true)
    }

    describe("HuntSubCommand tabComplete") {
        it("suggests subcommands and filters by prefix for first arg") {
            val result = HuntSubCommand.tabComplete(player, arrayOf("sta"))
            result.shouldNotBeNull()
            result shouldContain "start"
            result shouldContain "status"
        }

        it("suggests location pools and generate after start custom on third arg") {
            LocationPoolManager.createPool("tab-pool-hunt")
            val result = HuntSubCommand.tabComplete(player, arrayOf("start", "custom", ""))
            result.shouldNotBeNull()
            result shouldContain "tab-pool-hunt"
            result shouldContain "generate"
        }

        it("suggests only generate when third arg starts with gen") {
            LocationPoolManager.createPool("tab-pool-hunt")
            val result = HuntSubCommand.tabComplete(player, arrayOf("start", "custom", "gen"))
            result.shouldNotBeNull()
            result shouldContain "generate"
            result shouldNotContain "tab-pool-hunt"
        }

        it("suggests here and player blockX after start custom generate on fourth arg") {
            player.teleport(server.addSimpleWorld("world").spawnLocation.add(401.0, 127.0, 268.0))
            val result = HuntSubCommand.tabComplete(player, arrayOf("start", "custom", "generate", ""))
            result.shouldNotBeNull()
            result shouldContain "here"
            result shouldContain "401"
        }

        it("suggests radius for start custom generate here on fifth arg") {
            val result = HuntSubCommand.tabComplete(player, arrayOf("start", "custom", "generate", "here", ""))
            result.shouldNotBeNull()
            result shouldContain "100"
        }

        it("suggests player Y coordinate on fifth arg in coord generate mode") {
            val world = server.addSimpleWorld("coord-world")
            player.teleport(org.bukkit.Location(world, 401.0, 127.0, 268.0))
            val result = HuntSubCommand.tabComplete(player, arrayOf("start", "custom", "generate", "401", ""))
            result.shouldNotBeNull()
            result shouldContain "127"
        }

        it("suggests pool size as chest count for custom pool mode") {
            val pool = LocationPoolManager.createPool("sized-pool")
            repeat(12) { pool.addLocation(player.location) }
            val result = HuntSubCommand.tabComplete(player, arrayOf("start", "custom", "sized-pool", ""))
            result.shouldNotBeNull()
            result shouldContain "12"
        }

        it("does not suggest preset names at top level") {
            ConfigManager.moduleYamlPath(plugin.dataFolder.toPath(), "treasure-hunt.yml").toFile().writeText(
                """
                treasure-hunt-types:
                  daily-tab-type:
                    location-pool-id: none
                    chest-types:
                      default:
                        type: VANILLA
                        treasure-pool-id: tab-treasure
                        weight: 1
                """.trimIndent()
            )
            Treasures.getOrCreate("tab-treasure")
            ConfigManager.reloadAll()
            TreasureHuntManager.loadTreasureHuntTypes()

            val result = HuntSubCommand.tabComplete(player, arrayOf(""))
            result.shouldNotBeNull()
            result shouldContain "start"
            result shouldNotContain "daily-tab-type"
        }

        it("suggests preset chest counts after start preset on third arg") {
            ConfigManager.moduleYamlPath(plugin.dataFolder.toPath(), "treasure-hunt.yml").toFile().writeText(
                """
                treasure-hunt-types:
                  daily-tab-type:
                    location-pool-id: none
                    chest-types:
                      default:
                        type: VANILLA
                        treasure-pool-id: tab-treasure
                        weight: 1
                """.trimIndent()
            )
            Treasures.getOrCreate("tab-treasure")
            ConfigManager.reloadAll()
            TreasureHuntManager.loadTreasureHuntTypes()

            val result = HuntSubCommand.tabComplete(player, arrayOf("start", "daily-tab-type", ""))
            result.shouldNotBeNull()
            result shouldContain "5"
            result shouldContain "10"
        }

        it("returns null for stop when no active hunts to suggest") {
            val result = HuntSubCommand.tabComplete(player, arrayOf("stop", ""))
            result.shouldBeNull()
        }

        it("suggests chest models for start custom pool on fifth arg") {
            LocationPoolManager.createPool("ns-tab-pool")
            val result = HuntSubCommand.tabComplete(player, arrayOf("start", "custom", "ns-tab-pool", "3", "van"))
            result.shouldNotBeNull()
            result shouldContain "vanilla"
        }

        it("suggests presets and custom but not generate on start second arg") {
            LocationPoolManager.createPool("start-tab-pool")
            ConfigManager.moduleYamlPath(plugin.dataFolder.toPath(), "treasure-hunt.yml").toFile().writeText(
                """
                treasure-hunt-types:
                  z-start-tab-type:
                    location-pool-id: none
                    chest-types:
                      default:
                        type: VANILLA
                        treasure-pool-id: st2-treasure
                        weight: 1
                """.trimIndent()
            )
            Treasures.getOrCreate("st2-treasure")
            ConfigManager.reloadAll()
            TreasureHuntManager.loadTreasureHuntTypes()

            val result = HuntSubCommand.tabComplete(player, arrayOf("start", ""))
            result.shouldNotBeNull()
            result shouldContain "custom"
            result shouldNotContain "generate"
            result shouldContain "z-start-tab-type"
        }

        it("suggests treasure pools for start custom pool on sixth arg") {
            Treasures.getOrCreate("sixth-tab-treasure")
            val result = HuntSubCommand.tabComplete(player, arrayOf("start", "custom", "pool", "5", "vanilla", "six"))
            result.shouldNotBeNull()
            result shouldContain "sixth-tab-treasure"
        }
    }
})
