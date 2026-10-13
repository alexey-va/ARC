package ru.arc.hooks.elitemobs

import com.magmaguy.elitemobs.config.customtreasurechests.CustomTreasureChestConfigFields

/** Read the native cooldown list without expiring or saving player timers. */
internal object EMChestCooldowns {
    // EliteMobs 10.9.7 removed getRestockTimers(); the underlying list is unchanged.
    private val timersField by lazy {
        CustomTreasureChestConfigFields::class.java.getDeclaredField("restockTimers").apply {
            check(type == List::class.java) { "Unexpected EliteMobs chest timer field type" }
            isAccessible = true
        }
    }

    fun timers(fields: CustomTreasureChestConfigFields): List<String>? {
        val timers = timersField.get(fields) ?: return null
        check(timers is List<*>) { "Unexpected EliteMobs chest timer value type" }
        return timers.filterIsInstance<String>()
    }
}
