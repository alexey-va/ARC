package ru.arc.mounts

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import ru.arc.config.ConfigManager
import java.nio.file.Files
import java.time.Duration

class MountModuleConfigTest : StringSpec({
    "bundled catalog exposes the full production collection" {
        val config = bundledConfig("catalog")
        val catalog = config.catalog()

        catalog.all shouldHaveSize 50
        catalog.all.groupingBy(MountDefinition::movement).eachCount() shouldBe
            mapOf(MountMovement.WALKING to 30, MountMovement.FLYING to 12, MountMovement.SWIMMING to 8)
        catalog.all.map(MountDefinition::id).toSet().size shouldBe 50
        setOf(
            "cat",
            "wolf",
            "armadillo",
            "llama",
            "ocelot",
            "panda",
            "pufferfish",
            "magma_cube",
            "mooshroom",
            "tadpole",
            "polar_bear",
            "copper_golem",
            "iron_golem",
            "trader_llama",
            "snow_golem",
            "villager",
            "wandering_trader",
            "skeleton_horse",
        ).all { catalog[it] != null } shouldBe true
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
        val horse = checkNotNull(catalog["horse"])
        val fox = checkNotNull(catalog["fox"])

        goat.abilities.highJump?.displayName shouldBe "Высокий прыжок"
        goat.abilities.highJump?.multiplier shouldBe 1.8
        frog.abilities.highJump?.multiplier shouldBe 1.55
        horse.abilities.highJump?.multiplier shouldBe 1.25
        fox.abilities.highJump?.displayName shouldBe "Лисий прыжок"
        fox.abilities.highJump?.multiplier shouldBe 1.35
        catalog.all.filterNot {
            it.id in setOf("goat", "frog", "horse", "fox", "pocket_chicken", "magma_cube", "skeleton_horse")
        }
            .all { it.abilities.highJump == null } shouldBe true
        catalog.all.filter { it.movement == MountMovement.SWIMMING }.all { mount ->
            (mount.ability("water-breathing") != null || mount.abilities.passives.any { it.effect == MountAbilityEffect.WATER_BREATHING }) &&
                mount.ability("night-vision") != null
        } shouldBe true
        catalog["dolphin"]?.ability("dolphins-grace")?.speedMultiplier shouldBe 1.15
        setOf("skeleton", "enderman", "bat", "phantom").all {
            catalog[it]?.ability("night-vision")?.effect == MountAbilityEffect.NIGHT_VISION
        } shouldBe true
        setOf("strider", "blaze", "ghast", "happy_ghast").all { catalog[it]?.ability("fire-resistance") != null } shouldBe true
        checkNotNull(catalog["pig"]).abilities.passives.single().effect shouldBe MountAbilityEffect.SPEED
        checkNotNull(catalog["bee"]).abilities.passives.single().effect shouldBe MountAbilityEffect.REGENERATION
        checkNotNull(catalog["ghast"]).abilities.passives.single().effect shouldBe MountAbilityEffect.FIRE_RESISTANCE
        checkNotNull(catalog["magma_cube"]).abilities.passives.single().effect shouldBe MountAbilityEffect.FIRE_RESISTANCE
        checkNotNull(catalog["mega_warden"]).abilities.passives.map(MountPassiveAbilityDefinition::effect) shouldBe
            listOf(MountAbilityEffect.NIGHT_VISION, MountAbilityEffect.STRENGTH, MountAbilityEffect.RESISTANCE)
        catalog.all.all { it.abilities.displayNames.isNotEmpty() || it.behaviors.isNotEmpty() } shouldBe true
    }

    "authored sizes and motion make representative mounts feel distinct" {
        val config = bundledConfig("mount-personality")
        val catalog = config.catalog()
        val defaults = config.motionTiming

        checkNotNull(catalog["horse"]).sizeOptions.map(MountSizeOptionDefinition::multiplier) shouldBe listOf(0.1, 1.0, 2.0, 3.0, 10.0)
        checkNotNull(catalog["bee"]).sizeOptions.map(MountSizeOptionDefinition::multiplier) shouldBe listOf(0.1, 0.5, 1.0, 3.0, 10.0)
        checkNotNull(catalog["fox"]).motion.resolve(defaults) shouldBe
            MountMotionTiming(Duration.ofMillis(550), Duration.ofMillis(250), Duration.ofMillis(130))
        checkNotNull(catalog["camel"]).motion.resolve(defaults) shouldBe
            MountMotionTiming(Duration.ofMillis(1_200), Duration.ofMillis(550), Duration.ofMillis(320))
        checkNotNull(catalog["ravager"]).motion.resolve(defaults) shouldBe
            MountMotionTiming(Duration.ofMillis(1_300), Duration.ofMillis(550), Duration.ofMillis(320))
        checkNotNull(catalog["breeze"]).motion.resolve(defaults) shouldBe
            MountMotionTiming(Duration.ofMillis(350), Duration.ofMillis(250), Duration.ofMillis(120))
    }

    "ravager has authored size tuning, a guarded ram and visible localized trails" {
        val catalog = bundledConfig("ravager-quality").catalog()
        val ravager = checkNotNull(catalog["ravager"])

        ravager.sizeOptions.map(MountSizeOptionDefinition::id) shouldBe listOf("keychain", "standard", "huge", "absurd", "colossal")
        ravager.sizeOptions.map(MountSizeOptionDefinition::multiplier) shouldBe listOf(0.1, 1.0, 2.0, 3.0, 10.0)
        ravager.sizeOptions.filter(MountSizeOptionDefinition::grantOnly).map(MountSizeOptionDefinition::id) shouldBe
            listOf("keychain", "colossal")
        val ram = ravager.behaviors.filterIsInstance<MountRamBehavior>().single()
        ram.displayName shouldBe "Таран"
        ram.description shouldBe
            listOf(
                "Нажмите спринт при движении вперёд.",
                "Первый враждебный моб на пути",
                "получит урон при столкновении.",
            )
        ram.damage shouldBe 4.0
        ram.cooldown shouldBe Duration.ofSeconds(4)
        val trample = ravager.behaviors.filterIsInstance<MountTrampleBehavior>().single()
        trample.displayName shouldBe "Топот"
        trample.damage shouldBe 2.0
        trample.cooldown shouldBe Duration.ofSeconds(1)
        trample.maximumTargets shouldBe 4
        ravager.skin("starlight")?.trail?.displayName shouldBe "Звёздный след"
        ravager.skin("starlight")?.trail?.count shouldBe 3
        ravager.skin("shadow")?.trail?.displayName shouldBe "След душ"
        ravager.skin("shadow")?.trail?.count shouldBe 4
    }

    "absurd catalog mounts retain their authored effective scale" {
        val catalog = bundledConfig("absurd-scales").catalog()

        catalog["mega_pig"] shouldBe null
        catalog["colossal_bee"] shouldBe null
        catalog["pocket_ghast"] shouldBe null
        checkNotNull(catalog["pig"]).sizeOptions.map(MountSizeOptionDefinition::multiplier) shouldBe
            listOf(0.1, 0.5, 1.0, 3.0, 10.0)
        checkNotNull(catalog["ghast"]).sizeOptions.map(MountSizeOptionDefinition::multiplier) shouldBe
            listOf(0.1, 0.5, 1.0, 3.0, 10.0)
        checkNotNull(catalog["mega_warden"]).sizeOptions.map(MountSizeOptionDefinition::multiplier) shouldBe
            listOf(0.1, 0.5, 1.0, 3.0, 10.0)
        checkNotNull(catalog["pocket_chicken"]).sizeOptions.map(MountSizeOptionDefinition::multiplier) shouldBe
            listOf(0.1, 0.5, 1.0, 3.0, 10.0)
    }

    "every mount receives command-only extreme sizes inside the native scale envelope" {
        val catalog = bundledConfig("extreme-sizes").catalog()

        catalog.all.all { mount ->
            val extremes = mount.sizeOptions.filter(MountSizeOptionDefinition::grantOnly)
            extremes.size == 2 &&
                extremes.all { it.minimumLevel == 1 } &&
                extremes.first().multiplier == 0.1 &&
                extremes.last().multiplier >= 7.6 &&
                extremes.last().multiplier <= 10.0 &&
                mount.defaultSizeOption?.grantOnly == false
        } shouldBe true
        checkNotNull(catalog["bat"]).sizeOptions.last().multiplier shouldBe 7.6
        checkNotNull(catalog["bee"]).sizeOptions.last().multiplier shouldBe 10.0
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
        config.quickSummonSneakSwapHands shouldBe true
        config.quickSummonWhistle shouldBe true
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

    "existing runtime config merges new bundled mount features without replacing server gates" {
        val dataPath = Files.createTempDirectory("arc-mounts-merge-forward-")
        val moduleDir = Files.createDirectories(dataPath.resolve("modules"))
        val file = moduleDir.resolve("mounts.yml")
        Files.writeString(
            file,
            """
            enabled: true
            ownership-migration-complete: true
            purchases-enabled: true
            allowed-worlds: [operator_world]
            gui:
              items:
                background: {material: GRAY_STAINED_GLASS_PANE, customModelData: 11000}
            mounts:
              operator_mount:
                type: walking
                entity: PIG
                item: PIG_SPAWN_EGG
                name: Operator mount
                acquisition: Operator catalog
                levels:
                  - {speed: 1.0, price: 1}
            """.trimIndent() + "\n",
        )

        val config = MountModuleConfig.load(dataPath)

        config.enabled shouldBe true
        config.purchasesEnabled shouldBe true
        config.allowedWorlds shouldBe setOf("operator_world")
        config.guiStyle(MountGuiItemRole.BACKGROUND) shouldBe
            MountGuiItemStyle(Material.GRAY_STAINED_GLASS_PANE, 11000)
        config.quickSummonWhistle shouldBe true
        config.guiText("common.footer-open", "") shouldBe
            "<#8c8c8c>[<#92bed8>▶<#8c8c8c>] <#92bed8>ЛКМ<#e6fff3> — открыть"
        config.catalog().all.map(MountDefinition::id).toSet() shouldBe
            bundledConfig("merge-forward-catalog").catalog().all.map(MountDefinition::id).toSet() + "operator_mount"
        checkNotNull(config.catalog()["ravager"]).behaviors shouldBe
            checkNotNull(bundledConfig("merge-forward-ravager").catalog()["ravager"]).behaviors

        val merged = Files.readString(file)
        ConfigManager.ofModule(dataPath, "mounts.yml")
            .mergeMissingFromBundled("modules/mounts.yml") shouldBe false
        Files.readString(file) shouldBe merged
    }

    "bundled GUI remains resource-pack neutral" {
        val config = bundledConfig("generic-gui")

        MountGuiItemRole.entries.forEach { role ->
            config.guiStyle(role) shouldBe MountGuiItemStyle()
        }
    }

    "bundled GUI copy owns the dark titles and thirteen-row collection guide" {
        val config = bundledConfig("gui-copy")

        config.listTitle shouldBe "<color:#20252b><bold>Коллекция маунтов</bold>"
        config.detailTitle shouldBe "<color:#20252b><bold><mount></bold>"
        config.progressionTitle shouldBe "<color:#20252b><bold>Развитие маунта</bold>"
        config.skinsTitle shouldBe "<color:#20252b><bold>Облики маунта</bold>"
        config.confirmTitle shouldBe "<color:#20252b><bold>Покупка маунта</bold>"
        config.guiText("list.guide-name", "") shouldBe "<color:#92bed8>Путеводитель по коллекции"
        // Display name plus twelve lore rows is the intentionally retained thirteen-row tooltip.
        config.guiLines("list.guide-lore", emptyList()) shouldHaveSize 12
        config.guiText("common.action-footer", "").contains("▶") shouldBe true
        config.guiText("list.mount-acquirable-name", "") shouldBe "<color:#92bed8><mount>"
        config.guiText("detail.whistle-disabled-name", "") shouldBe "<color:#969696>Свисток недоступен"
        config.guiText("progression.mount-required", "") shouldBe "<color:#c42323>Сначала получите маунта"
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
              test_bee:
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

        val levels = checkNotNull(MountModuleConfig.load(dataPath).catalog()["test_bee"]).levels
        levels.map(MountLevelDefinition::scaleMultiplier) shouldBe listOf(0.8, 1.0)
    }

    "size tuning rejects malformed level gates and unknown fields" {
        listOf(
            "{id: standard, name: Standard, multiplier: 1.0, minimum-level: nope}",
            "{id: standard, name: Standard, multiplier: 1.0, minimum-leevl: 1}",
        ).forEachIndexed { index, option ->
            val dataPath = Files.createTempDirectory("arc-mounts-size-invalid-$index-")
            val moduleDir = Files.createDirectories(dataPath.resolve("modules"))
            Files.writeString(
                moduleDir.resolve("mounts.yml"),
                """
                enabled: false
                mounts:
                  test_bee:
                    type: flying
                    entity: BEE
                    item: BEE_SPAWN_EGG
                    name: Bee
                    acquisition: Test
                    levels:
                      - {speed: 1.0, price: 1}
                    size-tuning:
                      - $option
                """.trimIndent(),
            )

            shouldThrow<IllegalArgumentException> { MountModuleConfig.load(dataPath).catalog() }
        }
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
              test_bee:
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
        val timing = checkNotNull(config.catalog()["test_bee"]).motion.resolve(config.motionTiming)

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
