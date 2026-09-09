package ru.arc.landsui

import me.angeschossen.lands.api.framework.limit.LimitModifier
import me.angeschossen.lands.api.framework.limit.holder.LimitHolder
import me.angeschossen.lands.api.limits.Limit
import me.angeschossen.lands.api.memberholder.MemberHolder
import me.angeschossen.lands.api.memberholder.HolderType

/** Keeps area capacity proportional to chunks already claimed by a land. */
object RegionAreaLimits {
    const val MODIFIER_ID = "arc.region-area-limits"
    private const val MIN_AREAS = 32
    private const val MAX_AREAS = 2048

    internal fun target(nativeBase: Int, claimedChunks: Int): Int {
        if (nativeBase < 0) return nativeBase
        val scaled = (claimedChunks.toLong().coerceAtLeast(0L) * 2L)
            .coerceAtMost(MAX_AREAS.toLong()).toInt()
        return maxOf(nativeBase, MIN_AREAS, scaled)
    }

    fun install(): AutoCloseable {
        val modifier = object : LimitModifier {
            override fun getId(): String = MODIFIER_ID

            override fun getModifier(holder: LimitHolder): Int {
                if (holder !is MemberHolder || holder.type != HolderType.LAND) return 0
                // The false flag requests the unmodified pack value; calling the
                // normal getLimit() here would re-enter this modifier.
                val nativeBase = holder.getLimit(Limit.LAND_AREAS, false)
                if (nativeBase < 0) return 0
                return target(nativeBase, holder.getChunksAmount()) - nativeBase
            }
        }
        Limit.LAND_AREAS.registerModifier(modifier)
        return AutoCloseable { Limit.LAND_AREAS.unregisterModifier(modifier) }
    }
}
