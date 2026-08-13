package ru.arc.mounts

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.nio.file.Files

class MountModuleConfigTest : StringSpec({
    "bundled catalog parses all Denizen-compatible mounts" {
        val dataPath = Files.createTempDirectory("arc-mounts-config-")
        val moduleDir = Files.createDirectories(dataPath.resolve("modules"))
        val resource = checkNotNull(MountModuleConfigTest::class.java.getResourceAsStream("/modules/mounts.yml"))
        resource.use { input -> Files.newOutputStream(moduleDir.resolve("mounts.yml")).use(input::copyTo) }

        val config = MountModuleConfig.load(dataPath)
        val catalog = config.catalog()

        catalog.all shouldHaveSize 11
        catalog.all.map(MountDefinition::id) shouldBe
            listOf("zombie", "skeleton", "pig", "enderman", "horse", "dolphin", "bee", "turtle", "frog", "frog2", "blaze")
        catalog["frog2"]?.movement shouldBe MountMovement.FLYING
        catalog["dolphin"]?.movement shouldBe MountMovement.SWIMMING
        catalog["blaze"]?.price(1) shouldBe null
        catalog["zombie"]?.price(3) shouldBe 250_000.0
        catalog["dolphin"]?.speeds shouldBe listOf(0.6, 0.7, 0.8)
        catalog["turtle"]?.prices shouldBe listOf(1_000.0, 20_000.0, 30_000.0)
        catalog["frog"]?.speeds shouldBe listOf(0.9, 2.0, 4.2)
        catalog.all.map(MountDefinition::glowPrice).toSet() shouldBe setOf(10_000.0)
    }

    "bundled module keeps the ownership migration gate closed" {
        val dataPath = Files.createTempDirectory("arc-mounts-migration-gate-")
        val moduleDir = Files.createDirectories(dataPath.resolve("modules"))
        val resource = checkNotNull(MountModuleConfigTest::class.java.getResourceAsStream("/modules/mounts.yml"))
        resource.use { input -> Files.newOutputStream(moduleDir.resolve("mounts.yml")).use(input::copyTo) }

        MountModuleConfig.load(dataPath).ownershipMigrationComplete shouldBe false
    }
})
