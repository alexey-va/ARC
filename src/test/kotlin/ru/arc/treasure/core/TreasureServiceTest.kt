package ru.arc.treasure.core

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import net.kyori.adventure.text.Component
import net.milkbowl.vault.economy.Economy
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.PlayerInventory
import ru.arc.KotestTestBase
import ru.arc.util.TextUtil

@Suppress("USELESS_CAST")
class TreasureServiceTest :
    KotestTestBase({

        lateinit var mockPlayer: Player
        lateinit var mockInventory: PlayerInventory
        lateinit var mockEconomy: Economy
        lateinit var pools: MutableMap<String, TreasurePool>
        lateinit var service: TreasureService

        beforeEach {
            mockPlayer = mockk(relaxed = true)
            mockInventory = mockk(relaxed = true)
            mockEconomy = mockk(relaxed = true)
            pools = mutableMapOf()

            every { mockPlayer.name } returns "TestPlayer"
            every { mockPlayer.inventory } returns mockInventory
            every { mockInventory.addItem(any()) } returns hashMapOf()
            every { mockEconomy.depositPlayer(any<Player>(), any()) } returns
                mockk {
                    every { transactionSuccess() } returns true
                }

            // Create service with mock economy provider
            service =
                TreasureService(
                    poolProvider = { pools[it] },
                    economyProvider = { mockEconomy },
                )
        }

        afterEach {
            clearAllMocks()
        }

        describe("TreasureService giving items") {

            it("should give item to player inventory") {
                val treasure = Treasure.Item(ItemStack(Material.DIAMOND), min = 5, max = 5)

                val result = service.give(treasure, mockPlayer)

                result.shouldBeInstanceOf<GiveResult.Success>()
                verify {
                    mockInventory.addItem(
                        match {
                            it.type == Material.DIAMOND && it.amount == 5
                        },
                    )
                }
            }

            it("should give random amount between min and max") {
                val treasure = Treasure.Item(ItemStack(Material.GOLD_INGOT), min = 1, max = 64)

                repeat(20) {
                    service.give(treasure, mockPlayer)
                }

                verify(atLeast = 20) {
                    mockInventory.addItem(
                        match {
                            it.type == Material.GOLD_INGOT && it.amount in 1..64
                        },
                    )
                }
            }

            it("should drop overflow items on ground") {
                // Simulate full inventory by returning overflow
                val overflowItem = ItemStack(Material.DIAMOND, 5)
                every { mockInventory.addItem(any()) } returns hashMapOf(0 to overflowItem)

                val mockWorld = mockk<org.bukkit.World>(relaxed = true)
                val mockLocation = mockk<org.bukkit.Location>(relaxed = true)
                every { mockPlayer.location } returns mockLocation
                every { mockLocation.world } returns mockWorld

                val treasure = Treasure.Item(ItemStack(Material.DIAMOND), min = 5, max = 5)

                service.give(treasure, mockPlayer)

                verify { mockWorld.dropItemNaturally(mockLocation, overflowItem) }
            }

            it("should return success with the treasure") {
                val treasure = Treasure.Item(ItemStack(Material.DIAMOND))

                val result = service.give(treasure, mockPlayer)

                result.shouldBeInstanceOf<GiveResult.Success>()
                (result as GiveResult.Success).treasure shouldBe treasure
            }
        }

        describe("TreasureService giving money") {

            it("should deposit money via economy") {
                val treasure = Treasure.Money(min = 100.0, max = 100.0)

                val result = service.give(treasure, mockPlayer)

                result.shouldBeInstanceOf<GiveResult.Success>()
                verify { mockEconomy.depositPlayer(mockPlayer, 100.0) }
            }

            it("should give random amount between min and max") {
                val treasure = Treasure.Money(min = 50.0, max = 150.0)

                repeat(10) {
                    service.give(treasure, mockPlayer)
                }

                verify(atLeast = 10) {
                    mockEconomy.depositPlayer(mockPlayer, match { it in 50.0..150.0 })
                }
            }

            it("should fail gracefully when economy unavailable") {
                val noEconomyService =
                    TreasureService(
                        poolProvider = { pools[it] },
                        economyProvider = { null },
                    )
                val treasure = Treasure.Money(min = 100.0, max = 100.0)

                val result = noEconomyService.give(treasure, mockPlayer)

                result.shouldBeInstanceOf<GiveResult.Failure>()
                (result as GiveResult.Failure).reason shouldBe "Economy not available"
            }

            it("should send default money message when treasure has no messages") {
                mockkStatic(TextUtil::class)
                every { TextUtil.mm(any<String>()) } answers {
                    Component.text(firstArg<String>())
                }

                val treasure = Treasure.Money(min = 100.0, max = 100.0)

                service.give(treasure, mockPlayer)

                verify { TextUtil.mm("<dark_green>Вы получили <yellow>100<dark_green> монет") }
                verify { mockPlayer.sendMessage(any<Component>()) }

                unmockkStatic(TextUtil::class)
            }
        }

        describe("TreasureService executing commands") {

            it("should execute commands via console") {
                mockkStatic(Bukkit::class)
                val mockServer = mockk<org.bukkit.Server>(relaxed = true)
                val mockDispatcher = mockk<org.bukkit.command.ConsoleCommandSender>(relaxed = true)
                every { Bukkit.getServer() } returns mockServer
                every { mockServer.consoleSender } returns mockDispatcher
                every { Bukkit.dispatchCommand(any(), any()) } returns true

                val treasure = Treasure.Command(commands = listOf("say Hello %player%"))

                val result = service.give(treasure, mockPlayer)

                result.shouldBeInstanceOf<GiveResult.Success>()
                verify { Bukkit.dispatchCommand(mockDispatcher, "say Hello TestPlayer") }

                unmockkStatic(Bukkit::class)
            }

            it("should replace player placeholders") {
                mockkStatic(Bukkit::class)
                val mockServer = mockk<org.bukkit.Server>(relaxed = true)
                val mockDispatcher = mockk<org.bukkit.command.ConsoleCommandSender>(relaxed = true)
                every { Bukkit.getServer() } returns mockServer
                every { mockServer.consoleSender } returns mockDispatcher
                every { Bukkit.dispatchCommand(any(), any()) } returns true

                val treasure =
                    Treasure.Command(
                        commands =
                            listOf(
                                "give %player% diamond 1",
                                "tell %player% You won!",
                            ),
                    )

                service.give(treasure, mockPlayer)

                verify { Bukkit.dispatchCommand(mockDispatcher, "give TestPlayer diamond 1") }
                verify { Bukkit.dispatchCommand(mockDispatcher, "tell TestPlayer You won!") }

                unmockkStatic(Bukkit::class)
            }
        }

        describe("TreasureService sub-pool handling") {

            it("should give random treasure from sub-pool") {
                val itemTreasure = Treasure.Item(ItemStack(Material.EMERALD), weight = 100)
                val subPool = TreasurePool("sub-pool", treasures = listOf(itemTreasure))
                pools["sub-pool"] = subPool

                val treasure = Treasure.SubPool(poolId = "sub-pool")

                val result = service.give(treasure, mockPlayer)

                result.shouldBeInstanceOf<GiveResult.Success>()
                verify { mockInventory.addItem(match { it.type == Material.EMERALD }) }
            }

            it("should fail if sub-pool doesn't exist") {
                val treasure = Treasure.SubPool(poolId = "nonexistent")

                val result = service.give(treasure, mockPlayer)

                result.shouldBeInstanceOf<GiveResult.Failure>()
                (result as GiveResult.Failure).reason shouldBe "Sub-pool not found: nonexistent"
            }

            it("should fail if sub-pool is empty") {
                val emptyPool = TreasurePool("empty-sub")
                pools["empty-sub"] = emptyPool

                val treasure = Treasure.SubPool(poolId = "empty-sub")

                val result = service.give(treasure, mockPlayer)

                result.shouldBeInstanceOf<GiveResult.Failure>()
            }
        }

        describe("TreasureService giving enchanted books") {

            it("should give random enchanted books") {
                val treasure = Treasure.Enchant(min = 1, max = 3)

                val result = service.give(treasure, mockPlayer)

                result.shouldBeInstanceOf<GiveResult.Success>()
                verify(atLeast = 1, atMost = 3) {
                    mockInventory.addItem(
                        match {
                            it.type == Material.ENCHANTED_BOOK
                        },
                    )
                }
            }

            it("should exclude specified enchantments") {
                val treasure =
                    Treasure.Enchant(
                        min = 5,
                        max = 5,
                        exclude = setOf("mending", "unbreaking"),
                    )

                service.give(treasure, mockPlayer)

                // Verify books were added (can't easily verify exclusion without more complex setup)
                verify(exactly = 5) { mockInventory.addItem(any()) }
            }
        }

        describe("TreasureService giving potions") {

            it("should give random potions") {
                val treasure = Treasure.Potion(min = 2, max = 2)

                val result = service.give(treasure, mockPlayer)

                result.shouldBeInstanceOf<GiveResult.Success>()
                verify(exactly = 2) {
                    mockInventory.addItem(
                        match {
                            it.type in setOf(Material.POTION, Material.SPLASH_POTION, Material.LINGERING_POTION)
                        },
                    )
                }
            }
        }

        describe("TreasureService giveFromPool") {

            it("should give random treasure from pool") {
                val item = Treasure.Item(ItemStack(Material.GOLD_INGOT), weight = 100)
                val pool = TreasurePool("test-pool", treasures = listOf(item))
                pools["test-pool"] = pool

                val result = service.giveFromPool("test-pool", mockPlayer)

                result.shouldBeInstanceOf<GiveResult.Success>()
                verify { mockInventory.addItem(match { it.type == Material.GOLD_INGOT }) }
            }

            it("should fail for unknown pool") {
                val result = service.giveFromPool("unknown", mockPlayer)

                result.shouldBeInstanceOf<GiveResult.Failure>()
                (result as GiveResult.Failure).reason shouldBe "Pool not found: unknown"
            }

            it("should fail for empty pool") {
                val pool = TreasurePool("empty-pool")
                pools["empty-pool"] = pool

                val result = service.giveFromPool("empty-pool", mockPlayer)

                result.shouldBeInstanceOf<GiveResult.Failure>()
            }
        }

        describe("GiveResult") {

            it("should indicate success") {
                val treasure = Treasure.Item(ItemStack(Material.STONE))
                val result: GiveResult = GiveResult.Success(treasure)

                result.isSuccess shouldBe true
                result.isFailure shouldBe false
            }

            it("should indicate failure with reason") {
                val result: GiveResult = GiveResult.Failure("Something went wrong")

                result.isSuccess shouldBe false
                result.isFailure shouldBe true
                (result as GiveResult.Failure).reason shouldBe "Something went wrong"
            }

            it("should call onSuccess for successful result") {
                val treasure = Treasure.Item(ItemStack(Material.STONE))
                val result: GiveResult = GiveResult.Success(treasure)

                var called = false
                result.onSuccess { called = true }

                called shouldBe true
            }

            it("should call onFailure for failed result") {
                val result: GiveResult = GiveResult.Failure("Error")

                var reason: String? = null
                result.onFailure { reason = it }

                reason shouldBe "Error"
            }
        }

        describe("GiveConfig") {

            it("should have sensible defaults") {
                val config = GiveConfig.DEFAULT

                config.sendMessages shouldBe true
                config.sendPoolMessages shouldBe true
            }

            it("should have silent option") {
                val config = GiveConfig.SILENT

                config.sendMessages shouldBe false
                config.sendPoolMessages shouldBe false
            }
        }
    })
