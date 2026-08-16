package ru.arc.autobuild

import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import ru.arc.KotestTestBase
import java.nio.file.Files

class BuildingManagerSymlinkTest :
    KotestTestBase({
        describe("schematic catalog discovery") {
            it("loads schematics through the production-style root symlink") {
                val configuredRoot = dataPath.resolve("schematics")
                configuredRoot.toFile().deleteRecursively()

                val sharedCatalog = Files.createTempDirectory("arc-shared-schematics-")
                try {
                    Files.writeString(sharedCatalog.resolve("viking.schem"), "test schematic")
                    Files.createSymbolicLink(configuredRoot, sharedCatalog)

                    BuildingManager.init()

                    BuildingManager.getBuilding("viking.schem")
                        .shouldNotBeNull()
                        .fileName shouldBe "viking.schem"
                } finally {
                    BuildingManager.stopAll()
                    Files.deleteIfExists(configuredRoot)
                    sharedCatalog.toFile().deleteRecursively()
                }
            }
        }
    })
