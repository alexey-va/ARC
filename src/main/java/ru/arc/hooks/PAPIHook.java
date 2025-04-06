package ru.arc.hooks;

import ru.arc.ARC;
import ru.arc.configs.Config;
import ru.arc.configs.ConfigManager;
import ru.arc.xserver.playerlist.PlayerManager;
import lombok.extern.slf4j.Slf4j;
import me.clip.placeholderapi.PlaceholderAPI;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;

@Slf4j
public class PAPIHook extends PlaceholderExpansion {

    static Config config = ConfigManager.of(ARC.plugin.getDataPath(), "misc.yml");

    public String parse(String str, OfflinePlayer player) {
        return PlaceholderAPI.setPlaceholders(player, str);
    }

    @Override
    public @NotNull String getIdentifier() {
        return "arc";
    }

    @Override
    public @NotNull String getAuthor() {
        return "GrocerMC";
    }

    @Override
    public @NotNull String getVersion() {
        return "1.0";
    }

    @Override
    public boolean persist() {
        return true; // This is required or else PlaceholderAPI will unregister the Expansion on reload
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        if (params.equalsIgnoreCase("players")) {
            return String.join(", ", PlayerManager.getPlayerNames());
        } else if (params.split("_")[0].equals("jobsboosts")) {
            return jobsBoosts(player, params);
        } else if (params.startsWith("rubycount")) {
            return formatRubyCount(player);
        } else if (params.startsWith("guildrank")) {
            return formatGuildRankAndPrestige(player);
        } else if (params.startsWith("particles")) {
            return formatParticleVisibility(player);
        } else if (params.startsWith("worldname")) {
            return getWorldName(player);
        }
        return null;
    }

    @Override
    public @NotNull List<String> getPlaceholders() {
        return List.of(
                "%arc_players%",
                "%arc_jobsboosts_has_<boost_name>%",
                "%arc_rubycount%",
                "%arc_guildrank%",
                "%arc_particles%",
                "%arc_worldname%"
        );
    }


    private String jobsBoosts(OfflinePlayer player, String params) {
        if (HookRegistry.jobsHook == null) return "";
        String[] pars = params.split("_");
        if (pars[1].equals("has")) {
            return HookRegistry.jobsHook.hasBoost(player, pars[2]) ? "true" : "false";
        }
        return "";
    }

    /**
     * Formats the player's money placeholder into a short notation (e.g., "1K", "1M").
     * If parsing fails, returns "...".
     */
    private static String formatRubyCount(OfflinePlayer player) {
        // Obtain the placeholder string for the player's money
        String coinPlaceholder = PlaceholderAPI.setPlaceholders(player, "%elitemobs_player_money%");

        double value;
        try {
            value = Double.parseDouble(coinPlaceholder);
        } catch (NumberFormatException e) {
            // If it's not a valid number, return "..."
            return "...";
        }

        // Short notation
        if (value > 1000 && value < 1_000_000) {
            long rounded = Math.round(value / 1000.0);
            return rounded + "K";
        } else if (value >= 1_000_000) {
            long rounded = Math.round(value / 1_000_000.0);
            return rounded + "M";
        }
        // Otherwise, return the raw number as a string
        long rounded = Math.round(value);
        return String.valueOf(rounded);
    }

    /**
     * Formats a player's guild rank name and prestige into a combined string.
     */
    private static String formatGuildRankAndPrestige(OfflinePlayer player) {
        // Obtain the placeholder strings
        String rname = PlaceholderAPI.setPlaceholders(player, "%elitemobs_player_active_guild_rank_name%");
        String prestigeStr = PlaceholderAPI.setPlaceholders(player, "%elitemobs_player_prestige_guild_rank_numerical%");

        // If the rank name is "Uninitialized player data!", return "..."
        if ("Uninitialized player data!".equals(rname)) {
            return "...";
        }

        String result = "";
        int prestige = 0;
        try {
            prestige = Integer.parseInt(prestigeStr);
        } catch (NumberFormatException e) {
            // If parsing fails, prestige remains 0
        }

        // If prestige is not zero, build the prefix & modify rname
        if (prestige != 0) {
            // The first 2 chars of rname plus prestige
            result += "&7[" + rname.substring(0, Math.min(2, rname.length())) + prestige + "&7] &r";

            // Take the last word from rname
            String[] parts = rname.split(" ");
            rname = parts[parts.length - 1];
        }

        // If total length is too large, use the numeric placeholder for rname
        if (rname.length() + result.length() > 30) {
            rname = prestigeStr;
        }

        return result + rname;
    }

    /**
     * Checks if a player can see particles.
     * Returns "&aВключено" if true, "&cОтключено" otherwise.
     */
    private static String formatParticleVisibility(OfflinePlayer player) {
        // Obtain the placeholder string
        String ph = PlaceholderAPI.setPlaceholders(player, "%playerparticles_can_see_particles%");

        if ("true".equals(ph)) {
            return "&aВключено";
        } else {
            return "&cОтключено";
        }
    }

    // gets world name form config
    private String getWorldName(OfflinePlayer offlinePlayer) {
        if (!(offlinePlayer instanceof Player player)) return "&7Обычный мир";
        String playerWorld = player.getWorld().getName();
        Map<String, String> worlds = config.map("world-names");
        if (worlds.containsKey(playerWorld)) return worlds.get(playerWorld);
        for (String world : worlds.keySet()) {
            if (!world.contains("*")) continue;
            String regexPattern = world.replace("*", ".*");
            if (playerWorld.matches(regexPattern)) {
                return worlds.get(world);
            }
        }
        config.injectDeepKey("world-names." + playerWorld, playerWorld);
        return "&7Обычный мир";
    }
}
