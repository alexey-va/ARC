package ru.arc.treasure.core

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.bukkit.Bukkit
import org.bukkit.command.ConsoleCommandSender
import org.bukkit.entity.Player
import ru.arc.KotestTestBase

class AeLootTest :
    KotestTestBase({

        it("should build ae giveitem command with resolved args") {
            val treasure =
                Treasure.Ae(
                    kind = AeKind.ITEM,
                    itemName = "magic",
                    amount = 1,
                    args = listOf(AeArg.RandomTier),
                )

            val command = AeLoot.buildCommand("Steve", treasure)

            command shouldStartWith "ae giveitem Steve magic 1 "
            listOf("SIMPLE", "UNIQUE", "ELITE", "ULTIMATE", "LEGENDARY", "FABLED")
                .any { command.endsWith(" $it") || command.contains(" $it") } shouldBe true
        }

        it("should build ae giverandombook command") {
            val treasure =
                Treasure.Ae(
                    kind = AeKind.RANDOM_BOOK,
                    args = listOf(AeArg.RandomTier),
                )

            AeLoot.buildCommand("Alex", treasure) shouldStartWith "ae giverandombook Alex "
        }

        it("should parse ae args from yaml map") {
            val args =
                AeArg.parseList(
                    listOf(
                        mapOf("tier" to "random"),
                        mapOf("slot" to "random"),
                        mapOf("int" to "10-13"),
                        mapOf("int" to 5),
                    ),
                )

            args.size shouldBe 4
            args[0] shouldBe AeArg.RandomTier
            args[1] shouldBe AeArg.RandomSlot
            args[2] shouldBe AeArg.IntRange(10, 13)
            args[3] shouldBe AeArg.IntRange(5, 5)
        }
    })

class TreasureNativeLootTypesTest :
    KotestTestBase({

        it("should deserialize ae treasure") {
            val parsed =
                Treasure.fromMap(
                    mapOf(
                        "type" to "ae",
                        "kind" to "item",
                        "name" to "mystery",
                        "weight" to 20,
                    ),
                ) as Treasure.Ae

            parsed.kind shouldBe AeKind.ITEM
            parsed.itemName shouldBe "mystery"
            parsed.weight shouldBe 20
        }

        it("should deserialize slimefun treasure with range") {
            val parsed =
                Treasure.fromMap(
                    mapOf(
                        "type" to "slimefun",
                        "item-id" to "STEEL_INGOT",
                        "amount" to "5-10",
                        "weight" to 15,
                    ),
                ) as Treasure.Slimefun

            parsed.itemId shouldBe "STEEL_INGOT"
            parsed.min shouldBe 5
            parsed.max shouldBe 10
            parsed.weight shouldBe 15
        }
    })

class TreasureServiceNativeLootTest :
    KotestTestBase({

        afterEach {
            unmockkStatic(Bukkit::class)
        }

        it("should dispatch ae command on give") {
            mockkStatic(Bukkit::class)
            val mockServer = mockk<org.bukkit.Server>(relaxed = true)
            val console = mockk<ConsoleCommandSender>(relaxed = true)
            every { Bukkit.getServer() } returns mockServer
            every { mockServer.consoleSender } returns console
            every { Bukkit.dispatchCommand(any(), any()) } returns true

            val player = mockk<Player>(relaxed = true)
            every { player.name } returns "Steve"

            val service = TreasureService(poolProvider = { null }, economyProvider = { null })
            val treasure =
                Treasure.Ae(
                    kind = AeKind.ITEM,
                    itemName = "mystery",
                    amount = 1,
                )

            service.give(treasure, player)

            verify {
                Bukkit.dispatchCommand(console, "ae giveitem Steve mystery 1")
            }
        }

        it("should dispatch slimefun command on give") {
            mockkStatic(Bukkit::class)
            val mockServer = mockk<org.bukkit.Server>(relaxed = true)
            val console = mockk<ConsoleCommandSender>(relaxed = true)
            every { Bukkit.getServer() } returns mockServer
            every { mockServer.consoleSender } returns console
            every { Bukkit.dispatchCommand(any(), any()) } returns true

            val player = mockk<Player>(relaxed = true)
            every { player.name } returns "Steve"

            val service = TreasureService(poolProvider = { null }, economyProvider = { null })
            val treasure = Treasure.Slimefun(itemId = "STEEL_INGOT", min = 3, max = 3)

            service.give(treasure, player)

            verify {
                Bukkit.dispatchCommand(console, "sf give Steve STEEL_INGOT 3")
            }
        }
    })
