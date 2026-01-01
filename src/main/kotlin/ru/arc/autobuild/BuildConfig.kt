package ru.arc.autobuild

import net.kyori.adventure.text.minimessage.tag.Tag
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import org.bukkit.Material
import org.bukkit.Particle
import ru.arc.ARC
import ru.arc.configs.Config
import ru.arc.configs.ConfigManager
import ru.arc.util.TextUtil
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

/**
 * Centralized configuration for the building module.
 * All building-related classes should use this instead of creating their own config instances.
 */
object BuildConfig {

    private val config: Config
        get() = if (ARC.plugin != null) {
            ConfigManager.of(ARC.plugin.dataPath, "auto-build.yml")
        } else {
            ConfigManager.of(Paths.get(System.getProperty("java.io.tmpdir")), "auto-build.yml")
        }

    // ==================== General Settings ====================

    val isDisabled: Boolean get() = config.bool("disable-building", false)
    val cleanupIntervalTicks: Long get() = config.integer("cleanup-interval", 20).toLong()
    val confirmTimeSeconds: Int get() = config.integer("confirm-time", 180)

    // ==================== Construction Settings ====================

    val blocksPerTick: Int get() = config.integer("construction.blocks-per-tick", 3)
    val cycleDurationTicks: Long get() = config.integer("construction.cycle-duration-ticks", 10).toLong()
    val playSounds: Boolean get() = config.bool("construction.play-sounds", true)
    val showParticles: Boolean get() = config.bool("construction.show-particles", false)
    val placeParticle: Particle get() = config.particle("construction.place-particle", Particle.FLAME)
    val particleCount: Int get() = config.integer("construction.particle-count", 5)
    val particleOffset: Double get() = config.real("construction.particle-offset", 0.25)

    val npcSkins: Map<String, String> get() = config.map("construction.npc-skins", defaultNpcSkins)

    val skipMaterials: Set<Material>
        get() = config.materialSet(
            "construction.skip-materials", setOf(
                Material.CHEST, Material.BARREL, Material.TRAPPED_CHEST,
                Material.PLAYER_HEAD, Material.PLAYER_WALL_HEAD, Material.BEDROCK
            )
        )

    val nonDropMaterials: Set<Material>
        get() = config.materialSet(
            "construction.not-drop-materials", setOf(
                Material.SHORT_GRASS, Material.TALL_GRASS, Material.DIRT, Material.GRASS_BLOCK
            )
        )

    // ==================== Display Settings ====================

    val borderParticleInterval: Long get() = config.integer("display.border-particle-interval", 5).toLong()
    val borderParticle: Particle get() = config.particle("display.border-particle", Particle.FLAME)
    val borderParticleCount: Int get() = config.integer("display.border-particle-count", 1)
    val borderParticleCornerCount: Int get() = config.integer("display.border-particle-corner-count", 3)
    val borderParticleOffset: Double get() = config.real("display.border-particle-offset", 0.0)
    val borderParticleCornerOffset: Double get() = config.real("display.border-particle-corner-offset", 0.07)
    val centerParticle: Particle get() = config.particle("display.center-particle", Particle.NAUTILUS)
    val centerParticleCount: Int get() = config.integer("display.center-particle-count", 1)
    val maxDisplayBlocks: Int get() = config.integer("display.max-blocks", 30_000)
    val maxDisplaysPer10Min: Int get() = config.integer("display.max-per-10-min", 10)

    // ==================== GUI Settings ====================

    object ConfirmGui {
        val title: String get() = config.string("confirm-gui.title", "<dark_gray>Подтверждение постройки")
        val confirmMaterial: Material get() = config.material("confirm-gui.confirm-material", Material.PAPER)
        val cancelMaterial: Material
            get() = config.material(
                "confirm-gui.cancel-material",
                Material.RED_STAINED_GLASS_PANE
            )
        val confirmModelData: Int get() = config.integer("confirm-gui.confirm-model-data", 0)
        val cancelModelData: Int get() = config.integer("confirm-gui.cancel-model-data", 0)
    }

    object BuildingGui {
        val confirmMaterial: Material get() = config.material("building-gui.confirm-material", Material.PAPER)
        val cancelMaterial: Material
            get() = config.material(
                "building-gui.cancel-material",
                Material.RED_STAINED_GLASS_PANE
            )
        val fastFinishMaterial: Material
            get() = config.material(
                "building-gui.fast-finish-material",
                Material.BLAZE_POWDER
            )
        val cancelModelData: Int get() = config.integer("building-gui.cancel-model-data", 0)
    }

    // ==================== Messages ====================

    object Messages {
        fun disabled() = config.componentDef("disabled-message", "<gray>\uD83D\uDEE0 <red>Постройка здесь отключена!")
        fun notFound() = config.componentDef("building-not-found-message", "<gray>\uD83D\uDEE0 <red>Здание не найдено!")
        fun alreadyBuilding() =
            config.componentDef("already-building-message", "<gray>\uD83D\uDEE0 <red>Вы уже строите одно здание!")

        fun cantBuild() =
            config.componentDef("cant-build-message", "<gray>\uD83D\uDEE0 <red>Вы не можете строить здесь.")

        fun cooldown(ticksRemaining: Long) = config.componentDef(
            "cooldown-message",
            "<gray>\uD83D\uDEE0 <red>Вы не можете строить так часто. Подождите <time>.",
            TagResolver.builder()
                .tag("time", Tag.inserting(TextUtil.timeComponent(ticksRemaining / 20L, TimeUnit.SECONDS)))
                .build()
        )

        fun startOutline() = config.componentDef(
            "start-message",
            "<gray>\uD83D\uDEE0 <green>Нажмите на тот же блок, чтобы подтвердить постройку"
        )

        fun confirm() =
            config.componentDef("confirm-message", "<gray>\uD83D\uDEE0 <green>Подтвердите постройку, нажав ПКМ на NPC")

        fun startBuild() = config.componentDef("start-build-message", "<gray>\uD83D\uDEE0 <green>Постройка начата")
        fun cancelled() = config.componentDef("cancel-build-message", "<gray>\uD83D\uDEE0 <red>Постройка отменена")
        fun finished() =
            config.componentDef("building-finished-message", "<gray>\uD83D\uDEE0 <green>Строительство завершено!")

        fun inactivity() = config.componentDef(
            "inactivity-cancel-message",
            "<gray>\uD83D\uDEE0 Постройка отменена из-за неактивности."
        )

        fun notYourNpc() = config.componentDef("not-your-npc", "<gray>\uD83D\uDEE0 <red>Этот NPC не принадлежит вам!")
        fun noBook() =
            config.componentDef("confirm-gui.no-book", "<gray>\uD83D\uDEE0 <red>У вас нет книги в инвентаре!")

        fun confirmButton() = config.componentDef("confirm-gui.confirm", "<green>Подтвердить постройку")
        fun cancelButton() = config.componentDef("confirm-gui.cancel", "<red>Отменить постройку")
        fun cancelBuildButton() = config.componentDef("building-gui.cancel-name", "<red>Отменить постройку")
        fun cancelLore() = config.componentListDef(
            "building-gui.cancel-lore", listOf(
                "<gray>Вы уверены, что хотите отменить строительство?",
                "<gray>Вы не вернете книгу"
            )
        )

        fun displayLimit() = config.componentDef(
            "messages.display-limit",
            "<gray>\uD83D\uDEE0 <red>Слишком много блоков в строении. Показывается лишь часть."
        )

        fun displayRateLimit() = config.componentDef(
            "messages.display-limit-2",
            "<gray>\uD83D\uDEE0 <red>Превышен лимит показа блоков. Строение не будет показано"
        )
    }

    // ==================== Defaults ====================

    val defaultNpcSkins = mapOf(
        "&6Петрович" to "https://minesk.in/faca74c68a104b6987bc8c11ffebb092",
        "&6Николаич" to "https://minesk.in/6666ba384aa3486b88c21fa7541fb856",
        "&6Иваныч" to "https://minesk.in/3ff30e8f08ae48c2abece46bbf0c09d6",
        "&6Агадиль" to "https://minesk.in/e8eae58c095949de87ff9c9b5b7c17f2"
    )
}

