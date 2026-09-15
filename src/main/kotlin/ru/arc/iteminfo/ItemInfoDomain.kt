package ru.arc.iteminfo

import net.kyori.adventure.text.Component
import org.bukkit.Location
import ru.arc.onboarding.claimGuideLabelLocation
import java.util.Locale

enum class ItemInfoMode(val id: String) {
    HOLOGRAM("hologram"),
    BOSSBAR("bossbar"),
    OFF("off");

    companion object {
        const val META_KEY = "arc-item-info-mode"

        fun fromStored(raw: String?): ItemInfoMode =
            entries.firstOrNull { it.id == raw?.trim()?.lowercase(Locale.ROOT) } ?: HOLOGRAM
    }
}

data class ItemInfoTarget(
    val name: Component,
    val namespacedId: String,
)

internal fun itemInfoHologramLocation(eye: Location): Location = claimGuideLabelLocation(eye)
