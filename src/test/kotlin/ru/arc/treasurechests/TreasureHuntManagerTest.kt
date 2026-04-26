package ru.arc.treasurechests

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.bukkit.Location
import org.mockbukkit.mockbukkit.entity.PlayerMock
import org.mockbukkit.mockbukkit.world.WorldMock
import ru.arc.KotestTestBase
import ru.arc.common.locationpools.LocationPool
import ru.arc.common.locationpools.LocationPoolManager
import ru.arc.treasure.core.Treasure
import ru.arc.treasure.core.TreasurePool
import ru.arc.treasure.core.Treasures

class TreasureHuntManagerTest :
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
            TreasureHuntManager.stopAll()
            LocationPoolManager.clear()
            Treasures.clear()
        }

        describe("TreasureHuntManager hunt management") {
            it("should get active hunts") {
                val activeHunts = TreasureHuntManager.getActiveHunts()

                activeHunts shouldNotBe null
                activeHunts.isEmpty() shouldBe true
            }

            it("should stop all hunts") {
                TreasureHuntManager.stopAll()

                val activeHunts = TreasureHuntManager.getActiveHunts()
                activeHunts.isEmpty() shouldBe true
            }
        }

        describe("Getting hunts by location pool") {
            it("should return empty optional when no hunt exists") {
                val result = TreasureHuntManager.getByLocationPool(locationPool)

                result.isEmpty shouldBe true
            }
        }

        describe("Getting hunts by block") {
            it("should return null for non-existent block") {
                val block = world.getBlockAt(100, 100, 100)
                val hunt = TreasureHuntManager.getByBlock(block)

                hunt.shouldBeNull()
            }
        }

        describe("Treasure hunt types") {
            it("should get treasure hunt types list") {
                val types = TreasureHuntManager.getTreasureHuntTypes()

                types shouldNotBe null
                // Initially empty unless loaded from config
            }

            it("should get treasure hunt type by id") {
                val type = TreasureHuntManager.getTreasureHuntType("non-existent")

                type.shouldBeNull()
            }
        }

        describe("Player quit handling") {
            it("should handle player quit without errors") {
                // Should not throw exception
                TreasureHuntManager.onPlayerQuit(player)
            }
        }

        describe("Treasure pools") {
            it("should get treasure pools") {
                val pools = TreasureHuntManager.getTreasurePools()

                pools shouldNotBe null
            }
        }

        describe("TreasureHuntRegistry") {
            it("should return aliases map") {
                val aliases = TreasureHuntRegistry.getAliases()
                aliases shouldNotBe null
            }

            it("should return default start message") {
                val message = TreasureHuntRegistry.getDefaultStartMessage()
                message shouldNotBe null
            }

            it("should return default stop message") {
                val message = TreasureHuntRegistry.getDefaultStopMessage()
                message shouldNotBe null
            }
        }
    })
