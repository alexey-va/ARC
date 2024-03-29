package arc.arc.farm;

import arc.arc.configs.FarmConfig;
import arc.arc.util.CooldownManager;
import arc.arc.util.ParticleManager;
import arc.arc.util.TextUtil;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import lombok.Getter;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;

import java.time.Duration;
import java.time.LocalTime;
import java.util.*;

public class Farm {

    private final String regionName;
    private final String worldName;
    private final World world;
    private String permission;
    private boolean particles;
    private int maxBlocks;
    ProtectedRegion region;

    Map<UUID, Integer> blocksBrokenByPlayer = new HashMap<>();

    private static final Set<Material> seeds = Set.of(Material.BEETROOT_SEEDS, Material.PUMPKIN_SEEDS,
            Material.MELON_SEEDS, Material.WHEAT_SEEDS, Material.TORCHFLOWER_SEEDS);

    public Farm(String worldName, String regionName, boolean particles, String permission, int maxBlocks) {
        this.regionName = regionName;
        this.worldName = worldName;
        this.particles = particles;
        this.permission = permission;
        this.maxBlocks = maxBlocks;

        world = Bukkit.getWorld(worldName);
        if (world == null) {
            System.out.print("World " + worldName + " does not exist!");
            return;
        }

        RegionContainer regionContainer = WorldGuard.getInstance().getPlatform().getRegionContainer();
        this.region = regionContainer.get(BukkitAdapter.adapt(world)).getRegion(regionName);

        if (region == null) {
            System.out.print("No region named " + regionName + " in world " + worldName);
        }
    }

    public boolean processBreakEvent(BlockBreakEvent event) {
        if (region == null) return false;

        Block block = event.getBlock();
        if (block.getLocation().getWorld() != world) return false;
        if (!region.contains(event.getBlock().getX(), event.getBlock().getY(), event.getBlock().getZ())) return false;
        if (event.getPlayer().hasPermission(FarmConfig.adminPermission)) return true;

        event.setCancelled(true);

        if (!event.getPlayer().hasPermission(permission) ||
                !FarmConfig.farmMaterials.contains(event.getBlock().getType())) {
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

        //if (block.getType() == Material.PUMPKIN || block.getType() == Material.MELON) player.giveExp(5);
        //else player.giveExp(1);

        if (ageable != null) {
            player.getInventory().addItem(block.getDrops().stream()
                    .filter(stack -> !seeds.contains(stack.getType()))
                    .toArray(ItemStack[]::new));

            ageable.setAge(0);
            block.setBlockData(ageable, false);
        } else {
            block.breakNaturally();
        }
        if (particles) ParticleManager.queue(ParticleManager.ParticleDisplay.builder()
                .players(List.of(player))
                .extra(0.06)
                .count(5)
                .offsetX(0.25).offsetY(0.25).offsetZ(0.25)
                .location(block.getLocation().toCenterLocation())
                .build());
    }

    private boolean reachedMax(Player player) {
        return blocksBrokenByPlayer.getOrDefault(player.getUniqueId(), 0) >= maxBlocks;
    }

    private void incrementBlocks(Player player) {
        blocksBrokenByPlayer.merge(player.getUniqueId(), 1, Integer::sum);
        int count = blocksBrokenByPlayer.get(player.getUniqueId());
        if (count % 64 == 0 && count != maxBlocks) {
            Component text = Component.text("Вы добыли ", NamedTextColor.GRAY)
                    .append(Component.text(count + "", NamedTextColor.GREEN))
                    .append(Component.text(" из ", NamedTextColor.GRAY))
                    .append(Component.text(maxBlocks + "", NamedTextColor.GOLD))
                    .append(Component.text(" за этот день", NamedTextColor.GRAY));
            player.sendActionBar(TextUtil.strip(text));
        }
    }

    private void sendMaxReachedMessage(Player player) {
        if (CooldownManager.cooldown(player.getUniqueId(), "farm_limit_message") > 0) return;
        CooldownManager.addCooldown(player.getUniqueId(), "farm_limit_message", 60);

        //int count = blocksBrokenByPlayer.getOrDefault(player.getUniqueId(), 0);
        //LocalTime now = LocalTime.now();
        //LocalTime reset = LocalTime.of(now.getHour() + 1, 0);
        //Duration tillReset = Duration.between(now, reset);
        Component text = Component.text("Вы слишком разогнались!", NamedTextColor.RED)
                .append(Component.text(" Сброс лимита происходит в полночь.", NamedTextColor.GRAY));
        player.sendMessage(TextUtil.strip(text));
    }

    public void resetLimit() {
        blocksBrokenByPlayer.clear();
    }


}
