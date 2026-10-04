package ru.arc.sidebar

import com.willfp.ecojobs.api.activeJobs
import com.willfp.ecojobs.api.getJobLevel
import com.willfp.ecojobs.api.getJobProgress
import com.willfp.ecojobs.jobs.Job
import org.bukkit.Bukkit
import dev.aurelium.auraskills.api.AuraSkillsApi
import dev.aurelium.auraskills.api.skill.Skill
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.entity.Player
import ru.arc.hooks.HookRegistry
import ru.arc.treasurechests.TreasureHuntRegistry
import java.util.Locale

internal const val SIDEBAR_SKILLS_META_KEY = "arc-scoreboard-skills-selection"
internal data class SidebarSkillChoice(val id: String, val name: String)

internal fun selectedSidebarSkills(value: String?): List<String> = value.orEmpty().split(',').filter(String::isNotBlank).distinct().take(2)

internal fun toggleSidebarSkill(selected: List<String>, id: String): List<String> =
    if (id in selected) selected - id else (selected + id).takeLast(2)

internal fun sidebarSkillName(skill: Skill): String = skill.getDisplayName(Locale.forLanguageTag("ru"))

internal fun sidebarSkillChoices(): List<SidebarSkillChoice> =
    if (HookRegistry.auraSkillsHook == null) emptyList() else AuraSkillsApi.get().globalRegistry.skills
        .filter { it.isEnabled }.map { SidebarSkillChoice(it.id.toString(), PlainTextComponentSerializer.plainText().serialize(LegacyComponentSerializer.legacySection().deserialize(sidebarSkillName(it).replace('&', '§')))) }.sortedBy { it.id }

/** Reads only loaded online-player state on the main thread, once per selected section. */
internal fun sidebarPlayerData(player: Player, section: SidebarSection, skillsMetaKey: String = SIDEBAR_SKILLS_META_KEY): Map<String, String> = when (section) {
    SidebarSection.PROFESSION -> if (!Bukkit.getPluginManager().isPluginEnabled("EcoJobs")) emptyMap() else {
        player.activeJobs.sortedWith(compareByDescending<Job> { player.getJobLevel(it) }.thenBy { it.id })
            .take(2).mapIndexed { index, job ->
                val level = player.getJobLevel(job)
                val percent = if (level >= job.maxLevel) 100 else sidebarProgressPercent(player.getJobProgress(job) * 100, 100)
                "%arc_sidebar_profession_${index + 1}%" to "${job.name} &e$level &fур. &7• &a$percent%"
            }.toMap()
    }
    SidebarSection.SKILLS -> if (HookRegistry.auraSkillsHook == null) emptyMap() else {
        val api = AuraSkillsApi.get()
        val user = api.getUser(player.uniqueId)
        val available = api.globalRegistry.skills.filter { it.isEnabled }
        val selected = selectedSidebarSkills(HookRegistry.luckPermsHook?.getCachedMeta(player.uniqueId, skillsMetaKey))
        val skills = if (selected.isEmpty()) available.sortedWith(compareByDescending<dev.aurelium.auraskills.api.skill.Skill> { user.getSkillLevel(it) }.thenBy { it.id.toString() }).take(2)
            else selected.mapNotNull { id -> available.firstOrNull { it.id.toString() == id } }
        skills.mapIndexed { index, skill ->
                val level = user.getSkillLevel(skill)
                val percent = if (level >= skill.maxLevel) 100 else sidebarProgressPercent(user.getSkillXp(skill), api.xpRequirements.getXpRequired(skill, level + 1))
                "%arc_sidebar_skill_${index + 1}%" to "${sidebarSkillName(skill)} &e$level &fур. &7• &a$percent%"
            }.toMap()
    }
    SidebarSection.ACTIVITY -> TreasureHuntRegistry.getActiveHunts().firstOrNull { it.world == player.world }?.let { hunt ->
        mapOf("%arc_sidebar_activity_name%" to "Охота за сокровищами", "%arc_sidebar_activity_progress%" to "${hunt.remainingChests}/${hunt.totalChests} осталось")
    }.orEmpty()
    else -> emptyMap()
}

internal fun sidebarProgressPercent(current: Double, required: Int): Int =
    if (required <= 0) 100 else ((current / required * 100).takeIf(Double::isFinite) ?: 0.0).toInt().coerceIn(0, 100)

internal fun sidebarDirection(yaw: Float): String {
    val directions = listOf("Ю", "ЮЗ", "З", "СЗ", "С", "СВ", "В", "ЮВ")
    val normalized = ((yaw % 360 + 360) % 360)
    return directions[((normalized + 22.5f) / 45).toInt() % directions.size]
}
