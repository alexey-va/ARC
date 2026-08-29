package ru.arc.mounts

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import java.nio.file.Files
import java.time.Duration

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
        catalog["vex"]?.levels?.map(MountLevelDefinition::scaleMultiplier) shouldBe listOf(1.0, 1.0, 1.0)
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

    "mount abilities are explicit and assigned only where they fit" {
        val catalog = bundledConfig("abilities").catalog()
        val goat = checkNotNull(catalog["goat"])
        val frog = checkNotNull(catalog["frog"])

        goat.abilities.highJump?.displayName shouldBe "Высокий прыжок"
        goat.abilities.highJump?.multiplier shouldBe 1.8
        frog.abilities.highJump?.multiplier shouldBe 1.55
        catalog.all.filterNot { it.id in setOf("goat", "frog") }.all { it.abilities.highJump == null } shouldBe true
        catalog.all.filter { it.movement == MountMovement.SWIMMING }.all { mount ->
            mount.ability("water-breathing") != null && mount.ability("night-vision") != null
        } shouldBe true
        catalog["dolphin"]?.ability("dolphins-grace")?.speedMultiplier shouldBe 1.15
        catalog["bat"]?.ability("night-vision")?.effect shouldBe MountAbilityEffect.NIGHT_VISION
        catalog["phantom"]?.ability("night-vision")?.effect shouldBe MountAbilityEffect.NIGHT_VISION
        setOf("strider", "blaze", "ghast", "happy_ghast").all { catalog[it]?.ability("fire-resistance") != null } shouldBe true
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
        config.hideFlyingMountFromRider shouldBe true
        config.hideFlyingMountPitch shouldBe 35.0
        config.showFlyingMountPitch shouldBe 20.0
        config.compensateAirborneMining shouldBe true
        config.motionTiming shouldBe
            MountMotionTiming(
                accelerationTime = Duration.ofMillis(900),
                decelerationTime = Duration.ofMillis(350),
                turnTime = Duration.ofMillis(200),
            )
        config.tuning.speedPercentages shouldBe listOf(50, 65, 80, 90, 100)
        config.tuning.walkingStepHeightsHundredths shouldBe listOf(110, 150, 200, 300, 400)
        config.tuning.walkingMaxStepHeightByLevelHundredths shouldBe listOf(110, 200, 400)
    }

    "bundled GUI remains resource-pack neutral" {
        val config = bundledConfig("generic-gui")

        MountGuiItemRole.entries.forEach { role ->
            config.guiStyle(role) shouldBe MountGuiItemStyle()
        }
    }

    "runtime GUI overlay accepts material and custom model data" {
        val dataPath = Files.createTempDirectory("arc-mounts-runtime-gui-")
        val moduleDir = Files.createDirectories(dataPath.resolve("modules"))
        Files.writeString(
            moduleDir.resolve("mounts.yml"),
            """
            enabled: false
            gui:
              items:
                back:
                  material: BLUE_STAINED_GLASS_PANE
                  customModelData: 11013
                category-all:
                  material: COMPASS
                  customModelData: 11023
                category-flying:
                  material: FEATHER
                  customModelData: 11024
                category-walking:
                  material: SADDLE
                  customModelData: 11025
                category-swimming:
                  material: HEART_OF_THE_SEA
                  customModelData: 11026
            """.trimIndent(),
        )

        val config = MountModuleConfig.load(dataPath)
        config.guiStyle(MountGuiItemRole.BACK) shouldBe
            MountGuiItemStyle(Material.BLUE_STAINED_GLASS_PANE, 11013)
        config.guiStyle(MountGuiItemRole.CATEGORY_ALL) shouldBe
            MountGuiItemStyle(Material.COMPASS, 11023)
        config.guiStyle(MountGuiItemRole.CATEGORY_FLYING) shouldBe
            MountGuiItemStyle(Material.FEATHER, 11024)
        config.guiStyle(MountGuiItemRole.CATEGORY_WALKING) shouldBe
            MountGuiItemStyle(Material.SADDLE, 11025)
        config.guiStyle(MountGuiItemRole.CATEGORY_SWIMMING) shouldBe
            MountGuiItemStyle(Material.HEART_OF_THE_SEA, 11026)
    }

    "level scale is parsed explicitly and defaults to one when omitted" {
        val dataPath = Files.createTempDirectory("arc-mounts-level-scale-")
        val moduleDir = Files.createDirectories(dataPath.resolve("modules"))
        Files.writeString(
            moduleDir.resolve("mounts.yml"),
            """
            enabled: false
            mounts:
              bee:
                type: flying
                entity: BEE
                item: BEE_SPAWN_EGG
                name: Bee
                acquisition: Test
                levels:
                  - {speed: 1.0, scale: 0.8, price: 1}
                  - {speed: 1.2, price: 2}
            """.trimIndent(),
        )

        val levels = checkNotNull(MountModuleConfig.load(dataPath).catalog()["bee"]).levels
        levels.map(MountLevelDefinition::scaleMultiplier) shouldBe listOf(0.8, 1.0)
    }

    "per-mount motion timing overrides inherit unspecified global values" {
        val dataPath = Files.createTempDirectory("arc-mounts-motion-override-")
        val moduleDir = Files.createDirectories(dataPath.resolve("modules"))
        Files.writeString(
            moduleDir.resolve("mounts.yml"),
            """
            enabled: false
            movement:
              acceleration-time: 900ms
              deceleration-time: 350ms
              turn-time: 200ms
            mounts:
              bee:
                type: flying
                entity: BEE
                item: BEE_SPAWN_EGG
                name: Bee
                acquisition: Test
                motion:
                  acceleration-time: 0s
                  turn-time: 400ms
                levels:
                  - {speed: 1.0, price: 1}
            """.trimIndent(),
        )

        val config = MountModuleConfig.load(dataPath)
        val timing = checkNotNull(config.catalog()["bee"]).motion.resolve(config.motionTiming)

        timing shouldBe
            MountMotionTiming(
                accelerationTime = Duration.ZERO,
                decelerationTime = Duration.ofMillis(350),
                turnTime = Duration.ofMillis(400),
            )
    }
})

private fun bundledConfig(label: String): MountModuleConfig {
    val dataPath = Files.createTempDirectory("arc-mounts-$label-")
    val moduleDir = Files.createDirectories(dataPath.resolve("modules"))
    val resource = checkNotNull(MountModuleConfigTest::class.java.getResourceAsStream("/modules/mounts.yml"))
    resource.use { input -> Files.newOutputStream(moduleDir.resolve("mounts.yml")).use(input::copyTo) }
    return MountModuleConfig.load(dataPath)
}
