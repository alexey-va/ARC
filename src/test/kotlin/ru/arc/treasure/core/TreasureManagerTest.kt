package ru.arc.treasure.core

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import ru.arc.KotestTestBase
import java.io.File
import java.nio.file.Files

class TreasureManagerTest :
    KotestTestBase({

        lateinit var tempDir: File
        lateinit var manager: TreasureManager

        beforeEach {
            tempDir = Files.createTempDirectory("treasure-test-").toFile()
            manager = TreasureManager()
        }

        afterEach {
            tempDir.deleteRecursively()
        }

        describe("TreasureManager pool operations") {

            it("should create new pool") {
                val pool = manager.createPool("new-pool")

                pool shouldNotBe null
                pool!!.id shouldBe "new-pool"
                pool.isEmpty() shouldBe true
                manager.getPool("new-pool") shouldBe pool
            }

            it("should not create duplicate pool") {
                manager.createPool("test")
                val duplicate = manager.createPool("test")

                duplicate shouldBe null
            }

            it("should get existing pool") {
                val created = manager.createPool("existing")
                val retrieved = manager.getPool("existing")

                retrieved shouldBe created
            }

            it("should return null for unknown pool") {
                val result = manager.getPool("unknown")

                result.shouldBeNull()
            }

            it("should get or create pool") {
                val created = manager.getOrCreatePool("get-or-create")
                val retrieved = manager.getOrCreatePool("get-or-create")

                created shouldBe retrieved
                manager.allPools shouldHaveSize 1
            }

            it("should delete pool") {
                manager.createPool("to-delete")
                manager.getPool("to-delete").shouldNotBeNull()

                val deleted = manager.deletePool("to-delete")

                deleted shouldBe true
                manager.getPool("to-delete").shouldBeNull()
            }

            it("should return false when deleting nonexistent pool") {
                val deleted = manager.deletePool("nonexistent")

                deleted shouldBe false
            }

            it("should list all pools") {
                manager.createPool("pool1")
                manager.createPool("pool2")
                manager.createPool("pool3")

                val pools = manager.allPools

                pools shouldHaveSize 3
                pools.map { it.id } shouldContainExactlyInAnyOrder listOf("pool1", "pool2", "pool3")
            }
        }

        describe("TreasureManager pool modification") {

            it("should add treasure to pool") {
                val pool = manager.createPool("with-treasure")!!
                val treasure = Treasure.Item(ItemStack(Material.DIAMOND))

                val updated = manager.addTreasure(pool.id, treasure)

                updated shouldNotBe null
                updated?.size shouldBe 1
                manager.getPool(pool.id)?.size shouldBe 1
            }

            it("should remove treasure from pool") {
                val treasure = Treasure.Item(ItemStack(Material.GOLD_INGOT))
                val pool = manager.createPool("remove-treasure")!!
                manager.addTreasure(pool.id, treasure)
                manager.getPool(pool.id)?.size shouldBe 1

                val updated = manager.removeTreasure(pool.id, treasure.id)

                updated shouldNotBe null
                updated?.size shouldBe 0
            }

            it("should update treasure in pool") {
                val treasure = Treasure.Item(ItemStack(Material.STONE), weight = 1)
                val pool = manager.createPool("update-treasure")!!
                manager.addTreasure(pool.id, treasure)

                val modified = treasure.copy(weight = 10)
                val updated = manager.updateTreasure(pool.id, modified)

                updated shouldNotBe null
                updated?.findById(treasure.id)?.weight shouldBe 10
            }

            it("should update pool with messages") {
                val pool = manager.createPool("msg-pool")!!
                val message = TreasureMessage.chat("Pool message")
                val updatedPool = pool.addMessage(message)

                val result = manager.updatePool(updatedPool)

                result?.messages?.shouldHaveSize(1)
                manager
                    .getPool(pool.id)
                    ?.messages
                    ?.first()
                    ?.text shouldBe "Pool message"
            }

            it("should mark pool dirty on modifications") {
                val pool = manager.createPool("dirty-test")!!
                pool.isDirty shouldBe true // New pool is dirty

                pool.markClean()
                manager.addTreasure(pool.id, Treasure.Item(ItemStack(Material.STONE)))

                manager.getPool(pool.id)?.isDirty shouldBe true
            }
        }

        describe("TreasureManager persistence") {

            it("should save and load pools") {
                val treasure =
                    Treasure.Item(
                        ItemStack(Material.DIAMOND),
                        min = 1,
                        max = 5,
                        weight = 10,
                    )
                manager.createPool("persist-test")!!
                manager.addTreasure("persist-test", treasure)

                // Add pool message
                val poolWithMessage =
                    manager.getPool("persist-test")!!.addMessage(TreasureMessage.chat("Saved message"))
                manager.updatePool(poolWithMessage)

                // Save
                manager.saveTo(tempDir)

                // Create new manager and load
                val loadedManager = TreasureManager()
                loadedManager.loadFrom(tempDir)

                val loadedPool = loadedManager.getPool("persist-test")
                loadedPool.shouldNotBeNull()
                loadedPool.size shouldBe 1
                loadedPool.messages shouldHaveSize 1
                loadedPool.messages.first().text shouldBe "Saved message"

                val loadedTreasure = loadedPool.treasures.first() as Treasure.Item
                loadedTreasure.stack.type shouldBe Material.DIAMOND
                loadedTreasure.min shouldBe 1
                loadedTreasure.max shouldBe 5
                loadedTreasure.weight shouldBe 10
            }

            it("should only save dirty pools") {
                manager.createPool("dirty")
                manager.createPool("clean")?.let {
                    // Force clean the second pool
                    manager.addTreasure(it.id, Treasure.Item(ItemStack(Material.STONE)))
                }

                // Mark one clean, leave other dirty
                manager.getPool("clean")?.markClean()

                manager.saveDirty(tempDir)

                // Files should exist for dirty pool
                File(tempDir, "dirty.yml").exists() shouldBe true
                // Clean pool should also be saved as it was modified
            }

            it("should mark pools clean after save") {
                manager.createPool("mark-clean")
                manager.getPool("mark-clean")?.isDirty shouldBe true

                manager.saveTo(tempDir)

                manager.getPool("mark-clean")?.isDirty shouldBe false
            }

            it("should load all pool files from directory") {
                // Create pool files manually
                val pool1 =
                    TreasurePool(
                        "file-pool-1",
                        treasures =
                            listOf(
                                Treasure.Money(min = 100.0, max = 200.0),
                            ),
                    )
                val pool2 =
                    TreasurePool(
                        "file-pool-2",
                        treasures =
                            listOf(
                                Treasure.Command(commands = listOf("say hi")),
                            ),
                    )

                // Save to files
                savePoolToFile(pool1, File(tempDir, "file-pool-1.yml"))
                savePoolToFile(pool2, File(tempDir, "file-pool-2.yml"))

                // Load into manager
                manager.loadFrom(tempDir)

                manager.allPools shouldHaveSize 2
                manager.getPool("file-pool-1")?.size shouldBe 1
                manager.getPool("file-pool-2")?.size shouldBe 1
            }

            it("should handle empty data directory") {
                tempDir.deleteRecursively()
                tempDir.mkdirs()

                manager.loadFrom(tempDir)

                manager.allPools shouldHaveSize 0
            }

            it("should skip invalid pool files") {
                File(tempDir, "invalid.yml").writeText("not: valid: yaml: structure: [")

                manager.loadFrom(tempDir)

                manager.allPools shouldHaveSize 0 // Invalid file should be skipped
            }
        }

        describe("TreasureManager integration") {

            it("should handle complex pool with multiple treasure types") {
                val pool = manager.createPool("complex")!!

                manager.addTreasure(pool.id, Treasure.Item(ItemStack(Material.DIAMOND)))
                manager.addTreasure(pool.id, Treasure.Money(min = 50.0, max = 100.0))
                manager.addTreasure(pool.id, Treasure.Command(commands = listOf("msg %player% hi")))
                manager.addTreasure(pool.id, Treasure.SubPool(poolId = "other"))
                manager.addTreasure(pool.id, Treasure.Enchant(min = 1, max = 3))
                manager.addTreasure(pool.id, Treasure.Potion())

                val loaded = manager.getPool("complex")
                loaded?.size shouldBe 6
                loaded?.totalWeight shouldBe 6 // All default weight = 1

                // Test persistence
                manager.saveTo(tempDir)

                val newManager = TreasureManager()
                newManager.loadFrom(tempDir)

                val reloaded = newManager.getPool("complex")
                reloaded?.size shouldBe 6
            }

            it("should clear all pools") {
                manager.createPool("pool1")
                manager.createPool("pool2")
                manager.allPools shouldHaveSize 2

                manager.clear()

                manager.allPools shouldHaveSize 0
            }

            it("should reload pools from disk") {
                // Initial setup
                manager.createPool("initial")
                manager.saveTo(tempDir)

                // Modify in memory
                manager.createPool("memory-only")
                manager.allPools shouldHaveSize 2

                // Reload from disk - should lose memory-only pool
                manager.loadFrom(tempDir)

                manager.allPools shouldHaveSize 1
                manager.getPool("initial").shouldNotBeNull()
                manager.getPool("memory-only").shouldBeNull()
            }
        }
    })

/**
 * Helper function to save a pool to a file for testing.
 */
private fun savePoolToFile(
    pool: TreasurePool,
    file: File,
) {
    val yaml =
        org.bukkit.configuration.file
            .YamlConfiguration()
    val map = pool.toMap()
    map.forEach { (key, value) -> yaml.set(key, value) }
    yaml.save(file)
}
