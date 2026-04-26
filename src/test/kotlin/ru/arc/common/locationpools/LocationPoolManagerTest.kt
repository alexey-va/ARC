package ru.arc.common.locationpools

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.mockbukkit.mockbukkit.entity.PlayerMock
import org.mockbukkit.mockbukkit.world.WorldMock
import ru.arc.KotestTestBase

class LocationPoolManagerTest :
    KotestTestBase({

        lateinit var world: WorldMock
        lateinit var player: PlayerMock

        beforeSpec {
            world = server.addSimpleWorld("test-world")
            player = server.addPlayer("TestPlayer")
            // Initialize the editor service
            LocationPoolEditor.init()
        }

        afterSpec {
            LocationPoolManager.clear()
        }

        describe("LocationPoolManager pool creation") {
            it("should create pool") {
                val pool = LocationPoolManager.createPool("test-pool-create")

                pool shouldNotBe null
                pool.id shouldBe "test-pool-create"
            }

            it("should return same pool instance when creating twice") {
                val pool1 = LocationPoolManager.createPool("test-pool-same")
                val pool2 = LocationPoolManager.createPool("test-pool-same")

                pool1 shouldBe pool2
            }
        }

        describe("Getting pools") {
            it("should get pool by id") {
                val created = LocationPoolManager.createPool("test-pool-get")
                val retrieved = LocationPoolManager.getPool("test-pool-get")

                created shouldBe retrieved
            }

            it("should return null for non-existent pool") {
                val pool = LocationPoolManager.getPool("non-existent-pool")

                pool.shouldBeNull()
            }

            it("should return null for null id") {
                LocationPoolManager.getPool(null).shouldBeNull()
            }
        }

        describe("Adding pools") {
            it("should add pool to manager") {
                val pool = LocationPool("test-pool-add")
                LocationPoolManager.addPool(pool)

                val retrieved = LocationPoolManager.getPool("test-pool-add")
                retrieved shouldBe pool
            }
        }

        describe("Location management") {
            it("should add location to pool") {
                val location = Location(world, 10.0, 20.0, 30.0)
                LocationPoolManager.addLocation("test-pool-loc", location)

                val pool = LocationPoolManager.getPool("test-pool-loc")
                pool shouldNotBe null
                pool!!.size shouldBe 1
            }

            it("should remove location from pool") {
                val location = Location(world, 10.0, 20.0, 30.0)
                LocationPoolManager.addLocation("test-pool-remove", location)

                val removed = LocationPoolManager.removeLocation("test-pool-remove", location)
                removed shouldBe true

                val pool = LocationPoolManager.getPool("test-pool-remove")
                pool!!.size shouldBe 0
            }

            it("should return false when removing from non-existent pool") {
                val location = Location(world, 10.0, 20.0, 30.0)

                val result = LocationPoolManager.removeLocation("non-existent-remove", location)
                result shouldBe false
            }
        }

        describe("Getting all pools") {
            it("should return all created pools") {
                // Clear first to ensure clean state
                LocationPoolManager.clear()

                LocationPoolManager.createPool("pool1")
                LocationPoolManager.createPool("pool2")
                LocationPoolManager.createPool("pool3")

                val all = LocationPoolManager.getAll()
                all.size shouldBe 3
            }
        }

        describe("Editing management") {
            it("should set and get editing state") {
                val uuid = player.uniqueId

                LocationPoolManager.setEditing(uuid, "test-pool-edit")
                val editing = LocationPoolManager.getEditing(uuid)

                editing shouldBe "test-pool-edit"
            }

            it("should cancel editing") {
                val uuid = player.uniqueId

                LocationPoolManager.setEditing(uuid, "test-pool-cancel")
                LocationPoolManager.cancelEditing(uuid, false)

                val editing = LocationPoolManager.getEditing(uuid)
                editing.shouldBeNull()
            }
        }

        describe("Block placement processing") {
            it("should add location when placing gold block") {
                val uuid = player.uniqueId
                LocationPoolManager.setEditing(uuid, "test-pool-gold")

                val block = world.getBlockAt(10, 20, 30)
                block.type = Material.GOLD_BLOCK
                val item = ItemStack(Material.GOLD_BLOCK)

                val event =
                    BlockPlaceEvent(
                        block,
                        block.state,
                        world.getBlockAt(10, 19, 30),
                        item,
                        player,
                        true,
                        EquipmentSlot.HAND,
                    )

                LocationPoolManager.processLocationPool(event)

                event.isCancelled shouldBe true
                val pool = LocationPoolManager.getPool("test-pool-gold")
                pool!!.size shouldBe 1
            }

            it("should remove location when placing redstone block") {
                val uuid = player.uniqueId
                LocationPoolManager.setEditing(uuid, "test-pool-redstone")

                val blockLocation = Location(world, 11.0, 21.0, 31.0)
                LocationPoolManager.addLocation("test-pool-redstone", blockLocation.toCenterLocation())

                val block = world.getBlockAt(11, 21, 31)
                block.type = Material.REDSTONE_BLOCK
                val item = ItemStack(Material.REDSTONE_BLOCK)
                val event =
                    BlockPlaceEvent(
                        block,
                        block.state,
                        world.getBlockAt(11, 20, 31),
                        item,
                        player,
                        true,
                        EquipmentSlot.HAND,
                    )

                LocationPoolManager.processLocationPool(event)

                event.isCancelled shouldBe true
                val pool = LocationPoolManager.getPool("test-pool-redstone")
                pool!!.size shouldBe 0
            }
        }

        describe("Pool deletion") {
            it("should delete pool") {
                LocationPoolManager.createPool("test-pool-delete")
                val deleted = LocationPoolManager.delete("test-pool-delete")

                deleted shouldBe true
                LocationPoolManager.getPool("test-pool-delete").shouldBeNull()
            }

            it("should return false when deleting non-existent pool") {
                val deleted = LocationPoolManager.delete("non-existent-delete")

                deleted shouldBe false
            }
        }

        describe("Nearby locations") {
            it("should get nearby locations") {
                val location1 = Location(world, 0.0, 0.0, 0.0)
                val location2 = Location(world, 100.0, 0.0, 0.0)
                val location3 = Location(world, 0.0, 0.0, 10.0)

                LocationPoolManager.addLocation("test-pool-nearby", location1)
                LocationPoolManager.addLocation("test-pool-nearby", location2)
                LocationPoolManager.addLocation("test-pool-nearby", location3)

                val center = Location(world, 0.0, 0.0, 5.0)
                val nearby = LocationPoolManager.getNearbyLocations("test-pool-nearby", center)

                nearby.size shouldBe 2
            }
        }

        describe("Clearing pools") {
            it("should clear all pools") {
                LocationPoolManager.createPool("pool-clear1")
                LocationPoolManager.createPool("pool-clear2")

                LocationPoolManager.clear()

                val all = LocationPoolManager.getAll()
                all.isEmpty() shouldBe true
            }
        }
    })
