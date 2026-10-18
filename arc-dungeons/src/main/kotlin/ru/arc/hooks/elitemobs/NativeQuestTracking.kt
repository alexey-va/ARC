package ru.arc.hooks.elitemobs

import com.magmaguy.elitemobs.quests.QuestTracking
import java.lang.reflect.Modifier
import java.util.UUID

/**
 * EliteMobs 10.9.10 changed this getter's JVM return descriptor from HashMap to
 * ConcurrentHashMap. Bind its public method once, retaining the native map and lifecycle.
 * Native initialization calls [current] before publishing dungeon integration.
 */
internal object NativeQuestTracking {
    private val getter = QuestTracking::class.java.getMethod("getPlayerTrackingQuests").also {
        check(Modifier.isStatic(it.modifiers) && Map::class.java.isAssignableFrom(it.returnType)) {
            "Unsupported EliteMobs quest tracker getter"
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun current(): Map<UUID, QuestTracking> = getter.invoke(null) as Map<UUID, QuestTracking>
}
