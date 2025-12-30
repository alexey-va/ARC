package ru.arc.hooks.worldguard;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import com.sk89q.worldguard.protection.regions.RegionQuery;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;

public class WGHook implements Listener {

    public WGHook() {
    }

    public boolean canBuild(Player player, Location location) {
        LocalPlayer localPlayer = WorldGuardPlugin.inst().wrapPlayer(player);
        RegionContainer container = WorldGuard.getInstance().getPlatform().getRegionContainer();
        if (container == null) return true;
        RegionQuery query = container.createQuery();
        return query.testState(BukkitAdapter.adapt(location), localPlayer, Flags.BUILD);
    }
}
