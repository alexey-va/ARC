package ru.arc.hooks.elitemobs

import com.magmaguy.elitemobs.api.EliteSkillXpGainEvent
import com.magmaguy.elitemobs.playerdata.database.PlayerData
import com.magmaguy.elitemobs.skills.CombatLevelDisplay
import com.magmaguy.elitemobs.skills.SkillType
import com.magmaguy.elitemobs.skills.SkillXPBar
import com.magmaguy.elitemobs.skills.SkillXPCalculator
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import java.util.IdentityHashMap

internal data class SharedSkillTarget(val skill: SkillType, val maxXp: Long = Long.MAX_VALUE)

internal data class SharedSkillSplit(val sourceGain: Long, val targetGain: Long)

/** Only positive increments are allocated; existing skill totals are never redistributed. */
internal fun planSharedSkillXp(
    sourceXp: Long,
    targetXp: Long,
    reward: Long,
    targetMaxXp: Long = Long.MAX_VALUE,
): SharedSkillSplit? {
    if (sourceXp < 0 || targetXp < 0 || targetMaxXp <= targetXp || reward < 2) return null
    val sourceCapacity = Long.MAX_VALUE - sourceXp
    if (sourceCapacity == 0L) return null
    val targetCapacity = targetMaxXp - targetXp
    val sourceRoom = minOf(reward, sourceCapacity)
    val admitted = sourceRoom + minOf(reward - sourceRoom, targetCapacity)
    if (admitted < 2) return null

    val preferredTarget = if (targetXp < sourceXp) {
        val catchUp = minOf(admitted, sourceXp - targetXp)
        catchUp + (admitted - catchUp) / 2
    } else {
        (admitted - minOf(admitted, targetXp - sourceXp)) / 2
    }
    // ponytail: reserve one native XP so a later setXp(0) remains an unambiguous veto.
    // One-XP rewards cannot be shared; exact zero-source splits need a native post-award API.
    val targetGain = preferredTarget.coerceIn(
        maxOf(0L, admitted - sourceCapacity),
        minOf(targetCapacity, admitted - 1),
    )
    if (targetGain == 0L) return null
    return SharedSkillSplit(admitted - targetGain, targetGain)
}

/**
 * Runs entirely inside the synchronous native award event. HIGHEST reserves the native share;
 * MONITOR grants the companion only if the final event still accepts that split. EliteMobs owns
 * both persistence writes and the subsequent source award. Other MONITOR handlers must not mutate
 * the event, as required by Bukkit; this is not an atomic transaction across the two skill columns.
 */
internal class DungeonSharedSkillXp(
    private val recipient: (Player, SkillType) -> SharedSkillTarget?,
    private val ready: (Player) -> Boolean = { true },
    private val awarded: (EliteSkillXpGainEvent, SkillType, Long) -> Unit = { _, _, _ -> },
) : Listener, AutoCloseable {
    private data class PendingShare(val target: SharedSkillTarget, val split: SharedSkillSplit)

    private val pending = IdentityHashMap<EliteSkillXpGainEvent, PendingShare>()
    private var closed = false

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun prepareShare(event: EliteSkillXpGainEvent) {
        if (closed || event.isCancelled || event.skillType == SkillType.ARMOR || event.xp < 2) return
        val player = event.player
        val id = player.uniqueId
        if (!PlayerData.isDataLoaded(id) || !ready(player)) return
        val target = recipient(player, event.skillType) ?: return
        if (target.skill == SkillType.ARMOR || target.skill == event.skillType) return
        val split = planSharedSkillXp(
            PlayerData.getSkillXP(id, event.skillType),
            PlayerData.getSkillXP(id, target.skill),
            event.xp,
            target.maxXp,
        ) ?: return
        pending[event] = PendingShare(target, split)
        event.xp = split.sourceGain
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    fun completeShare(event: EliteSkillXpGainEvent) {
        val plan = pending.remove(event) ?: return
        // A later listener owns its replacement amount; never top it up with a stale share.
        if (event.isCancelled || event.xp != plan.split.sourceGain) return
        val player = event.player
        val id = player.uniqueId
        if (!PlayerData.isDataLoaded(id) || !ready(player)) return
        val sourceXp = PlayerData.getSkillXP(id, event.skillType)
        val targetXp = PlayerData.getSkillXP(id, plan.target.skill)
        if (sourceXp < 0 || plan.split.sourceGain > Long.MAX_VALUE - sourceXp) return
        if (targetXp < 0 || targetXp > plan.target.maxXp || plan.split.targetGain > plan.target.maxXp - targetXp) return

        val newXp = PlayerData.addSkillXP(id, plan.target.skill, plan.split.targetGain)
        val applied = newXp - targetXp
        if (applied <= 0) return
        awarded(event, plan.target.skill, applied)
        SkillXPBar.showXPGain(player, plan.target.skill, targetXp, newXp, applied)
        if (SkillXPCalculator.levelFromTotalXP(newXp) > SkillXPCalculator.levelFromTotalXP(targetXp)) {
            CombatLevelDisplay.updateDisplay(player)
        }
    }

    override fun close() {
        closed = true
        pending.clear()
    }
}
