package ru.arc.farm;

import ru.arc.ARC;
import ru.arc.configs.Config;
import ru.arc.configs.ConfigManager;
import ru.arc.util.CooldownManager;
import ru.arc.util.ParticleManager;
import ru.arc.util.TextUtil;
import ru.arc.util.Utils;
import com.destroystokyo.paper.ParticleBuilder;
import com.jeff_media.customblockdata.CustomBlockData;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

import static ru.arc.util.TextUtil.mm;

@Slf4j
@Getter
public class Mine implements Listener {

    private final List<TemporaryBlock> tempBlocks = new ArrayList<>();
    private final TreeMap<Integer, Material> materialMap = new TreeMap<>();
    private final Set<Material> ores = new HashSet<>();
    private final int priority;
    ProtectedRegion region;
    List<Block> cache;
    BukkitTask replaceCobblestoneTask;
    BukkitTask replaceCache;
    int brokenBlocks = 0;
    int totalWeight;
    private final String permission;
    private final boolean particles;
    private final World world;
    private final String mineId;
    private final Material tempBlock;
    private final Material baseBlock;

    Config config = ConfigManager.of(ARC.plugin.getDataFolder().toPath(), "farms.yml");
    static long expireTime = 60000L;
    Map<UUID, Integer> blocksBrokenByPlayer = new HashMap<>();
    int maxBlocks;

    public Mine(String mineId, Map<Material, Integer> materialMap, String regionName, String worldName,
                Material tempBlock, int priority, Material baseBlock, String permission, boolean particles, int maxBlocks) {
        this.mineId = mineId;
        this.tempBlock = tempBlock;
        this.priority = priority;
        this.baseBlock = baseBlock;
        this.permission = permission;
        this.particles = particles;
        this.maxBlocks = maxBlocks;
        expireTime = config.integer("mine-config.expire-time", 60000);

        int totalWeight = 0;
        for (var entry : materialMap.entrySet()) {
            totalWeight += entry.getValue();
            this.materialMap.put(totalWeight, entry.getKey());
            ores.add(entry.getKey());
        }
        this.totalWeight = totalWeight;

        this.world = Bukkit.getWorld(worldName);
        if (world == null) {
            System.out.println(worldName + " world does not exist!");
            return;
        }

        RegionManager regionManager = WorldGuard.getInstance().getPlatform().getRegionContainer().get(BukkitAdapter.adapt(world));
        if (regionManager == null) {
            log.error("Region manager is null!");
            return;
        }
        this.region = regionManager.getRegion(regionName);

        if (region == null) {
            log.error("Region {} not found in world {}", regionName, worldName);
            return;
        }
        computeCache(true);
        setupTasks();
    }

    private Material pickRandomMaterial() {
        return materialMap.ceilingEntry((new Random()).nextInt(totalWeight)).getValue();
    }

    private void setupTasks() {
        cancelTasks();

        replaceCobblestoneTask = new BukkitRunnable() {
            @Override
            public void run() {
                List<TemporaryBlock> toRemove = new ArrayList<>();
                tempBlocks.stream()
                        .filter(TemporaryBlock::ifExpire)
                        .forEach(block -> {
                            CustomBlockData blockData = new CustomBlockData(block.block, ARC.plugin);
                            blockData.remove(new NamespacedKey(ARC.plugin, "t"));
                            block.block.setType(baseBlock);
                            toRemove.add(block);
                        });
                tempBlocks.removeAll(toRemove);
            }
        }.runTaskTimer(ARC.plugin, 20L, config.integer("mine-config.replace-time", 20));

        replaceCache = new BukkitRunnable() {

            @Override
            public void run() {
                if (brokenBlocks <= 0) return;

                Set<Integer> rng = new HashSet<>();
                for (int i = 0; i < config.integer("mine-config.replace-batch", 10); i++)
                    rng.add(ThreadLocalRandom.current().nextInt(cache.size()));

                for (int idx : rng) {
                    Block block = cache.get(idx);
                    if (block.getType() != baseBlock) continue;
                    Material material = pickRandomMaterial();
                    if (block.getRelative(0, -1, 0).getType() == Material.AIR) {
                        if (material == Material.SAND) material = Material.SANDSTONE;
                        else if (material == Material.GRAVEL) material = Material.STONE;
                    }
                    block.setType(material);
                    brokenBlocks--;
                    if (brokenBlocks <= 0) return;
                }
            }
        }.runTaskTimer(ARC.plugin, 25L, config.integer("mine-config.replace-time", 20));
    }

    private void computeCache(boolean replaceTemporaryBlock) {
        List<Block> blocks = new ArrayList<>();
        for (BlockVector3 vector3 : new CuboidRegion(BukkitAdapter.adapt(world), region.getMinimumPoint(), region.getMaximumPoint())) {
            int x = vector3.x();
            int y = vector3.y();
            int z = vector3.z();
            Block block = world.getBlockAt(x, y, z);

            if (block.getType() == tempBlock && replaceTemporaryBlock) {
                block.setType(baseBlock);
                CustomBlockData blockData = new CustomBlockData(block, ARC.plugin);
                blockData.remove(new NamespacedKey(ARC.plugin, "t"));
            }

            if ((world.getBlockAt(x + 1, y, z)).getType() == Material.AIR ||
                    (world.getBlockAt(x - 1, y, z)).getType() == Material.AIR ||
                    (world.getBlockAt(x, y + 1, z)).getType() == Material.AIR ||
                    (world.getBlockAt(x, y - 1, z)).getType() == Material.AIR ||
                    (world.getBlockAt(x, y, z + 1)).getType() == Material.AIR ||
                    (world.getBlockAt(x, y, z - 1)).getType() == Material.AIR)
                blocks.add(block);
        }

        this.cache = blocks;
    }

    public boolean processBreakEvent(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (block.getLocation().getWorld() != world || region == null) return false;
        if (!region.contains(event.getBlock().getX(), event.getBlock().getY(), event.getBlock().getZ())) return false;
        if (event.getPlayer().hasPermission(config.string("admin-permission", "arc.farm-admin"))) return true;

        event.setCancelled(true);

        CustomBlockData blockData = new CustomBlockData(event.getBlock(), ARC.plugin);
        if (blockData.has(new NamespacedKey(ARC.plugin, "t"))) {
            Component text = config.componentDef("mine-config.already-broken", "<red>Этот блок еще не восстановился!");
            event.getPlayer().sendActionBar(text);
            return true;
        }

        if ((permission != null && !event.getPlayer().hasPermission(permission)) || !ores.contains(block.getType())) {
            event.getPlayer().sendMessage(TextUtil.noWGPermission());
            return true;
        }

        if (reachedMax(event.getPlayer())) {
            sendMaxReachedMessage(event.getPlayer());
            return true;
        } else if (block.getType() != baseBlock) incrementBlocks(event.getPlayer());

        Collection<ItemStack> stacks = event.getBlock().getDrops();
        stacks.forEach(stack -> event.getPlayer().getInventory().addItem(stack));

        if (block.getType() == baseBlock) event.getPlayer().giveExp(config.integer("mine-config.exp", 1));
        else event.getPlayer().giveExp(config.integer("mine-config.exp", 2));

        event.getBlock().setType(tempBlock);
        brokenBlocks++;

        blockData.set(new NamespacedKey(ARC.plugin, "t"), PersistentDataType.BOOLEAN, true);
        tempBlocks.add(new TemporaryBlock(event.getBlock()));

        Particle randomParticle = Utils.random(new Particle[]{Particle.FLAME, Particle.END_ROD, Particle.CRIT});
        if (particles) ParticleManager.queue(new ParticleBuilder(randomParticle)
                .receivers(List.of(event.getPlayer()))
                .location(block.getLocation().toCenterLocation())
                .count(config.integer("mine-config.particle-count", 5))
                .extra(config.real("mine-config.particle-extra", 0.06))
                .offset(config.real("mine-config.particle-offset", 0.25),
                        config.real("mine-config.particle-offset", 0.25),
                        config.real("mine-config.particle-offset", 0.25)));
        return true;
    }

    public void cancelTasks() {
        if (replaceCobblestoneTask != null && !replaceCobblestoneTask.isCancelled()) replaceCobblestoneTask.cancel();
        if (replaceCache != null && !replaceCache.isCancelled()) replaceCache.cancel();
    }

    private boolean reachedMax(Player player) {
        return blocksBrokenByPlayer.getOrDefault(player.getUniqueId(), 0) >= maxBlocks;
    }

    private void incrementBlocks(Player player) {
        blocksBrokenByPlayer.merge(player.getUniqueId(), 1, Integer::sum);
        int count = blocksBrokenByPlayer.get(player.getUniqueId());
        if (count % 16 == 0 && count != maxBlocks) {
            Component text = config.componentDef("mine-config.progress-message",
                    "<gray>Вы добыли <green><count><gray> из <gold><max><gray> за этот день",
                    TagResolver.builder()
                            .tag("count", Tag.inserting(mm(count + "")))
                            .tag("max", Tag.inserting(mm(maxBlocks + "")))
                            .build());
            player.sendActionBar(text);
        }
    }

    private void sendMaxReachedMessage(Player player) {
        if (CooldownManager.cooldown(player.getUniqueId(), "farm_limit_message") > 0) return;
        CooldownManager.addCooldown(player.getUniqueId(), "farm_limit_message", 60);

        Component text = config.componentDef("mine-config.limit-message", "<red>Вы достигли лимита добычи на сегодня</red>");
        player.sendMessage(text);
    }

    public void resetLimit() {
        blocksBrokenByPlayer.clear();
    }

    private static class TemporaryBlock {
        long timestamp;
        Block block;


        public TemporaryBlock(Block block) {
            this.block = block;
            this.timestamp = System.currentTimeMillis();
        }

        public boolean ifExpire() {
            return (System.currentTimeMillis() - timestamp > expireTime);
        }
    }

}
