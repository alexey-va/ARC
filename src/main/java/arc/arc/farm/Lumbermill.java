package arc.arc.farm;

import arc.arc.configs.FarmConfig;
import arc.arc.hooks.ArcModule;
import arc.arc.util.ParticleManager;
import arc.arc.util.TextUtil;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class Lumbermill {

    private String regionName;
    private String worldName;
    private final String permission;
    private final boolean particles;
    private final World world;
    private ProtectedRegion region;

    public Lumbermill(String regionName, String worldName, boolean particles, String permission) {
        this.regionName = regionName;
        this.worldName = worldName;
        this.permission = permission;
        this.particles = particles;

        world = Bukkit.getWorld(worldName);
        if (world == null) {
            System.out.print("Farm world is not loaded! " + worldName);
            return;
        }

        RegionContainer regionContainer = WorldGuard.getInstance().getPlatform().getRegionContainer();
        this.region = regionContainer.get(BukkitAdapter.adapt(world)).getRegion(regionName);

        if (region == null) System.out.print("Farm regions name is invalid!");
    }

    public boolean processBreakEvent(BlockBreakEvent event) {
        if (region == null) return false;

        Block block = event.getBlock();
        if (block.getLocation().getWorld() != world) return false;
        if (!region.contains(event.getBlock().getX(), event.getBlock().getY(), event.getBlock().getZ())) return false;
        if (event.getPlayer().hasPermission(FarmConfig.adminPermission)) return true;

        if (!event.getPlayer().hasPermission(permission) ||
                !FarmConfig.lumberMaterials.contains(event.getBlock().getType())) {
            event.getPlayer().sendMessage(TextUtil.noWGPermission());
            event.setCancelled(true);
            return true;
        }
        if (particles) ParticleManager.queue(ParticleManager.ParticleDisplay.builder()
                .players(List.of(event.getPlayer()))
                .extra(0.06)
                .count(5)
                .offsetX(0.25).offsetY(0.25).offsetZ(0.25)
                .location(block.getLocation().toCenterLocation())
                .build());
        return true;
    }

}
