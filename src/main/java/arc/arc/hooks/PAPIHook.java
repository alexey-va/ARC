package arc.arc.hooks;

import arc.arc.configs.MainConfig;
import arc.arc.xserver.playerlist.PlayerManager;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public class PAPIHook extends PlaceholderExpansion {


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
            if(HookRegistry.psHook == null) return null;
            return PSHook.getRegionName(player1.getLocation());
        } else if (params.equalsIgnoreCase("players")) {
            //System.out.println(PlayerManager.getPlayerNames());
            return String.join(", ", PlayerManager.getPlayerNames());
        } else if(params.split("_")[0].equals("parties")){
            return parties(player, params);
        } else if(params.split("_")[0].equals("jobsboosts")){
            return jobsBoosts(player, params);
        }

        return null;
    }

    private String parties(OfflinePlayer player, String params){
        //System.out.println(HookRegistry.partiesHook);
        if (HookRegistry.partiesHook == null) return "";
        String[] pars = params.split("_");
        //SystemSystem.out.println(params);
        switch (pars[1]) {
            case "tag":
                String s = HookRegistry.partiesHook.tag(player.getUniqueId());
                if(s == null) return "";
                if (!s.isEmpty()) return MainConfig.partyTag
                        .replace("%tag%", s)
                        .replace("%color%", HookRegistry.partiesHook.color(player.getUniqueId()));
                else return "";
            case "has":
                return HookRegistry.partiesHook.name(player.getUniqueId()) == null ? "false" : "true";
            case "name":
                String name = HookRegistry.partiesHook.name(player.getUniqueId());
                if(name == null) return "";
                return HookRegistry.partiesHook.color(player.getUniqueId())+
                        name;
        }
        return "";
    }

    private String jobsBoosts(OfflinePlayer player, String params){
        if(HookRegistry.jobsHook == null) return "";
        String[] pars = params.split("_");
        if(pars[0].equals("has")){
            return HookRegistry.jobsHook.hasBoost(player, pars[1]) ? "true" : "false";
        }
        return "";
    }
}
