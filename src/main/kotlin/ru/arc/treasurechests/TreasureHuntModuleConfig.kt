package ru.arc.treasurechests

import org.bukkit.Particle
import ru.arc.common.WeightedRandom
import ru.arc.common.locationpools.LocationPoolManager
import ru.arc.configs.Config
import ru.arc.configs.ConfigManager
import ru.arc.configs.ConfigSection
import ru.arc.util.Logging.warn
import java.nio.file.Path

/**
 * Configuration for the treasure hunt module.
 *
 * Uses lazy getters to support automatic reload.
 * Hunt types are cached for performance but can be reloaded via invalidateCache().
 */
class TreasureHuntModuleConfig(
    private val config: Config,
) {
    val aliases: Map<String, String>
        get() = config.map("aliases")

    val messages: TreasureHuntMessages
        get() = TreasureHuntMessages(config.section("messages"))

    val particles: ParticleSettings
        get() = ParticleSettings(config)

    /** Cached hunt types for performance */
    @Volatile
    private var cachedHuntTypes: Map<String, TreasureHuntConfig>? = null

    /**
     * Gets all hunt types (cached).
     * Call invalidateCache() to force reload.
     */
    val huntTypes: Map<String, TreasureHuntConfig>
        get() = cachedHuntTypes ?: loadHuntTypes().also { cachedHuntTypes = it }

    /**
     * Invalidates the hunt types cache, forcing reload on next access.
     */
    fun invalidateCache() {
        cachedHuntTypes = null
    }

    /**
     * Loads all hunt types from config.
     * Note: Use huntTypes property for cached access.
     */
    fun loadHuntTypes(): Map<String, TreasureHuntConfig> {
        val types = mutableMapOf<String, TreasureHuntConfig>()

        for (key in config.keys("treasure-hunt-types")) {
            try {
                types[key] = loadHuntType(key)
            } catch (e: Exception) {
                ru.arc.util.Logging
                    .error("Error loading treasure hunt type: $key", e)
            }
        }

        return types
    }

    private fun loadHuntType(key: String): TreasureHuntConfig {
        val hunt = config.section("treasure-hunt-types.$key")

        val locationPoolId = hunt.string("location-pool-id", "none")

        // Проверяем существование пула
        if (LocationPoolManager.getPool(locationPoolId) == null) {
            warn("Location pool not found for hunt type $key: $locationPoolId")
        }

        // Загружаем типы сундуков
        val chestTypes = WeightedRandom<ChestType>()
        val chestTypeKeys = hunt.keys("chest-types")

        for (ctKey in chestTypeKeys) {
            val ct = hunt.section("chest-types.$ctKey")

            val variant = ChestVariant.fromString(ct.string("type", "VANILLA"))
            val namespaceId = ct.string("ia-namespace-id", "")
            val treasurePoolId = ct.string("treasure-pool-id", "")
            val particlePath = ct.string("particle-path", "default")
            val weight = ct.int("weight", 1)

            val chestType =
                ChestType(
                    type = variant,
                    treasurePoolId = treasurePoolId,
                    particlePath = particlePath,
                    namespaceId = namespaceId.takeIf { it.isNotBlank() },
                    weight = weight,
                )
            chestTypes.add(chestType, weight.toDouble())
        }

        // Загружаем настройки босс-бара
        val bossBarConfig =
            BossBarConfig.fromStrings(
                visible = hunt.boolean("boss-bar-visible", true),
                message = hunt.string("boss-bar-message", ""),
                colorStr = hunt.string("boss-bar-color", "WHITE"),
                overlayStr = hunt.string("boss-bar-overlay", "PROGRESS"),
            )

        // Загружаем настройки объявлений
        val announcementConfig =
            AnnouncementConfig(
                announceStart = hunt.boolean("announce-start", true),
                announceStartGlobally = hunt.boolean("announce-start-globally", false),
                startMessage = hunt.string("start-message", "").takeIf { it.isNotBlank() },
                announceStop = hunt.boolean("announce-stop", true),
                stopMessage = hunt.string("stop-message", "").takeIf { it.isNotBlank() },
            )

        // Загружаем настройки эффектов
        val effectsConfig =
            EffectsConfig(
                launchFireworks = hunt.boolean("launch-fireworks", true),
            )

        return TreasureHuntConfig(
            id = key,
            locationPoolId = locationPoolId,
            chestTypes = chestTypes,
            bossBar = bossBarConfig,
            announcements = announcementConfig,
            effects = effectsConfig,
            timeoutSeconds = hunt.long("seconds-ttl", 3600),
        )
    }

    companion object {
        fun load(dataPath: Path): TreasureHuntModuleConfig {
            val config = ConfigManager.ofModule(dataPath, "treasure-hunt.yml")
            return TreasureHuntModuleConfig(config)
        }
    }
}

/**
 * Messages configuration for treasure hunts.
 * Uses [ConfigSection] scoped to the `messages:` subtree.
 */
class TreasureHuntMessages(
    private val m: ConfigSection,
) {
    val defaultStartMessage: String
        get() = m.string("default-start-message", "<gold>Охота за сокровищами началась!")

    val defaultStopMessage: String
        get() = m.string("default-stop-message", "<gold>Охота за сокровищами завершена!")

    val defaultBossBarMessage: String
        get() = m.string("default-bossbar-message", "Охота за сокровищами! Осталось %left%")
}

/**
 * Particle settings for treasure hunt display.
 * Uses lazy getters for automatic reload support.
 * Inner sections (`idle.*`, `claimed.*`) are accessed via [ConfigSection].
 */
class ParticleSettings(
    private val config: Config,
) {
    val idleTicks: Long
        get() = config.section("idle").int("ticks", 5).toLong()

    val playerSoundEach: Int
        get() = config.section("idle").int("player-sound-each", 1)

    private val defaultIdleConfig: ChestParticleConfig
        get() = loadParticleConfig("idle.default", DEFAULT_IDLE)

    private val defaultClaimedConfig: ChestParticleConfig
        get() = loadParticleConfig("claimed.default", DEFAULT_CLAIMED)

    fun getIdleConfig(particlePath: String): ChestParticleConfig {
        if (particlePath == "default") return defaultIdleConfig
        return loadParticleConfig("idle.$particlePath", defaultIdleConfig)
    }

    fun getClaimedConfig(particlePath: String): ChestParticleConfig {
        if (particlePath == "default") return defaultClaimedConfig
        return loadParticleConfig("claimed.$particlePath", defaultClaimedConfig)
    }

    private fun loadParticleConfig(
        prefix: String,
        default: ChestParticleConfig,
    ): ChestParticleConfig {
        val sec = config.section(prefix)
        return ChestParticleConfig(
            particle = sec.particle("particle", default.particle),
            count = sec.int("count", default.count),
            offset = sec.double("offset", default.offset),
            extra = sec.double("extra", default.extra),
            radius = sec.int("radius", default.radius),
            soundRadius = sec.int("sound-radius", default.soundRadius),
            sound = sec.string("sound", default.sound),
        )
    }

    companion object {
        private val DEFAULT_IDLE =
            ChestParticleConfig(
                particle = Particle.FLAME,
                count = 5,
                offset = 0.1,
                extra = 0.05,
                radius = 30,
                soundRadius = 30,
                sound = "block_amethyst_cluster_hit",
            )

        private val DEFAULT_CLAIMED =
            ChestParticleConfig(
                particle = Particle.END_ROD,
                count = 15,
                offset = 0.1,
                extra = 0.05,
                radius = 30,
                soundRadius = 30,
                sound = "ENTITY_PLAYER_LEVELUP",
            )
    }
}

/**
 * Particle configuration for a chest type.
 */
data class ChestParticleConfig(
    val particle: Particle = Particle.FLAME,
    val count: Int = 5,
    val offset: Double = 0.1,
    val extra: Double = 0.05,
    val radius: Int = 30,
    val soundRadius: Int = 30,
    val sound: String = "block_amethyst_cluster_hit",
)
