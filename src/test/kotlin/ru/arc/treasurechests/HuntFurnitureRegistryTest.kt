package ru.arc.treasurechests

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.mockbukkit.mockbukkit.entity.PlayerMock
import ru.arc.KotestTestBase
import ru.arc.common.chests.FurnitureEntityTracker
import java.nio.file.Files
import java.util.UUID

class HuntFurnitureRegistryTest :
    KotestTestBase({

        lateinit var player: PlayerMock

        beforeTest {
            player = server.addPlayer("RegistryTester")
            HuntFurnitureRegistry.resetForTests()
            HuntFurnitureRegistry.fileOverride = Files.createTempFile("hunt-furniture", ".json")
        }

        afterTest {
            HuntFurnitureRegistry.fileOverride = null
        }

        describe("HuntFurnitureRegistry") {
            it("registers and unregisters entity ids by block") {
                val world = server.addSimpleWorld("registry-world")
                val block = world.getBlockAt(10, 64, 20)
                val entityId = UUID.randomUUID()

                HuntFurnitureRegistry.register(block, listOf(entityId))
                HuntFurnitureRegistry.entityIdsAt(block) shouldContainExactly listOf(entityId)

                val removed = HuntFurnitureRegistry.unregister(block)
                removed shouldContainExactly listOf(entityId)
                HuntFurnitureRegistry.entityIdsAt(block).shouldBe(emptyList())
            }

            it("drainAll returns all anchors and clears registry") {
                val world = server.addSimpleWorld("drain-world")
                val block = world.getBlockAt(1, 64, 2)
                HuntFurnitureRegistry.register(block, listOf(UUID.randomUUID()))

                val drained = HuntFurnitureRegistry.drainAll()
                drained.size shouldBe 1
                HuntFurnitureRegistry.entityIdsAt(block).shouldBe(emptyList())
            }
        }

        describe("FurnitureEntityTracker") {
            it("detectSpawned returns entities present only after snapshot") {
                val id1 = UUID.randomUUID()
                val id2 = UUID.randomUUID()
                val id3 = UUID.randomUUID()

                FurnitureEntityTracker.detectSpawned(setOf(id1, id2), setOf(id1, id2, id3)) shouldContainExactly setOf(id3)
            }
        }
    })
