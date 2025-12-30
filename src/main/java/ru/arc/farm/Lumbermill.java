package ru.arc.farm;

import java.util.List;

import com.destroystokyo.paper.ParticleBuilder;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import org.bukkit.Bukkit;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.event.block.BlockBreakEvent;
import ru.arc.ARC;
import ru.arc.configs.Config;
import ru.arc.configs.ConfigManager;
import ru.arc.util.ParticleManager;
import ru.arc.util.RandomUtils;
import ru.arc.util.TextUtil;

import static ru.arc.util.Logging.error;

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
            error("World {} not found", worldName);
            return;
        }

        RegionContainer regionContainer = WorldGuard.getInstance().getPlatform().getRegionContainer();
        this.region = regionContainer.get(BukkitAdapter.adapt(world)).getRegion(regionName);

        if (region == null) error("Region {} not found in world {}", regionName, worldName);
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

        if (particles) {
            Particle randomParticle = RandomUtils.random(new Particle[]{Particle.CRIT, Particle.FLAME,
                    Particle.END_ROD});
            ParticleManager.queue(new ParticleBuilder(randomParticle)
                    .location(block.getLocation().toCenterLocation())
                    .count(config.integer("lumbermill-config.particle-count", 5))
                    .extra(config.real("lumbermill-config.particle-extra", 0.06))
                    .offset(config.real("lumbermill-config.particle-offset", 0.25),
                            config.real("lumbermill-config.particle-offset", 0.25),
                            config.real("lumbermill-config.particle-offset", 0.25))
                    .receivers(List.of(event.getPlayer())));
        }
        return true;
    }

}
