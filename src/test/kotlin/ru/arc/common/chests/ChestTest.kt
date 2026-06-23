package ru.arc.common.chests

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.Material
import org.bukkit.NamespacedKey
import ru.arc.KotestTestBase
import ru.arc.treasurechests.HuntFurnitureRegistry
import java.nio.file.Files

class ChestTest :
    KotestTestBase({

        beforeTest {
            HuntFurnitureRegistry.resetForTests()
            HuntFurnitureRegistry.fileOverride = Files.createTempFile("hunt-furniture-test", ".json")
        }

        afterTest {
            HuntFurnitureRegistry.fileOverride = null
        }

        describe("VanillaChest") {

            describe("create") {

                it("should place chest on air block and return true") {
                    val world = server.addSimpleWorld("test")
                    val block = world.getBlockAt(0, 64, 0)
                    block.type = Material.AIR

                    val chest = VanillaChest(block)
                    val result = chest.create()

                    result.shouldBeTrue()
                    block.type shouldBe Material.CHEST
                }

                it("should not place chest on non-air block and return false") {
                    val world = server.addSimpleWorld("test")
                    val block = world.getBlockAt(0, 64, 0)
                    block.type = Material.STONE

                    val chest = VanillaChest(block)
                    val result = chest.create()

                    result.shouldBeFalse()
                    block.type shouldBe Material.STONE
                }

                it("should set custom block data marker") {
                    val world = server.addSimpleWorld("test")
                    val block = world.getBlockAt(0, 64, 0)
                    block.type = Material.AIR

                    val mockProvider = mockk<BlockDataProvider>(relaxed = true)
                    val chest = VanillaChest(block, mockProvider)
                    chest.create()

                    verify {
                        mockProvider.setMarker(block, any<NamespacedKey>(), VanillaChest.MARKER_VALUE)
                    }
                }
            }

            describe("destroy") {

                it("should remove chest and set block to air") {
                    val world = server.addSimpleWorld("test")
                    val block = world.getBlockAt(0, 64, 0)
                    block.type = Material.AIR

                    val chest = VanillaChest(block)
                    chest.create()
                    block.type shouldBe Material.CHEST

                    chest.destroy()
                    block.type shouldBe Material.AIR
                }

                it("should remove custom block data marker") {
                    val world = server.addSimpleWorld("test")
                    val block = world.getBlockAt(0, 64, 0)
                    block.type = Material.AIR

                    val mockProvider = mockk<BlockDataProvider>(relaxed = true)
                    val chest = VanillaChest(block, mockProvider)
                    chest.create()
                    chest.destroy()

                    verify {
                        mockProvider.removeMarker(block, any<NamespacedKey>())
                    }
                }

                it("should not modify non-chest block") {
                    val world = server.addSimpleWorld("test")
                    val block = world.getBlockAt(0, 64, 0)
                    block.type = Material.STONE

                    val chest = VanillaChest(block)
                    chest.destroy()

                    block.type shouldBe Material.STONE
                }
            }

            describe("blockLocation") {

                it("should return block location") {
                    val world = server.addSimpleWorld("test")
                    val block = world.getBlockAt(10, 64, 20)

                    val chest = VanillaChest(block)
                    val location = chest.blockLocation

                    location.blockX shouldBe 10
                    location.blockY shouldBe 64
                    location.blockZ shouldBe 20
                    location.world shouldBe world
                }
            }
        }

        describe("ItemsAdderChest") {

            describe("create") {

                it("should not place on non-air block and return false") {
                    val world = server.addSimpleWorld("test")
                    val block = world.getBlockAt(0, 64, 0)
                    block.type = Material.STONE

                    val mockFurnitureProvider = mockk<FurnitureProvider>(relaxed = true)
                    val chest =
                        ItemsAdderChest(
                            block = block,
                            namespaceId = "test:chest",
                            furnitureProvider = mockFurnitureProvider,
                        )
                    val result = chest.create()

                    result.shouldBeFalse()
                    verify(exactly = 0) { mockFurnitureProvider.spawn(any(), any()) }
                }

                it("should spawn furniture and set marker on success") {
                    val world = server.addSimpleWorld("test")
                    val block = world.getBlockAt(0, 64, 0)
                    block.type = Material.AIR

                    val mockFurniture = mockk<Any>()
                    val mockEntity = world.spawn(block.location, org.bukkit.entity.ArmorStand::class.java)
                    val mockScanner =
                        mockk<FurnitureEntityScanner> {
                            every { snapshotNear(block, any()) } returnsMany
                                listOf(
                                    emptySet(),
                                    setOf(mockEntity.uniqueId),
                                )
                        }
                    val mockFurnitureProvider =
                        mockk<FurnitureProvider> {
                            every { spawn("test:chest", block) } returns mockFurniture
                            every { getEntity(mockFurniture) } returns mockEntity
                        }
                    val mockBlockDataProvider = mockk<BlockDataProvider>(relaxed = true)

                    val chest =
                        ItemsAdderChest(
                            block = block,
                            namespaceId = "test:chest",
                            blockDataProvider = mockBlockDataProvider,
                            furnitureProvider = mockFurnitureProvider,
                            entityScanner = mockScanner,
                        )
                    val result = chest.create()

                    result.shouldBeTrue()
                    verify { mockFurnitureProvider.spawn("test:chest", block) }
                    verify { mockBlockDataProvider.setMarker(block, any(), ItemsAdderChest.MARKER_VALUE) }
                    HuntFurnitureRegistry.entityIdsAt(block) shouldBe listOf(mockEntity.uniqueId)
                }

                it("should return false when furniture spawn fails") {
                    val world = server.addSimpleWorld("test")
                    val block = world.getBlockAt(0, 64, 0)
                    block.type = Material.AIR

                    val mockFurnitureProvider =
                        mockk<FurnitureProvider> {
                            every { spawn("test:chest", block) } returns null
                        }
                    val mockBlockDataProvider = mockk<BlockDataProvider>(relaxed = true)

                    val chest =
                        ItemsAdderChest(
                            block = block,
                            namespaceId = "test:chest",
                            blockDataProvider = mockBlockDataProvider,
                            furnitureProvider = mockFurnitureProvider,
                        )
                    val result = chest.create()

                    result.shouldBeFalse()
                    verify(exactly = 0) { mockBlockDataProvider.setMarker(any(), any(), any()) }
                }
            }

            describe("destroy") {

                it("should remove tracked entities from registry on destroy") {
                    val world = server.addSimpleWorld("test")
                    val block = world.getBlockAt(0, 64, 0)
                    val entity = world.spawn(block.location, org.bukkit.entity.ArmorStand::class.java)

                    HuntFurnitureRegistry.register(block, listOf(entity.uniqueId))
                    val mockBlockDataProvider = mockk<BlockDataProvider>(relaxed = true)
                    val mockFurnitureProvider = mockk<FurnitureProvider>(relaxed = true)

                    val chest =
                        ItemsAdderChest(
                            block = block,
                            namespaceId = "test:chest",
                            blockDataProvider = mockBlockDataProvider,
                            furnitureProvider = mockFurnitureProvider,
                        )
                    chest.destroy()

                    verify { mockFurnitureProvider.removeEntity(entity, false) }
                    HuntFurnitureRegistry.entityIdsAt(block).shouldBe(emptyList())
                }

                it("should fallback to furniture api when registry is empty") {
                    val world = server.addSimpleWorld("test")
                    val block = world.getBlockAt(0, 64, 0)

                    val mockFurniture = mockk<Any>()
                    val mockBlockDataProvider = mockk<BlockDataProvider>(relaxed = true)
                    val mockFurnitureProvider =
                        mockk<FurnitureProvider>(relaxed = true) {
                            every { getByBlock(block) } returns mockFurniture
                        }

                    val chest =
                        ItemsAdderChest(
                            block = block,
                            namespaceId = "test:chest",
                            blockDataProvider = mockBlockDataProvider,
                            furnitureProvider = mockFurnitureProvider,
                        )
                    chest.destroy()

                    verify { mockFurnitureProvider.remove(mockFurniture, false) }
                }

                it("should cleanup stored and neighbor barrier blocks on destroy") {
                    val world = server.addSimpleWorld("test")
                    val block = world.getBlockAt(0, 64, 0)

                    block.getRelative(0, 1, 0).type = Material.BARRIER
                    block.getRelative(5, 2, 0).type = Material.BARRIER

                    HuntFurnitureRegistry.register(
                        block,
                        emptyList(),
                        listOf(BlockPos.of(block.getRelative(5, 2, 0))),
                    )

                    val chest =
                        ItemsAdderChest(
                            block = block,
                            namespaceId = "test:chest",
                            blockDataProvider = mockk(relaxed = true),
                            furnitureProvider = mockk(relaxed = true),
                        )
                    chest.destroy()

                    block.getRelative(0, 1, 0).type shouldBe Material.AIR
                    block.getRelative(5, 2, 0).type shouldBe Material.AIR
                }

                @Suppress("DEPRECATION")
                it("should cleanup invisible item frames with custom model data") {
                    val world = server.addSimpleWorld("test")
                    val block = world.getBlockAt(0, 64, 0)

                    // Спавним невидимую рамку с предметом
                    val frame = world.spawn(block.location.toCenterLocation(), org.bukkit.entity.ItemFrame::class.java)
                    frame.isVisible = false
                    val item = org.bukkit.inventory.ItemStack(Material.PAPER)
                    val meta = item.itemMeta
                    meta.setCustomModelData(12345) // Deprecated, но работает с MockBukkit
                    item.itemMeta = meta
                    frame.setItem(item)

                    val mockBlockDataProvider =
                        mockk<BlockDataProvider>(relaxed = true) {
                            every { getMarker(block, any()) } returns ItemsAdderChest.MARKER_VALUE
                        }
                    val mockFurnitureProvider = mockk<FurnitureProvider>(relaxed = true)

                    val chest =
                        ItemsAdderChest(
                            block = block,
                            namespaceId = "test:chest",
                            blockDataProvider = mockBlockDataProvider,
                            furnitureProvider = mockFurnitureProvider,
                        )
                    chest.destroy()

                    // Рамка должна быть удалена
                    frame.isDead shouldBe true
                }

                @Suppress("DEPRECATION")
                it("should not cleanup visible item frames") {
                    val world = server.addSimpleWorld("test")
                    val block = world.getBlockAt(0, 64, 0)

                    // Спавним видимую рамку
                    val frame = world.spawn(block.location.toCenterLocation(), org.bukkit.entity.ItemFrame::class.java)
                    frame.isVisible = true
                    val item = org.bukkit.inventory.ItemStack(Material.PAPER)
                    val meta = item.itemMeta
                    meta.setCustomModelData(12345)
                    item.itemMeta = meta
                    frame.setItem(item)

                    val mockBlockDataProvider =
                        mockk<BlockDataProvider>(relaxed = true) {
                            every { getMarker(block, any()) } returns ItemsAdderChest.MARKER_VALUE
                        }
                    val mockFurnitureProvider = mockk<FurnitureProvider>(relaxed = true)

                    val chest =
                        ItemsAdderChest(
                            block = block,
                            namespaceId = "test:chest",
                            blockDataProvider = mockBlockDataProvider,
                            furnitureProvider = mockFurnitureProvider,
                        )
                    chest.destroy()

                    // Видимая рамка НЕ должна быть удалена
                    frame.isDead shouldBe false
                }

                it("should cleanup display entities nearby") {
                    val world = server.addSimpleWorld("test")
                    val block = world.getBlockAt(0, 64, 0)

                    // Спавним ItemDisplay entity
                    val display =
                        world.spawn(block.location.toCenterLocation(), org.bukkit.entity.ItemDisplay::class.java)

                    val mockBlockDataProvider =
                        mockk<BlockDataProvider>(relaxed = true) {
                            every { getMarker(block, any()) } returns ItemsAdderChest.MARKER_VALUE
                        }
                    val mockFurnitureProvider = mockk<FurnitureProvider>(relaxed = true)

                    val chest =
                        ItemsAdderChest(
                            block = block,
                            namespaceId = "test:chest",
                            blockDataProvider = mockBlockDataProvider,
                            furnitureProvider = mockFurnitureProvider,
                        )
                    chest.destroy()

                    // Display entity должна быть удалена
                    display.isDead shouldBe true
                }
            }
        }

        describe("ChestFactory") {

            describe("create") {

                it("should create VanillaChest for type 'vanilla'") {
                    val world = server.addSimpleWorld("test")
                    val block = world.getBlockAt(0, 64, 0)

                    val chest = ChestFactory.create(block, "vanilla")

                    chest.shouldNotBeNull()
                    chest.shouldBeInstanceOf<VanillaChest>()
                }

                it("should create VanillaChest for uppercase 'VANILLA'") {
                    val world = server.addSimpleWorld("test")
                    val block = world.getBlockAt(0, 64, 0)

                    val chest = ChestFactory.create(block, "VANILLA")

                    chest.shouldNotBeNull()
                    chest.shouldBeInstanceOf<VanillaChest>()
                }

                it("should create ItemsAdderChest for type 'ia'") {
                    val world = server.addSimpleWorld("test")
                    val block = world.getBlockAt(0, 64, 0)

                    val chest = ChestFactory.create(block, "ia", "test:chest")

                    chest.shouldNotBeNull()
                    chest.shouldBeInstanceOf<ItemsAdderChest>()
                }

                it("should create ItemsAdderChest for type 'itemsadder'") {
                    val world = server.addSimpleWorld("test")
                    val block = world.getBlockAt(0, 64, 0)

                    val chest = ChestFactory.create(block, "itemsadder", "test:chest")

                    chest.shouldNotBeNull()
                    chest.shouldBeInstanceOf<ItemsAdderChest>()
                }

                it("should return null for ItemsAdder without namespaceId") {
                    val world = server.addSimpleWorld("test")
                    val block = world.getBlockAt(0, 64, 0)

                    val chest = ChestFactory.create(block, "ia", null)

                    chest.shouldBeNull()
                }

                it("should return null for unknown type") {
                    val world = server.addSimpleWorld("test")
                    val block = world.getBlockAt(0, 64, 0)

                    val chest = ChestFactory.create(block, "unknown")

                    chest.shouldBeNull()
                }
            }

            describe("vanilla") {

                it("should create VanillaChest") {
                    val world = server.addSimpleWorld("test")
                    val block = world.getBlockAt(0, 64, 0)

                    val chest = ChestFactory.vanilla(block)

                    chest.shouldBeInstanceOf<VanillaChest>()
                    chest.block shouldBe block
                }
            }

            describe("itemsAdder") {

                it("should create ItemsAdderChest with namespaceId") {
                    val world = server.addSimpleWorld("test")
                    val block = world.getBlockAt(0, 64, 0)

                    val chest = ChestFactory.itemsAdder(block, "test:furniture")

                    chest.shouldBeInstanceOf<ItemsAdderChest>()
                    chest.block shouldBe block
                    chest.namespaceId shouldBe "test:furniture"
                }
            }
        }

        describe("CustomChest interface") {

            it("VanillaChest should implement CustomChest") {
                val world = server.addSimpleWorld("test")
                val block = world.getBlockAt(0, 64, 0)

                val chest: CustomChest = VanillaChest(block)
                chest.shouldNotBeNull()
            }

            it("ItemsAdderChest should implement CustomChest") {
                val world = server.addSimpleWorld("test")
                val block = world.getBlockAt(0, 64, 0)

                val chest: CustomChest = ItemsAdderChest(block, "test:chest")
                chest.shouldNotBeNull()
            }
        }
    })
