package ru.arc.hooks.elitemobs

import ru.arc.ARC
import ru.arc.config.Config
import ru.arc.config.ConfigManager

internal class DungeonClassLocalization(
    private val config: Config = ConfigManager.of(ARC.instance.dataPath, "modules/elitemobs.yml"),
) {
    fun formName(formId: String, fallback: String): String =
        config.string("dungeon-qol.classes.catalog.$formId.name", fallback)

    fun ability(formId: String, slot: String, fallbackName: String, fallbackDescription: String): DungeonClassAbility =
        DungeonClassAbility(
            config.string("dungeon-qol.classes.catalog.$formId.$slot.name", fallbackName),
            config.string("dungeon-qol.classes.catalog.$formId.$slot.description", fallbackDescription),
        )

    fun passive(formId: String, fallbackSource: String, fallbackDescription: String): DungeonClassPassive =
        DungeonClassPassive(
            formName(formId, fallbackSource),
            config.string("dungeon-qol.classes.catalog.$formId.passive", fallbackDescription),
        )
}
