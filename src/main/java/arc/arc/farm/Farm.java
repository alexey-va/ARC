package arc.arc.farm;

import arc.arc.ARC;
import arc.arc.configs.Config;
import arc.arc.configs.ConfigManager;
import arc.arc.util.CooldownManager;
import arc.arc.util.ParticleManager;
import arc.arc.util.TextUtil;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import lombok.extern.slf4j.Slf4j;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;

import java.util.*;

import static arc.arc.util.TextUtil.mm;

@Slf4j
public class Farm {

    private final World world;
    private final String permission;
    private final boolean particles;
    private final int maxBlocks;
    ProtectedRegion region;

    Map<UUID, Integer> blocksBrokenByPlayer = new HashMap<>();

    private static Set<Material> seeds = Set.of(Material.BEETROOT_SEEDS, Material.PUMPKIN_SEEDS,
            Material.MELON_SEEDS, Material.WHEAT_SEEDS, Material.TORCHFLOWER_SEEDS);

    Config config = ConfigManager.of(ARC.plugin.getDataFolder().toPath(), "farms.yml");

    public Farm(String worldName, String regionName, boolean particles, String permission, int maxBlocks) {
        this.particles = particles;
        this.permission = permission;
        this.maxBlocks = maxBlocks;

        seeds = config.materialSet("farm-config.seeds", seeds);

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

        event.setCancelled(true);

        if (!event.getPlayer().hasPermission(permission) ||
                !FarmManager.farmMaterials.contains(event.getBlock().getType())) {
            event.getPlayer().sendMessage(TextUtil.noWGPermission());
            event.setCancelled(true);
            return true;
        }

        if (reachedMax(event.getPlayer())) {
            sendMaxReachedMessage(event.getPlayer());
            return true;
        } else incrementBlocks(event.getPlayer());

        breakBlock(event.getPlayer(), block);
        return true;
    }

    private void breakBlock(Player player, Block block) {
        Ageable ageable = null;
        if (block.getBlockData() instanceof Ageable age) {
            ageable = age;
            if (ageable.getAge() != ageable.getMaximumAge()) return;
        }

        if (ageable != null) {
            player.getInventory().addItem(block.getDrops().stream()
                    .filter(stack -> !seeds.contains(stack.getType()))
                    .toArray(ItemStack[]::new));

            ageable.setAge(0);
            block.setBlockData(ageable, false);
        } else {
            block.breakNaturally();
        }
        if (particles) {
            ParticleManager.queue(ParticleManager.ParticleDisplay.builder()
                    .players(List.of(player))
                    .extra(config.real("farm-config.particle-extra", 0.06))
                    .count(config.integer("farm-config.particle-count", 5))
                    .offsetX(config.real("farm-config.particle-offset", 0.25))
                    .offsetY(config.real("farm-config.particle-offset", 0.25))
                    .offsetZ(config.real("farm-config.particle-offset", 0.25))
                    .location(block.getLocation().toCenterLocation())
                    .build());
        }
    }

    private boolean reachedMax(Player player) {
        return blocksBrokenByPlayer.getOrDefault(player.getUniqueId(), 0) >= maxBlocks;
    }

    private void incrementBlocks(Player player) {
        blocksBrokenByPlayer.merge(player.getUniqueId(), 1, Integer::sum);
        int count = blocksBrokenByPlayer.get(player.getUniqueId());
        if (count % 64 == 0 && count != maxBlocks) {
            Component text = config.componentDef("farm-config.progress-message",
                    "Вы добыли <green><count></green> из <gold><max></gold> за этот день",
                    TagResolver.builder()
                            .tag("count", Tag.inserting(mm(count + "")))
                            .tag("max", Tag.inserting(mm(maxBlocks + "")))
                            .build());
            player.sendActionBar(text);
        }
    }

    private void sendMaxReachedMessage(Player player) {
        if (CooldownManager.cooldown(player.getUniqueId(), "farm_limit_message") > 0) return;
        CooldownManager.addCooldown(player.getUniqueId(), "farm_limit_message",
                config.integer("farm-config.limit-message-cooldown", 60));

        Component text = config.componentDef("farm-config.limit-message",
                "<red>Вы достигли лимита добычи на сегодня</red>");
        player.sendMessage(text);
    }

    public void resetLimit() {
        blocksBrokenByPlayer.clear();
    }


}
