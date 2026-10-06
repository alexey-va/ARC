package ru.arc.decorinteraction

import java.time.Duration
import java.util.Locale
import java.util.UUID
import kotlin.random.Random

/** The fixed Survival decor interaction contract formerly defined by Denizen. */
internal enum class DecorInteractionAction(
    val command: String,
    val cooldownNode: String,
    val cooldown: Duration,
) {
    DRINK("drink", "arc.cooldown.interaction.drink", Duration.ofHours(1)),
    FOUNTAIN("fountain", "arc.cooldown.interaction.fountain", Duration.ofHours(3)),
    WELL("well", "arc.cooldown.interaction.well", Duration.ofMinutes(1)),
    MILK("milk", "arc.cooldown.interaction.milk", Duration.ofMinutes(5)),
    TEA("tea", "arc.cooldown.interaction.tea", Duration.ofMinutes(3)),
    FISH("fish", "arc.cooldown.interaction.fish", Duration.ofMinutes(20)),
    HARVEST("harvest", "arc.cooldown.interaction.harvest", Duration.ofMinutes(5)),
    REST("rest", "arc.cooldown.interaction.rest", Duration.ofMinutes(5)),
    ;

    companion object {
        fun parse(value: String): DecorInteractionAction? = entries.firstOrNull { it.command == value.lowercase(Locale.ROOT) }
    }
}

internal enum class BarrelDrink(val id: String, val label: String) {
    BEER("beer", "пиво"),
    WINE("wine", "красное вино"),
    ;

    companion object {
        fun parse(value: String): BarrelDrink? = entries.firstOrNull { it.id == value.lowercase(Locale.ROOT) }
    }
}

internal enum class HarvestCrop(val id: String, val itemId: String, val label: String) {
    CORN("corn", "food_and_produce:corn_cob", "початок кукурузы"),
    RICE("rice", "food_and_produce:rice", "горсть риса"),
    ;

    companion object {
        fun parse(value: String): HarvestCrop? = entries.firstOrNull { it.id == value.lowercase(Locale.ROOT) }
    }
}

internal enum class TeaReward(val itemId: String, val label: String) {
    HOT("food_and_produce:hot_tea", "горячий чай"),
    HONEY_LEMON("food_and_produce:honey_lemon_tea", "медово-лимонный чай"),
    ;

    companion object {
        fun roll(random: Random): TeaReward = entries[random.nextInt(entries.size)]
    }
}

internal enum class FishReward(val itemId: String, val label: String) {
    TUNA("tuna_fish", "тунец"),
    PERCH("perch_fish", "окунь"),
    SARDINE("sardine_fish", "сардина"),
    CARP("carp_fish", "карп"),
    ;

    companion object {
        fun roll(random: Random): FishReward = entries[random.nextInt(entries.size)]
    }
}

internal enum class FishQuality(val suffix: String, val label: String) {
    NORMAL("", "обычного качества"),
    SILVER("_silver_star", "серебряного качества"),
    GOLDEN("_golden_star", "золотого качества"),
    ;

    companion object {
        fun fromRoll(roll: Int): FishQuality {
            require(roll in 1..100) { "Fish quality roll must be from 1 to 100" }
            return when {
                roll <= 5 -> GOLDEN
                roll <= 30 -> SILVER
                else -> NORMAL
            }
        }
    }
}

internal data class FishCatch(val fish: FishReward, val quality: FishQuality) {
    val itemId: String get() = "customfishing:${fish.itemId}${quality.suffix}"
}

internal data class DecorInteractionCooldownKey(
    val playerId: UUID,
    val action: DecorInteractionAction,
)

/** Main-thread-owned fallback cooldowns, scoped by target and interaction. */
internal class DecorInteractionCooldowns {
    private val expiresAtNanos = HashMap<DecorInteractionCooldownKey, Long>()

    fun start(playerId: UUID, action: DecorInteractionAction, nowNanos: Long) {
        expiresAtNanos[DecorInteractionCooldownKey(playerId, action)] = nowNanos + action.cooldown.toNanos()
    }

    fun isActive(playerId: UUID, action: DecorInteractionAction, nowNanos: Long): Boolean {
        return expiresAtNanos[DecorInteractionCooldownKey(playerId, action)]?.let { nowNanos < it } == true
    }

    fun expire(nowNanos: Long) {
        expiresAtNanos.entries.removeIf { nowNanos >= it.value }
    }

    fun clear() = expiresAtNanos.clear()
}

internal fun rollFishCatch(random: Random): FishCatch =
    FishCatch(FishReward.roll(random), FishQuality.fromRoll(random.nextInt(100) + 1))

/** Main-thread-only gate mirroring Denizen's per-target three-second ratelimit. */
internal class DecorInteractionRateLimiter(
    private val windowNanos: Long = Duration.ofSeconds(3).toNanos(),
) {
    private val lastAttempt = HashMap<UUID, Long>()

    init {
        require(windowNanos > 0) { "Rate-limit window must be positive" }
    }

    fun tryAcquire(playerId: UUID, nowNanos: Long): Boolean {
        lastAttempt.entries.removeIf { nowNanos - it.value >= windowNanos }
        val previous = lastAttempt[playerId]
        if (previous != null && nowNanos - previous in 0 until windowNanos) return false
        lastAttempt[playerId] = nowNanos
        return true
    }

    fun clear() = lastAttempt.clear()
}
