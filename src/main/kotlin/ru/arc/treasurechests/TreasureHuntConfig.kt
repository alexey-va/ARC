package ru.arc.treasurechests

import net.kyori.adventure.bossbar.BossBar
import ru.arc.common.WeightedRandom
import ru.arc.common.locationpools.LocationPool
import ru.arc.common.locationpools.LocationPoolManager

/**
 * Конфигурация типа охоты за сокровищами.
 * Immutable data class с настройками охоты.
 */
data class TreasureHuntConfig(
    val id: String,
    val locationPoolId: String,
    val chestTypes: WeightedRandom<ChestType>,
    val bossBar: BossBarConfig = BossBarConfig(),
    val announcements: AnnouncementConfig = AnnouncementConfig(),
    val effects: EffectsConfig = EffectsConfig(),
    val timeoutSeconds: Long = 3600L,
) {
    /**
     * Получает LocationPool по ID.
     */
    fun getLocationPool(): LocationPool? = LocationPoolManager.getPool(locationPoolId)

    /**
     * Получает случайный тип сундука на основе весов.
     */
    fun getRandomChestType(): ChestType =
        chestTypes.random()
            ?: throw IllegalStateException("No chest types configured")

    companion object {
        /**
         * Создаёт простую конфигурацию с одним типом сундука.
         */
        fun simple(
            id: String,
            locationPoolId: String,
            chestType: ChestType,
        ): TreasureHuntConfig {
            val chestTypes =
                WeightedRandom<ChestType>().apply {
                    add(chestType, chestType.weight.toDouble())
                }
            return TreasureHuntConfig(
                id = id,
                locationPoolId = locationPoolId,
                chestTypes = chestTypes,
            )
        }
    }
}

/**
 * Настройки босс-бара для охоты.
 */
data class BossBarConfig(
    val visible: Boolean = true,
    val message: String = "Охота за сокровищами! Осталось %left%",
    val color: BossBar.Color = BossBar.Color.RED,
    val overlay: BossBar.Overlay = BossBar.Overlay.PROGRESS,
) {
    companion object {
        fun fromStrings(
            visible: Boolean = true,
            message: String = "Охота за сокровищами! Осталось %left%",
            colorStr: String = "RED",
            overlayStr: String = "PROGRESS",
        ): BossBarConfig {
            val color =
                runCatching { BossBar.Color.valueOf(colorStr.uppercase()) }
                    .getOrDefault(BossBar.Color.RED)
            val overlay =
                runCatching { BossBar.Overlay.valueOf(overlayStr.uppercase()) }
                    .getOrDefault(BossBar.Overlay.PROGRESS)
            return BossBarConfig(visible, message, color, overlay)
        }
    }
}

/**
 * Настройки объявлений для охоты.
 */
data class AnnouncementConfig(
    val announceStart: Boolean = true,
    val announceStartGlobally: Boolean = false,
    val startMessage: String? = null,
    val announceStop: Boolean = true,
    val stopMessage: String? = null,
)

/**
 * Настройки эффектов для охоты.
 */
data class EffectsConfig(
    val launchFireworks: Boolean = true,
)
