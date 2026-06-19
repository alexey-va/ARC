package ru.arc.commands.arc.subcommands

import io.kotest.matchers.collections.shouldContain
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

        it("suggests pool size as chest hint only for start branch with three args") {
            LocationPoolManager.createPool("tab-pool-hunt")
            val result = HuntSubCommand.tabComplete(player, arrayOf("start", "tab-pool-hunt", ""))
            result.shouldNotBeNull()
            // Empty pool → size 0; unknown pool would fall back to "10"
            result shouldContain "0"
        }

        it("suggests preset chest counts for shorthand hunt type on third arg") {
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

            val result = HuntSubCommand.tabComplete(player, arrayOf("daily-tab-type", "1", ""))
            result.shouldNotBeNull()
            result shouldContain "5"
            result shouldContain "10"
        }

        it("returns null for ambiguous three-arg stop command") {
            val result = HuntSubCommand.tabComplete(player, arrayOf("stop", "any-pool", ""))
            result.shouldBeNull()
        }

        it("suggests preset chest counts for shorthand hunt type on second arg") {
            ConfigManager.moduleYamlPath(plugin.dataFolder.toPath(), "treasure-hunt.yml").toFile().writeText(
                """
                treasure-hunt-types:
                  shorthand-tab-hunt:
                    location-pool-id: none
                    chest-types:
                      default:
                        type: VANILLA
                        treasure-pool-id: st-h-treasure
                        weight: 1
                """.trimIndent()
            )
            Treasures.getOrCreate("st-h-treasure")
            ConfigManager.reloadAll()
            TreasureHuntManager.loadTreasureHuntTypes()

            val result = HuntSubCommand.tabComplete(player, arrayOf("shorthand-tab-hunt", "1"))
            result.shouldNotBeNull()
            result shouldContain "10"
        }

        it("returns null for fourth-arg tab when first token is not start") {
            val result = HuntSubCommand.tabComplete(player, arrayOf("types", "a", "b", "van"))
            result.shouldBeNull()
        }

        it("returns null for fifth-arg tab when first token is not start") {
            val result = HuntSubCommand.tabComplete(player, arrayOf("status", "a", "b", "c", "d"))
            result.shouldBeNull()
        }

        it("suggests namespaces for start full-form on fourth arg") {
            LocationPoolManager.createPool("ns-tab-pool")
            Treasures.getOrCreate("ns-treasure")
            val result = HuntSubCommand.tabComplete(player, arrayOf("start", "ns-tab-pool", "3", "van"))
            result.shouldNotBeNull()
            result shouldContain "vanilla"
        }

        it("suggests hunt types and pools when second arg for start") {
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
            result shouldContain "start-tab-pool"
            result shouldContain "z-start-tab-type"
        }
    }
})
