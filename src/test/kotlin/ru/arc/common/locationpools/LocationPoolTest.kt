package ru.arc.common.locationpools

import io.kotest.matchers.shouldBe
import org.bukkit.Location
import org.mockbukkit.mockbukkit.world.WorldMock
import ru.arc.KotestTestBase

class LocationPoolTest :
    KotestTestBase({

        lateinit var world: WorldMock

        beforeSpec {
            world = server.addSimpleWorld("test-world")
        }

        describe("LocationPool creation") {
            it("should create pool with id") {
                val pool = LocationPool("test-pool")

                pool.id shouldBe "test-pool"
                pool.isEmpty shouldBe true
            }
        }

        describe("Adding locations to pool") {
            it("should add location to pool") {
                val pool = LocationPool("test-pool")
                val location = Location(world, 10.0, 20.0, 30.0)
                val weight = 1.5

                pool.addLocation(location, weight)

                pool.size shouldBe 1
                pool.isDirty shouldBe true
            }

            it("should add multiple locations with different weights") {
                val pool = LocationPool("test-pool")
                val location1 = Location(world, 10.0, 20.0, 30.0)
                val location2 = Location(world, 20.0, 30.0, 40.0)

                pool.addLocation(location1, 1.0)
                pool.addLocation(location2, 2.0)

                pool.size shouldBe 2
            }
        }

        describe("Removing locations from pool") {
            it("should remove location from pool") {
                val pool = LocationPool("test-pool")
                val location = Location(world, 10.0, 20.0, 30.0)

                pool.addLocation(location, 1.0)
                val removed = pool.removeLocation(location)

                removed shouldBe true
                pool.size shouldBe 0
            }

            it("should return false when removing non-existent location") {
                val pool = LocationPool("test-pool")
                val location = Location(world, 10.0, 20.0, 30.0)

                val removed = pool.removeLocation(location)

                removed shouldBe false
            }
        }

        describe("Random location selection") {
            it("should return N random locations") {
                val pool = LocationPool("test-pool")
                pool.addLocation(Location(world, 10.0, 20.0, 30.0), 1.0)
                pool.addLocation(Location(world, 20.0, 30.0, 40.0), 1.0)
                pool.addLocation(Location(world, 30.0, 40.0, 50.0), 1.0)

                val random = pool.getRandomLocations(2)

                random.size shouldBe 2
            }

            it("should return at most available locations when requesting more") {
                val pool = LocationPool("test-pool")
                pool.addLocation(Location(world, 10.0, 20.0, 30.0), 1.0)

                val random = pool.getRandomLocations(5)

                random.size shouldBe 1
            }

            it("should return empty set when pool is empty") {
                val pool = LocationPool("empty-random-pool")

                val random = pool.getRandomLocations(3)

                random.isEmpty() shouldBe true
            }

            it("should return empty set when requesting zero locations") {
                val pool = LocationPool("test-pool")
                pool.addLocation(Location(world, 10.0, 20.0, 30.0), 1.0)

                val random = pool.getRandomLocations(0)

                random.isEmpty() shouldBe true
            }
        }

        describe("Nearby locations") {
            it("should find nearby locations within distance") {
                val pool = LocationPool("test-pool")
                val location1 = Location(world, 0.0, 0.0, 0.0)
                val location2 = Location(world, 100.0, 0.0, 0.0)
                val location3 = Location(world, 0.0, 0.0, 10.0)

                pool.addLocation(location1, 1.0)
                pool.addLocation(location2, 1.0)
                pool.addLocation(location3, 1.0)

                val center = Location(world, 0.0, 0.0, 5.0)
                val nearby = pool.nearbyLocations(center, 50.0)

                // location1 and location3 should be nearby, location2 should not
                nearby.size shouldBe 2
            }

            it("should return empty set for empty pool") {
                val pool = LocationPool("test-pool")
                val center = Location(world, 0.0, 0.0, 0.0)

                val nearby = pool.nearbyLocations(center, 50.0)

                nearby.isEmpty() shouldBe true
            }
        }

        describe("Dirty flag") {
            it("should start clean") {
                val pool = LocationPool("test-pool")

                pool.isDirty shouldBe false
            }

            it("should be marked as dirty after adding location") {
                val pool = LocationPool("test-pool")

                pool.addLocation(Location(world, 10.0, 20.0, 30.0), 1.0)

                pool.isDirty shouldBe true
            }

            it("should be clean after markClean") {
                val pool = LocationPool("test-pool")
                pool.addLocation(Location(world, 10.0, 20.0, 30.0), 1.0)

                pool.markClean()

                pool.isDirty shouldBe false
            }
        }
    })
