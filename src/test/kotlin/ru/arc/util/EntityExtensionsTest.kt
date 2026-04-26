package ru.arc.util

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import ru.arc.KotestTestBase

class EntityExtensionsTest :
    KotestTestBase({

        describe("nearbyEntities") {

            it("should find nearby players") {
                val world = server.addSimpleWorld("test")
                val location = world.getBlockAt(0, 64, 0).location

                // Add players near the location
                val player1 = server.addPlayer("Player1")
                player1.teleport(location.clone().add(5.0, 0.0, 0.0))

                val player2 = server.addPlayer("Player2")
                player2.teleport(location.clone().add(100.0, 0.0, 0.0)) // Far away

                val nearby = location.nearbyPlayers(10.0)

                nearby shouldHaveSize 1
                nearby.first().name shouldBe "Player1"
            }
        }

        describe("isWithinRange") {

            it("should return true when within range") {
                val world = server.addSimpleWorld("test")
                val location = world.getBlockAt(0, 64, 0).location

                val player = server.addPlayer("TestPlayer")
                player.teleport(location.clone().add(5.0, 0.0, 0.0))

                player.isWithinRange(location, 10.0).shouldBeTrue()
            }

            it("should return false when outside range") {
                val world = server.addSimpleWorld("test")
                val location = world.getBlockAt(0, 64, 0).location

                val player = server.addPlayer("TestPlayer")
                player.teleport(location.clone().add(50.0, 0.0, 0.0))

                player.isWithinRange(location, 10.0).shouldBeFalse()
            }

            it("should return false for different worlds") {
                val world1 = server.addSimpleWorld("world1")
                val world2 = server.addSimpleWorld("world2")
                val location = world1.getBlockAt(0, 64, 0).location

                val player = server.addPlayer("TestPlayer")
                player.teleport(world2.getBlockAt(0, 64, 0).location)

                player.isWithinRange(location, 100.0).shouldBeFalse()
            }
        }

        describe("distanceToOrNull") {

            it("should return distance when same world") {
                val world = server.addSimpleWorld("test")
                val location = world.getBlockAt(0, 64, 0).location

                val player = server.addPlayer("TestPlayer")
                player.teleport(location.clone().add(10.0, 0.0, 0.0))

                val distance = player.distanceToOrNull(location)

                distance shouldBe 10.0
            }

            it("should return null for different worlds") {
                val world1 = server.addSimpleWorld("world1")
                val world2 = server.addSimpleWorld("world2")
                val location = world1.getBlockAt(0, 64, 0).location

                val player = server.addPlayer("TestPlayer")
                player.teleport(world2.getBlockAt(0, 64, 0).location)

                player.distanceToOrNull(location) shouldBe null
            }
        }
    })
