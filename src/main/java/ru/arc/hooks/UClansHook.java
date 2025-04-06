package ru.arc.hooks;

import me.ulrich.clans.Clans;
import org.bukkit.Bukkit;

public class UClansHook {

    Clans clan;

    public UClansHook() {
        clan = (Clans) Bukkit.getServer().getPluginManager().getPlugin("Clans");
    }

}
