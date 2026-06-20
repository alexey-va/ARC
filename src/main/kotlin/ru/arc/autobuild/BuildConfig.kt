package ru.arc.autobuild

import net.kyori.adventure.text.minimessage.tag.Tag
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import org.bukkit.Material
import org.bukkit.Particle
import ru.arc.ARC
import ru.arc.configs.Config
import ru.arc.configs.ConfigManager
import ru.arc.configs.ConfigSection
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
            ConfigManager.ofModule(ARC.instance.dataPath, "auto-build.yml")
        } else {
            ConfigManager.of(Paths.get(System.getProperty("java.io.tmpdir")), "auto-build.yml")
        }

    // ==================== General Settings ====================

    val isDisabled: Boolean get() = config.bool("disable-building", false)
    val cleanupIntervalTicks: Long get() = config.integer("cleanup-interval", 20).toLong()
    val confirmTimeSeconds: Int get() = config.integer("confirm-time", 180)

    // ==================== Construction Settings ====================

    private val construction: ConfigSection get() = config.section("construction")

    val blocksPerTick: Int get() = construction.int("blocks-per-tick", 3)
    val cycleDurationTicks: Long get() = construction.int("cycle-duration-ticks", 10).toLong()
    val playSounds: Boolean get() = construction.boolean("play-sounds", true)
    val showParticles: Boolean get() = construction.boolean("show-particles", false)
    val placeParticle: Particle get() = construction.particle("place-particle", Particle.FLAME)
    val particleCount: Int get() = construction.int("particle-count", 5)
    val particleOffset: Double get() = construction.double("particle-offset", 0.25)

    val npcSkins: Map<String, String> get() = construction.map("npc-skins", defaultNpcSkins)

    val skipMaterials: Set<Material>
        get() =
            construction.materialSet(
                "skip-materials",
                setOf(
                    Material.CHEST,
                    Material.BARREL,
                    Material.TRAPPED_CHEST,
                    Material.PLAYER_HEAD, Material.PLAYER_WALL_HEAD, Material.BEDROCK
            )
        )

    val nonDropMaterials: Set<Material>
        get() =
            construction.materialSet(
                "not-drop-materials",
                setOf(
                    Material.SHORT_GRASS, Material.TALL_GRASS, Material.DIRT, Material.GRASS_BLOCK
            )
        )

    // ==================== Display Settings ====================

    private val display: ConfigSection get() = config.section("display")

    val borderParticleInterval: Long get() = display.int("border-particle-interval", 5).toLong()
    val borderParticle: Particle get() = display.particle("border-particle", Particle.FLAME)
    val borderParticleCount: Int get() = display.int("border-particle-count", 1)
    val borderParticleCornerCount: Int get() = display.int("border-particle-corner-count", 3)
    val borderParticleOffset: Double get() = display.double("border-particle-offset", 0.0)
    val borderParticleCornerOffset: Double get() = display.double("border-particle-corner-offset", 0.07)
    val centerParticle: Particle get() = display.particle("center-particle", Particle.NAUTILUS)
    val centerParticleCount: Int get() = display.int("center-particle-count", 1)
    val maxDisplayBlocks: Int get() = display.int("max-blocks", 30_000)
    val maxDisplaysPer10Min: Int get() = display.int("max-per-10-min", 10)

    // ==================== GUI Settings ====================

    object ConfirmGui {
        val title: String get() = config.string("confirm-gui.title", "<dark_gray>Подтверждение постройки")
    }

    /** Module YAML — use [ru.arc.util.fromConfig] with paths under `building-gui.*` / `confirm-gui.*`. */
    @JvmStatic
    fun config(): Config =
        if (ARC.plugin != null) {
            ConfigManager.ofModule(ARC.instance.dataPath, "auto-build.yml")
        } else {
            ConfigManager.of(Paths.get(System.getProperty("java.io.tmpdir")), "auto-build.yml")
        }

    // ==================== Messages ====================

    object Messages {
        fun disabled() = config.component("disabled-message", "<gray>\uD83D\uDEE0 <red>Постройка здесь отключена!")
        fun notFound() = config.component("building-not-found-message", "<gray>\uD83D\uDEE0 <red>Здание не найдено!")
        fun alreadyBuilding() =
            config.component("already-building-message", "<gray>\uD83D\uDEE0 <red>Вы уже строите одно здание!")

        fun cantBuild() =
            config.component("cant-build-message", "<gray>\uD83D\uDEE0 <red>Вы не можете строить здесь.")

        fun cooldown(ticksRemaining: Long) = config.component(
            "cooldown-message",
            "<gray>\uD83D\uDEE0 <red>Вы не можете строить так часто. Подождите <time>.",
        ) { tag("time", TextUtil.timeComponent(ticksRemaining / 20L, TimeUnit.SECONDS)) }

        fun startOutline() = config.component(
            "start-message",
            "<gray>\uD83D\uDEE0 <green>Нажмите на тот же блок, чтобы подтвердить постройку"
        )

        fun confirm() =
            config.component("confirm-message", "<gray>\uD83D\uDEE0 <green>Подтвердите постройку, нажав ПКМ на NPC")

        fun startBuild() = config.component("start-build-message", "<gray>\uD83D\uDEE0 <green>Постройка начата")
        fun cancelled() = config.component("cancel-build-message", "<gray>\uD83D\uDEE0 <red>Постройка отменена")
        fun finished() =
            config.component("building-finished-message", "<gray>\uD83D\uDEE0 <green>Строительство завершено!")

        fun inactivity() = config.component(
            "inactivity-cancel-message",
            "<gray>\uD83D\uDEE0 Постройка отменена из-за неактивности."
        )

        fun notYourNpc() = config.component("not-your-npc", "<gray>\uD83D\uDEE0 <red>Этот NPC не принадлежит вам!")

        fun noBook() =
            config.component("confirm-gui.no-book", "<gray>\uD83D\uDEE0 <red>У вас нет книги в инвентаре!")

        fun cancelConfirmHint() =
            config.component(
                "building-gui.cancel-hint",
                "<gray>\uD83D\uDEE0 <yellow>Нажмите «Отменить» ещё раз для подтверждения",
            )

        fun displayLimit() = config.component(
            "messages.display-limit",
            "<gray>\uD83D\uDEE0 <red>Слишком много блоков в строении. Показывается лишь часть."
        )

        fun displayRateLimit() = config.component(
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

