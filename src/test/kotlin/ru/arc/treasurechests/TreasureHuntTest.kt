package ru.arc.treasurechests

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.bukkit.Location
import org.mockbukkit.mockbukkit.entity.PlayerMock
import org.mockbukkit.mockbukkit.world.WorldMock
import ru.arc.KotestTestBase
import ru.arc.common.WeightedRandom
import ru.arc.common.locationpools.LocationPool
import ru.arc.common.locationpools.LocationPoolManager
import ru.arc.treasure.core.Treasure
import ru.arc.treasure.core.TreasurePool
import ru.arc.treasure.core.Treasures
import java.util.concurrent.ConcurrentHashMap

class TreasureHuntTest :
    KotestTestBase({

        lateinit var world: WorldMock
        lateinit var player: PlayerMock
        lateinit var locationPool: LocationPool
        lateinit var treasurePool: TreasurePool

        beforeSpec {
            world = server.addSimpleWorld("test-world")
            player = server.addPlayer("TestPlayer")

            // Clear treasures for clean tests
            Treasures.clear()

            // Setup location pool
            locationPool = LocationPoolManager.createPool("test-location-pool")
            val location1 = Location(world, 10.0, 20.0, 30.0)
            val location2 = Location(world, 20.0, 30.0, 40.0)
            locationPool.addLocation(location1, 1.0)
            locationPool.addLocation(location2, 1.0)

            // Setup treasure pool
            treasurePool = Treasures.getOrCreate("test-treasure-pool")
            val treasure = Treasure.Money(min = 100.0, max = 200.0, weight = 10)
            Treasures.addTreasure("test-treasure-pool", treasure)
            treasurePool = Treasures.getPool("test-treasure-pool")!!
        }

        afterSpec {
            TreasureHuntRegistry.stopAll()
            LocationPoolManager.clear()
            Treasures.clear()
        }

        describe("TreasureHuntConfig") {
            it("should create config with chests and location pool") {
                val chestType = ChestType.vanilla("test-treasure-pool")
                val chestTypes =
                    WeightedRandom<ChestType>().apply {
                        add(chestType, 1.0)
                    }

                val config =
                    TreasureHuntConfig(
                        id = "test-config",
                        locationPoolId = "test-location-pool",
                        chestTypes = chestTypes,
                    )

                config.locationPoolId shouldBe "test-location-pool"
                config.getLocationPool() shouldNotBe null
            }

            it("should get random chest type") {
                val chestType1 = ChestType.vanilla("test-treasure-pool", weight = 1)
                val chestType2 = ChestType.vanilla("test-treasure-pool", weight = 2)

                val chestTypes =
                    WeightedRandom<ChestType>().apply {
                        add(chestType1, 1.0)
                        add(chestType2, 2.0)
                    }

                val config =
                    TreasureHuntConfig(
                        id = "test-config",
                        locationPoolId = "test-location-pool",
                        chestTypes = chestTypes,
                    )

                val random = config.getRandomChestType()
                (random == chestType1 || random == chestType2) shouldBe true
            }
        }

        describe("ChestType") {
            it("should get treasure pool") {
                val chestType = ChestType.vanilla("test-treasure-pool")

                val pool = chestType.getTreasurePool()

                pool shouldNotBe null
                pool!!.id shouldBe "test-treasure-pool"
            }

            it("should return null for non-existent treasure pool") {
                val chestType = ChestType.vanilla("non-existent")

                val pool = chestType.getTreasurePool()

                pool.shouldBeNull()
            }

            it("should create vanilla chest type") {
                val chestType =
                    ChestType.vanilla(
                        treasurePoolId = "test",
                        particlePath = "custom",
                        weight = 5,
                    )

                chestType.type shouldBe ChestVariant.VANILLA
                chestType.treasurePoolId shouldBe "test"
                chestType.particlePath shouldBe "custom"
                chestType.weight shouldBe 5
                chestType.namespaceId.shouldBeNull()
            }

            it("should create ItemsAdder chest type") {
                val chestType =
                    ChestType.itemsAdder(
                        namespaceId = "custom:chest",
                        treasurePoolId = "test",
                        particlePath = "custom",
                        weight = 3,
                    )

                chestType.type shouldBe ChestVariant.ITEMS_ADDER
                chestType.namespaceId shouldBe "custom:chest"
            }
        }

        describe("ChestVariant") {
            it("should parse VANILLA from string") {
                ChestVariant.fromString("VANILLA") shouldBe ChestVariant.VANILLA
                ChestVariant.fromString("vanilla") shouldBe ChestVariant.VANILLA
            }

            it("should parse ITEMS_ADDER from various strings") {
                ChestVariant.fromString("IA") shouldBe ChestVariant.ITEMS_ADDER
                ChestVariant.fromString("ITEMS_ADDER") shouldBe ChestVariant.ITEMS_ADDER
                ChestVariant.fromString("itemsadder") shouldBe ChestVariant.ITEMS_ADDER
            }
        }

        describe("BossBarConfig") {
            it("should create from strings with defaults") {
                val config = BossBarConfig.fromStrings()

                config.visible shouldBe true
                config.color shouldBe net.kyori.adventure.bossbar.BossBar.Color.RED
                config.overlay shouldBe net.kyori.adventure.bossbar.BossBar.Overlay.PROGRESS
            }

            it("should handle invalid color gracefully") {
                val config = BossBarConfig.fromStrings(colorStr = "INVALID")

                config.color shouldBe net.kyori.adventure.bossbar.BossBar.Color.RED
            }
        }

        describe("ActiveHunt") {
            it("should create with config") {
                val chestType = ChestType.vanilla("test-treasure-pool")
                val chestTypes =
                    WeightedRandom<ChestType>().apply {
                        add(chestType, 1.0)
                    }
                val config =
                    TreasureHuntConfig(
                        id = "test",
                        locationPoolId = "test-location-pool",
                        chestTypes = chestTypes,
                    )

                val hunt =
                    ActiveHunt(
                        config = config,
                        world = world,
                        chests = ConcurrentHashMap(),
                        totalChests = 5,
                        startTime = System.currentTimeMillis(),
                    )

                hunt.totalChests shouldBe 5
                hunt.remainingChests shouldBe 0 // Пустая коллекция сундуков
            }

            it("should calculate progress correctly") {
                val chestType = ChestType.vanilla("test-treasure-pool")
                val chestTypes =
                    WeightedRandom<ChestType>().apply {
                        add(chestType, 1.0)
                    }
                val config =
                    TreasureHuntConfig(
                        id = "test",
                        locationPoolId = "test-location-pool",
                        chestTypes = chestTypes,
                    )

                val hunt =
                    ActiveHunt(
                        config = config,
                        world = world,
                        chests = ConcurrentHashMap(),
                        totalChests = 10,
                        startTime = System.currentTimeMillis(),
                    )

                // Пустая коллекция -> progress = 0
                hunt.progress shouldBe 0f
            }

            it("should track remaining chests") {
                val chestType = ChestType.vanilla("test-treasure-pool")
                val chestTypes =
                    WeightedRandom<ChestType>().apply {
                        add(chestType, 1.0)
                    }
                val config =
                    TreasureHuntConfig(
                        id = "test",
                        locationPoolId = "test-location-pool",
                        chestTypes = chestTypes,
                    )

                val chests = ConcurrentHashMap<Location, PlacedChest>()
                val hunt =
                    ActiveHunt(
                        config = config,
                        world = world,
                        chests = chests,
                        totalChests = 5,
                        startTime = System.currentTimeMillis(),
                    )

                hunt.remainingChests shouldBe 0
                hunt.progress shouldBe 0f
            }
        }
    })
