package arc.arc.hooks;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public class PAPHook extends PlaceholderExpansion {
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
        if(params.equalsIgnoreCase("region_here")){
            Player player1 = player.getPlayer();
            if(player1 == null || !player1.isOnline()) return "...";
            return PSHook.getRegionName(player1.getLocation());
        }

        return null;
    }
}
