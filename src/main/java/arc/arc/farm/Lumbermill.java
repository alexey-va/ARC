package arc.arc.farm;

import arc.arc.ARC;
import arc.arc.configs.Config;
import arc.arc.configs.ConfigManager;
import arc.arc.util.ParticleManager;
import arc.arc.util.TextUtil;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import lombok.extern.slf4j.Slf4j;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.event.block.BlockBreakEvent;

import java.util.List;

@Slf4j
public class Lumbermill {

    private final String permission;
    private final boolean particles;
    private final World world;
    private ProtectedRegion region;

    Config config = ConfigManager.of(ARC.plugin.getDataFolder().toPath(), "farms.yml");

    public Lumbermill(String worldName, String regionName, boolean particles, String permission) {
        this.permission = permission;
        this.particles = particles;

        world = Bukkit.getWorld(worldName);
        if (world == null) {
            log.error("World {} not found", worldName);
            return;
        }

        RegionContainer regionContainer = WorldGuard.getInstance().getPlatform().getRegionContainer();
        this.region = regionContainer.get(BukkitAdapter.adapt(world)).getRegion(regionName);

        if (region == null) log.error("Region {} not found in world {}", regionName, worldName);
    }

    public boolean processBreakEvent(BlockBreakEvent event) {
        if (region == null) return false;

        Block block = event.getBlock();
        if (block.getLocation().getWorld() != world) return false;
        if (!region.contains(event.getBlock().getX(), event.getBlock().getY(), event.getBlock().getZ())) return false;
        if (event.getPlayer().hasPermission(config.string("admin-permission", "arc.farm-admin"))) return true;

        if (!event.getPlayer().hasPermission(permission) ||
                !FarmManager.lumberMaterials.contains(event.getBlock().getType())) {
            event.getPlayer().sendMessage(TextUtil.noWGPermission());
            event.setCancelled(true);
            return true;
        }
        if (particles) ParticleManager.queue(ParticleManager.ParticleDisplay.builder()
                .players(List.of(event.getPlayer()))
                .extra(config.real("lumbermill-config.particle-extra", 0.06))
                .count(config.integer("lumbermill-config.particle-count", 5))
                .offsetY(config.real("lumbermill-config.particle-offset-y", 0.25))
                .offsetX(config.real("lumbermill-config.particle-offset-x", 0.25))
                .offsetZ(config.real("lumbermill-config.particle-offset-z", 0.25))
                .location(block.getLocation().toCenterLocation())
                .build());
        return true;
    }

}
