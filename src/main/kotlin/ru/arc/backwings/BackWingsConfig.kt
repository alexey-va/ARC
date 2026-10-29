package ru.arc.backwings

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import java.nio.file.Path

internal enum class BackWing(val itemId: String, val cosmeticId: String) {
    DREADKNIGHT("dreadknight:dreadknight_wings", "arc_dreadknight_wings"),
    GALAXY("galaxy_set:galaxy_wings", "arc_galaxy_wings"),
    DECEASE("deceaseset:decease_wings", "arc_decease_wings");

    val permissions: List<String> = listOf(
        "cosmeticscore.user.cosmetics.wear.$cosmeticId",
        "cosmeticscore.user.cosmetics.seeingui.$cosmeticId",
    )

    companion object {
        fun fromItemId(id: String?): BackWing? = entries.firstOrNull { it.itemId == id }
    }
}

internal data class BackWingsSettings(
    val equippedText: Component = message("<#BCE6A4>Крылья надеты"),
    val removedText: Component = message("<#E8DFD2>Крылья сняты"),
    val missingText: Component = message("<#F4BD6A>Крылья сняты: предмета нет в инвентаре"),
    val unavailableText: Component = message("<#F4BD6A>Не удалось сменить крылья. Попробуйте чуть позже"),
) {
    companion object {
        private fun message(text: String): Component = MiniMessage.miniMessage().deserialize(text)
    }
}

internal class BackWingsConfig(private val source: Config) {
    fun snapshot(): BackWingsSettings = BackWingsSettings(
        text("equipped", "<#BCE6A4>Крылья надеты"),
        text("removed", "<#E8DFD2>Крылья сняты"),
        text("missing", "<#F4BD6A>Крылья сняты: предмета нет в инвентаре"),
        text("unavailable", "<#F4BD6A>Не удалось сменить крылья. Попробуйте чуть позже"),
    )

    private fun text(key: String, fallback: String): Component =
        MiniMessage.miniMessage().deserialize(source.string("text.$key", fallback))

    companion object {
        const val RESOURCE = "modules/back-wings.yml"
        fun load(dataPath: Path): BackWingsConfig = BackWingsConfig(
            ConfigManager.of(dataPath, RESOURCE).also { it.mergeMissingFromBundled(RESOURCE) },
        )
    }
}
