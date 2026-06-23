package ru.arc.commands.arc.subcommands

import ru.arc.treasurechests.ChestVariant
import ru.arc.treasurechests.TreasureHuntConfig

internal object HuntTypesFormatter {
    fun chestModels(config: TreasureHuntConfig): String =
        config.chestTypes
            .values()
            .map { chestType ->
                when (chestType.type) {
                    ChestVariant.VANILLA -> "vanilla"
                    ChestVariant.ITEMS_ADDER -> chestType.namespaceId ?: "?"
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

    fun locationPoolSizeSuffix(size: Int?): String = size?.takeIf { it > 0 }?.let { " <gray>($it loc)" }.orEmpty()
}
