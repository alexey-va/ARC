package ru.arc.commands.arc.subcommands

import ru.arc.common.WeightedRandom
import ru.arc.treasurechests.ChestVariant
import ru.arc.treasurechests.ChestType
import ru.arc.treasurechests.TreasureHuntConfig

internal object HuntTypesFormatter {
    fun chestModels(
        config: TreasureHuntConfig,
        aliases: Map<String, String> = emptyMap(),
    ): String =
        config.chestTypes
            .values()
            .map { chestType ->
                when (chestType.type) {
                    ChestVariant.VANILLA -> "vanilla"
                    ChestVariant.ITEMS_ADDER ->
                        chestType.namespaceId?.let { namespaceId ->
                            aliases.entries.firstOrNull { it.value == namespaceId }?.key ?: namespaceId
                        } ?: "?"
                }
            }.distinct()
            .sorted()
            .joinToString(", ")

    fun treasurePools(config: TreasureHuntConfig): String =
        config.chestTypes
            .values()
            .map { it.treasurePoolId }
            .distinct()
            .sorted()
            .joinToString(", ")

    fun withOverrides(
        config: TreasureHuntConfig,
        locationPoolId: String,
        chestTypeOverride: ChestType?,
        treasurePoolIdOverride: String?,
    ): TreasureHuntConfig {
        if (chestTypeOverride == null && treasurePoolIdOverride == null && locationPoolId == config.locationPoolId) {
            return config
        }

        val chestTypes = WeightedRandom<ChestType>()
        config.chestTypes.entries().forEach { entry ->
            val source = entry.value
            val withAppearance =
                chestTypeOverride?.let {
                    source.copy(type = it.type, namespaceId = it.namespaceId)
                } ?: source
            val withReward = treasurePoolIdOverride?.let { withAppearance.copy(treasurePoolId = it) } ?: withAppearance
            chestTypes.add(withReward, entry.weight)
        }
        return config.copy(locationPoolId = locationPoolId, chestTypes = chestTypes)
    }

    fun locationPoolSizeSuffix(size: Int?): String = size?.takeIf { it > 0 }?.let { " <gray>($it точек)" }.orEmpty()
}
