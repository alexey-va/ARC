package ru.arc.util

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.bukkit.command.CommandSender
import ru.arc.KotestTestBase

class PlayerExtensionsTest :
    KotestTestBase({

        describe("hasAnyPermission") {

            it("should return true when player has at least one permission") {
                val player = server.addPlayer("TestPlayer")
                player.addAttachment(plugin, "test.permission1", true)

                player.hasAnyPermission("test.permission1", "test.permission2").shouldBeTrue()
            }

            it("should return false when player has none of the permissions") {
                val player = server.addPlayer("TestPlayer")

                player.hasAnyPermission("test.other1", "test.other2").shouldBeFalse()
            }
        }

        describe("hasAllPermissions") {

            it("should return true when player has all permissions") {
                val player = server.addPlayer("TestPlayer")
                player.addAttachment(plugin, "test.perm1", true)
                player.addAttachment(plugin, "test.perm2", true)

                player.hasAllPermissions("test.perm1", "test.perm2").shouldBeTrue()
            }

            it("should return false when player is missing a permission") {
                val player = server.addPlayer("TestPlayer")
                player.addAttachment(plugin, "test.perm1", true)

                player.hasAllPermissions("test.perm1", "test.perm2").shouldBeFalse()
            }
        }

        describe("ifPlayer") {

            it("should execute block for player") {
                val player = server.addPlayer("TestPlayer")

                val result = (player as CommandSender).ifPlayer { it.name }

                result shouldBe "TestPlayer"
            }

            it("should return null for console") {
                val console = server.consoleSender

                val result = console.ifPlayer { it.name }

                result.shouldBeNull()
            }
        }

        describe("requirePlayer") {

            it("should execute block for player") {
                val player = server.addPlayer("TestPlayer")

                val result = (player as CommandSender).requirePlayer { it.name }

                result shouldBe "TestPlayer"
            }

            it("should return null and send message for console") {
                val console = server.consoleSender

                val result = console.requirePlayer { it.name }

                result.shouldBeNull()
            }
        }
    })
