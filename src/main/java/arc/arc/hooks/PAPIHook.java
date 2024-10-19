package arc.arc.hooks;

import arc.arc.hooks.ps.PSHook;
import arc.arc.xserver.playerlist.PlayerManager;
import dev.espi.protectionstones.PSRegion;
import me.clip.placeholderapi.PlaceholderAPI;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public class PAPIHook extends PlaceholderExpansion {

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
        }

        return null;
    }

    private String jobsBoosts(OfflinePlayer player, String params) {
        if (HookRegistry.jobsHook == null) return "";
        String[] pars = params.split("_");
        if (pars[1].equals("has")) {
            return HookRegistry.jobsHook.hasBoost(player, pars[2]) ? "true" : "false";
        }
        return "";
    }
}
