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
        val config = bundledConfig("progression")
        val catalog = config.catalog()
        val purchasable = catalog.all.filter { it.price(3) != null }

        purchasable.all { it.level(3).handlingMultiplier == 1.28 } shouldBe true
        purchasable.all { it.level(3).sprintMultiplier == 1.12 } shouldBe true
        purchasable.all { mount -> mount.level(3).speed >= mount.level(2).speed * 1.85 } shouldBe true
        purchasable.all { mount -> checkNotNull(mount.price(3)) >= checkNotNull(mount.price(2)) * 7.0 } shouldBe true
        catalog.all.all { mount ->
            val scale =
                when (mount.movement) {
                    MountMovement.WALKING -> config.walkingSpeedScale
                    MountMovement.FLYING -> config.flyingSpeedScale
                    MountMovement.SWIMMING -> config.swimmingSpeedScale
                }
            val final = mount.level(3)
            val blocksPerTick =
                (final.speed * scale * config.sprintMultiplier * final.sprintMultiplier)
                    .coerceAtMost(config.maximumSpeedBlocksPerTick)
            blocksPerTick * 20.0 >= 19.0
        } shouldBe true
        catalog.all.filter { it.rarity == MountRarity.COMMON }.mapNotNull { it.price(3) }.toSet() shouldBe setOf(5_000_000.0)
        catalog.all.filter { it.rarity == MountRarity.UNCOMMON }.mapNotNull { it.price(3) }.toSet() shouldBe setOf(8_000_000.0)
        catalog.all.filter { it.rarity == MountRarity.RARE }.mapNotNull { it.price(3) }.toSet() shouldBe setOf(12_000_000.0)
        catalog.all.filter { it.rarity == MountRarity.EPIC }.mapNotNull { it.price(3) }.toSet() shouldBe setOf(20_000_000.0)
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

    "mount abilities are explicit and the mountain goat has a high jump" {
        val catalog = bundledConfig("abilities").catalog()
        val goat = checkNotNull(catalog["goat"])

        goat.abilities.highJump?.displayName shouldBe "Высокий прыжок"
        goat.abilities.highJump?.multiplier shouldBe 1.8
        catalog.all.filterNot { it.id == "goat" }.all { it.abilities.highJump == null } shouldBe true
    }

    "every catalog appearance, material, entity and particle matches the exact Paper API" {
        MountModule.validatePaperTypes(bundledConfig("paper-types").catalog())
    }

    "bundled module is fail-closed until a runtime mirror enables it" {
        val config = bundledConfig("gates")

        config.enabled shouldBe false
        config.ownershipMigrationComplete shouldBe false
        config.purchasesEnabled shouldBe false
        config.riderKnockoffDamage shouldBe 6.0
    }
})

private fun bundledConfig(label: String): MountModuleConfig {
    val dataPath = Files.createTempDirectory("arc-mounts-$label-")
    val moduleDir = Files.createDirectories(dataPath.resolve("modules"))
    val resource = checkNotNull(MountModuleConfigTest::class.java.getResourceAsStream("/modules/mounts.yml"))
    resource.use { input -> Files.newOutputStream(moduleDir.resolve("mounts.yml")).use(input::copyTo) }
    return MountModuleConfig.load(dataPath)
}
