package ru.arc.investigation

import org.bukkit.Material
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.file.Path
import java.time.Duration
import java.util.Locale

data class InvestigationNpcPoint(
    val npcId: Int,
    val x: Double,
    val y: Double,
    val z: Double,
    val radius: Double,
)

class InvestigationConfig(private val config: Config) {
    val enabled: Boolean get() = config.bool("enabled", false)
    val world: String get() = config.string("scene.world", "rc_origin_spawn").trim()
    val feeMinor: Long get() = moneyMinor("challenge.fee", "100.00")
    val rewardMinor: Long get() = moneyMinor("challenge.reward", "300.00")
    val duration: Duration get() = config.duration("challenge.duration", Duration.ofSeconds(150))
    val cooldown: Duration get() = config.duration("challenge.cooldown", Duration.ofHours(20))
    val contractGroup: String get() = config.string("contracts.group", "bank_orders").trim().lowercase(Locale.ROOT)

    fun point(role: String): InvestigationNpcPoint {
        val root = "scene.npcs.$role"
        return InvestigationNpcPoint(
            npcId = config.integer("$root.id", 0),
            x = config.double("$root.x", 0.0),
            y = config.double("$root.y", 0.0),
            z = config.double("$root.z", 0.0),
            radius = config.double("$root.radius", 4.5),
        )
    }

    fun validated(): InvestigationConfig {
        if (!enabled) return this
        require(WORLD_PATTERN.matches(world)) { "Invalid investigation world" }
        require(feeMinor in 1L..MAX_MONEY_MINOR && rewardMinor in (feeMinor + 1L)..MAX_MONEY_MINOR) {
            "Investigation fee and reward must be positive, bounded, and reward must exceed fee"
        }
        require(duration in Duration.ofSeconds(30)..Duration.ofMinutes(5)) { "Investigation duration must be 30s..5m" }
        require(cooldown in Duration.ofHours(1)..Duration.ofDays(7)) { "Investigation cooldown must be 1h..7d" }
        require(GROUP_PATTERN.matches(contractGroup)) { "Invalid investigation contract group" }
        NPC_ROLES.map(::point).forEach { point ->
            require(point.npcId > 0) { "Investigation NPC id must be positive" }
            require(listOf(point.x, point.y, point.z, point.radius).all(Double::isFinite)) { "Investigation NPC point must be finite" }
            require(point.radius in 1.0..8.0) { "Investigation NPC radius must be 1..8 blocks" }
        }
        require(NPC_ROLES.map(::point).map(InvestigationNpcPoint::npcId).distinct().size == NPC_ROLES.size) {
            "Investigation NPC ids must be unique"
        }
        return this
    }

    private fun moneyMinor(path: String, fallback: String): Long =
        config.string(path, fallback)
            .trim()
            .let(::BigDecimal)
            .movePointRight(2)
            .setScale(0, RoundingMode.UNNECESSARY)
            .longValueExact()

    companion object {
        private val WORLD_PATTERN = Regex("[a-z0-9_./-]{1,64}")
        private val GROUP_PATTERN = Regex("[a-z0-9][a-z0-9_-]{2,47}")
        private const val MAX_MONEY_MINOR = 100_000_000L
        private val NPC_ROLES = listOf("foma", "stavr", "prokhor", "gordey", "agata", "tikhon")

        fun load(dataPath: Path): InvestigationConfig =
            InvestigationConfig(ConfigManager.of(dataPath, "modules/investigations.yml")).validated()
    }
}

enum class InvestigationGuiRole(val configKey: String, val fallback: Material) {
    DOSSIER("dossier", Material.WRITABLE_BOOK),
    STAVR("stavr", Material.BELL),
    PROKHOR("prokhor", Material.BOOK),
    GORDEY("gordey", Material.SHIELD),
    AGATA("agata", Material.SPYGLASS),
    TIKHON("tikhon", Material.BARREL),
    AMOUNT("amount", Material.GOLD_NUGGET),
    SEAL("seal", Material.HONEYCOMB),
    CARGO("cargo", Material.BUNDLE),
    DUPLICATE("duplicate", Material.WRITTEN_BOOK),
    CLEAN("clean", Material.LIME_DYE),
    START("start", Material.EMERALD),
    CONTRACTS("contracts", Material.CHEST),
}
