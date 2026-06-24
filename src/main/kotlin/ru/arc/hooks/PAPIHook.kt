package ru.arc.hooks

import me.clip.placeholderapi.PlaceholderAPI
import me.clip.placeholderapi.expansion.PlaceholderExpansion
import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player
import ru.arc.ARC
import ru.arc.config.ConfigManager
import ru.arc.jobs.JobsModule
import ru.arc.xserver.playerlist.PlayerManager

class PAPIHook : PlaceholderExpansion() {

    companion object {
        private val config = ConfigManager.of(ARC.instance.dataPath, "misc.yml")
    }

    fun parse(str: String, player: OfflinePlayer): String =
        PlaceholderAPI.setPlaceholders(player, str)

    override fun getIdentifier(): String = "arc"
    override fun getAuthor(): String = "GrocerMC"
    override fun getVersion(): String = "1.0"
    override fun persist(): Boolean = true

    override fun onRequest(player: OfflinePlayer, params: String): String? {
        return when {
            params.equals("players", ignoreCase = true) ->
                PlayerManager.getPlayerNames().joinToString(", ")
            params.split("_")[0] == "jobsboosts" -> jobsBoosts(player, params)
            params.startsWith("rubycount") -> formatRubyCount(player)
            params.startsWith("guildrank") -> formatGuildRankAndPrestige(player)
            params.startsWith("particles") -> formatParticleVisibility(player)
            params.startsWith("worldname") -> getWorldName(player)
            else -> null
        }
    }

    override fun getPlaceholders(): List<String> = listOf(
        "%arc_players%",
        "%arc_jobsboosts_has_<boost_name>%",
        "%arc_rubycount%",
        "%arc_guildrank%",
        "%arc_particles%",
        "%arc_worldname%",
    )

    private fun jobsBoosts(player: OfflinePlayer, params: String): String {
        if (!HookRegistry.jobsEnabled) return ""
        val pars = params.split("_")
        if (pars.size > 2 && pars[1] == "has") {
            return if (JobsModule.hasBoost(player, pars[2])) "true" else "false"
        }
        return ""
    }

    private fun formatRubyCount(player: OfflinePlayer): String {
        val coinPlaceholder = PlaceholderAPI.setPlaceholders(player, "%elitemobs_player_money%")
        val value = coinPlaceholder.toDoubleOrNull() ?: return "..."
        return when {
            value > 1000 && value < 1_000_000 -> "${Math.round(value / 1000.0)}K"
            value >= 1_000_000 -> "${Math.round(value / 1_000_000.0)}M"
            else -> Math.round(value).toString()
        }
    }

    private fun formatGuildRankAndPrestige(player: OfflinePlayer): String {
        var rname = PlaceholderAPI.setPlaceholders(player, "%elitemobs_player_active_guild_rank_name%")
        val prestigeStr = PlaceholderAPI.setPlaceholders(player, "%elitemobs_player_prestige_guild_rank_numerical%")

        if ("Uninitialized player data!" == rname) return "..."

        var result = ""
        val prestige = prestigeStr.toIntOrNull() ?: 0

        if (prestige != 0) {
            result += "&7[${rname.substring(0, minOf(2, rname.length))}$prestige&7] &r"
            rname = rname.split(" ").last()
        }

        if (rname.length + result.length > 30) rname = prestigeStr
        return result + rname
    }

    private fun formatParticleVisibility(player: OfflinePlayer): String {
        val ph = PlaceholderAPI.setPlaceholders(player, "%playerparticles_can_see_particles%")
        return if ("true" == ph) "&aВключено" else "&cОтключено"
    }

    private fun getWorldName(offlinePlayer: OfflinePlayer): String {
        val player = offlinePlayer as? Player ?: return "&7Обычный мир"
        val playerWorld = player.world.name
        val worlds: Map<String, String> = config.map("world-names")
        worlds[playerWorld]?.let { return it }
        for ((world, name) in worlds) {
            if (!world.contains("*")) continue
            if (playerWorld.matches(Regex(world.replace("*", ".*")))) return name
        }
        config.injectDeepKey("world-names.$playerWorld", playerWorld)
        return "&7Обычный мир"
    }
}
