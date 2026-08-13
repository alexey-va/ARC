package ru.arc.mounts

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.nio.file.Files

class MountModuleConfigTest : StringSpec({
    "bundled catalog exposes the full production collection" {
        val config = bundledConfig("catalog")
        val catalog = config.catalog()

        catalog.all shouldHaveSize 30
        catalog.all.groupingBy(MountDefinition::movement).eachCount() shouldBe
            mapOf(MountMovement.WALKING to 12, MountMovement.FLYING to 12, MountMovement.SWIMMING to 6)
        catalog.all.map(MountDefinition::id).toSet().size shouldBe 30
        catalog["blaze"]?.price(1) shouldBe null
        catalog["happy_ghast"]?.movement shouldBe MountMovement.FLYING
        catalog["dolphin"]?.movement shouldBe MountMovement.SWIMMING
        catalog.all.all { it.levels.size == 3 } shouldBe true
        catalog.all.all { it.skins.size >= 2 } shouldBe true
    }

    "maximum level is a fast and intentionally expensive final sprint" {
        val catalog = bundledConfig("progression").catalog()

        catalog.all.filter { it.price(3) != null }.all { it.level(3).handlingMultiplier == 1.28 } shouldBe true
        catalog.all.filter { it.price(3) != null }.all { it.level(3).sprintMultiplier == 1.12 } shouldBe true
        catalog.all.filter { it.rarity == MountRarity.COMMON }.mapNotNull { it.price(3) }.toSet() shouldBe setOf(5_000_000.0)
        catalog.all.filter { it.rarity == MountRarity.LEGENDARY }.mapNotNull { it.price(3) }.toSet() shouldBe setOf(35_000_000.0)
    }

    "zombie forms are deterministic and separated into explicit skins" {
        val zombie = checkNotNull(bundledConfig("zombie").catalog()["zombie"])

        zombie.appearance.baby shouldBe false
        zombie.appearance.equipment shouldBe emptyMap()
        zombie.skins.map(MountSkinDefinition::id) shouldBe listOf("baby", "iron_guard", "diamond_warlord")
        zombie.skin("baby")?.appearance?.baby shouldBe true
        zombie.skin("iron_guard")?.appearance?.equipment?.get(MountEquipmentSlot.CHEST) shouldBe "IRON_CHESTPLATE"
        zombie.skin("diamond_warlord")?.appearance?.equipment?.get(MountEquipmentSlot.MAIN_HAND) shouldBe "NETHERITE_SWORD"
        zombie.skinPermission("baby") shouldBe "arc.mounts.zombie.skin.baby"
    }

    "every catalog appearance, material, entity and particle matches the exact Paper API" {
        MountModule.validatePaperTypes(bundledConfig("paper-types").catalog())
    }

    "bundled module is fail-closed until a runtime mirror enables it" {
        val config = bundledConfig("gates")

        config.enabled shouldBe false
        config.ownershipMigrationComplete shouldBe false
        config.purchasesEnabled shouldBe false
    }
})

private fun bundledConfig(label: String): MountModuleConfig {
    val dataPath = Files.createTempDirectory("arc-mounts-$label-")
    val moduleDir = Files.createDirectories(dataPath.resolve("modules"))
    val resource = checkNotNull(MountModuleConfigTest::class.java.getResourceAsStream("/modules/mounts.yml"))
    resource.use { input -> Files.newOutputStream(moduleDir.resolve("mounts.yml")).use(input::copyTo) }
    return MountModuleConfig.load(dataPath)
}
