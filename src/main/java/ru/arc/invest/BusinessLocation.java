package ru.arc.invest;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import lombok.RequiredArgsConstructor;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.serialization.ConfigurationSerializable;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

@RequiredArgsConstructor
public class BusinessLocation implements ConfigurationSerializable {

    final String worldName;
    final String regionName;

    ProtectedRegion region;


    public void init(){
        World world = Bukkit.getWorld(worldName);
        if(world == null){
            System.out.println("World "+worldName+" was not found!");
            return;
        }

        RegionContainer regionContainer = WorldGuard.getInstance().getPlatform().getRegionContainer();
        this.region = regionContainer.get(BukkitAdapter.adapt(world)).getRegion(regionName);

        if (region == null) {
            System.out.print("No region named " + regionName + " in world " + worldName);
        }
    }

    public static BusinessLocation deserialize(Map<String, Object> map){
        String worldName = (String) map.get("world-name");
        String regionName = (String) map.get("region-name");

        BusinessLocation businessLocation = new BusinessLocation(worldName, regionName);
        return businessLocation;
    }

    @Override
    public @NotNull Map<String, Object> serialize() {
        return null;
    }
}
