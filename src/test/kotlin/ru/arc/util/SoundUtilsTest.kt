package ru.arc.util

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.bukkit.Sound
import ru.arc.KotestTestBase

class SoundUtilsTest :
    KotestTestBase({

        beforeSpec {
            SoundUtils.clearCache()
        }

        afterSpec {
            SoundUtils.clearCache()
        }

        describe("getSound") {

            it("should return Sound for valid Bukkit enum name") {
                val sound = SoundUtils.getSound("ENTITY_PLAYER_LEVELUP")

                sound.shouldNotBeNull()
                sound shouldBe Sound.ENTITY_PLAYER_LEVELUP
            }

            it("should return Sound for lowercase enum name") {
                val sound = SoundUtils.getSound("entity_player_levelup")

                sound.shouldNotBeNull()
                sound shouldBe Sound.ENTITY_PLAYER_LEVELUP
            }

            it("should return null for invalid sound name") {
                val sound = SoundUtils.getSound("invalid_sound_that_doesnt_exist")

                sound.shouldBeNull()
            }

            it("should return null for blank string") {
                val sound = SoundUtils.getSound("")

                sound.shouldBeNull()
            }

            it("should cache results") {
                // First call
                val sound1 = SoundUtils.getSound("ENTITY_PLAYER_LEVELUP")
                // Second call should return cached value
                val sound2 = SoundUtils.getSound("entity_player_levelup")

                sound1 shouldBe sound2
            }
        }

        describe("getNamespacedKey") {

            it("should return NamespacedKey for valid string") {
                val key = SoundUtils.getNamespacedKey("minecraft:entity.player.levelup")

                key.shouldNotBeNull()
                key.namespace shouldBe "minecraft"
            }

            it("should return null for blank string") {
                val key = SoundUtils.getNamespacedKey("")

                key.shouldBeNull()
            }
        }

        describe("playSound at location") {

            it("should return true for valid sound") {
                val world = server.addSimpleWorld("test")
                val location = world.getBlockAt(0, 64, 0).location

                val result = SoundUtils.playSound(location, "ENTITY_PLAYER_LEVELUP")

                result.shouldBeTrue()
            }

            it("should return false for invalid sound") {
                val world = server.addSimpleWorld("test")
                val location = world.getBlockAt(0, 64, 0).location

                val result = SoundUtils.playSound(location, "invalid_sound")

                result.shouldBeFalse()
            }
        }

        describe("playSound to player") {

            it("should return true for valid sound") {
                val player = server.addPlayer("TestPlayer")

                val result = SoundUtils.playSound(player, "ENTITY_PLAYER_LEVELUP")

                result.shouldBeTrue()
            }

            it("should return false for invalid sound") {
                val player = server.addPlayer("TestPlayer")

                val result = SoundUtils.playSound(player, "invalid_sound")

                result.shouldBeFalse()
            }
        }

        describe("extension functions") {

            it("Location.playSound should work") {
                val world = server.addSimpleWorld("test")
                val location = world.getBlockAt(0, 64, 0).location

                val result = location.playSound("ENTITY_PLAYER_LEVELUP")

                result.shouldBeTrue()
            }

            it("Player.playSound should work") {
                val player = server.addPlayer("TestPlayer")

                val result = player.playSound("ENTITY_EXPERIENCE_ORB_PICKUP")

                result.shouldBeTrue()
            }
        }
    })
