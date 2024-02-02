package arc.arc.farm;

import arc.arc.ARC;
import arc.arc.configs.FarmConfig;
import arc.arc.util.ParticleManager;
import arc.arc.util.TextUtil;
import com.jeff_media.customblockdata.CustomBlockData;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import lombok.Getter;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public class Mine implements Listener {

    private List<TemporaryBlock> tempBlocks = new ArrayList<>();
    private TreeMap<Integer, Material> materialMap = new TreeMap<>();
    private Set<Material> ores = new HashSet<>();
    ProtectedRegion region;
    List<Block> cache;
    BukkitTask replaceCobblestoneTask;
    BukkitTask replaceCache;
    int brokenBlocks = 0;
    int totalWeight;
    private String regionName;
    private String worldName;
    private String permission;
    private boolean particles;
    private World world;
    @Getter
    private String mineId;
    private Material tempBlock;
    private Material baseBlock;
    int priority;

    public Mine(String mineId, Map<Material, Integer> materialMap, String regionName, String worldName,
                Material tempBlock, int priority, Material baseBlock, String permission, boolean particles) {
        this.mineId = mineId;
        this.regionName = regionName;
        this.worldName = worldName;
        this.tempBlock = tempBlock;
        this.priority = priority;
        this.baseBlock = baseBlock;
        this.permission = permission;
        this.particles = particles;

        int totalWeight = 0;
        for (var entry : materialMap.entrySet()) {
            totalWeight += entry.getValue();
            this.materialMap.put(totalWeight, entry.getKey());
            ores.add(entry.getKey());
        }
        this.totalWeight = totalWeight;

        RegionContainer regionContainer = WorldGuard.getInstance().getPlatform().getRegionContainer();
        this.region = regionContainer.get(BukkitAdapter.adapt(world)).getRegion(regionName);

        if (region == null) {
            System.out.print(mineId + " is invalid!");
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
        }.runTaskTimer(ARC.plugin, 20L, 20L);

        replaceCache = new BukkitRunnable() {

            @Override
            public void run() {
                if (brokenBlocks <= 0) return;

                Set<Integer> rng = new HashSet<>();
                for (int i = 0; i < 10; i++) rng.add(ThreadLocalRandom.current().nextInt(cache.size()));

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
        }.runTaskTimer(ARC.plugin, 25L, 20L);
    }

    private void computeCache(boolean replaceTemporaryBlock) {
        List<Block> blocks = new ArrayList<>();
        for (BlockVector3 vector3 : new CuboidRegion(BukkitAdapter.adapt(world), region.getMinimumPoint(), region.getMaximumPoint())) {
            int x = vector3.getBlockX();
            int y = vector3.getBlockY();
            int z = vector3.getBlockZ();
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
        if (event.getPlayer().hasPermission(FarmConfig.adminPermission)) return true;

        event.setCancelled(true);

        if ((permission != null && !event.getPlayer().hasPermission(permission)) || !ores.contains(block.getType())) {
            event.getPlayer().sendMessage(TextUtil.noWGPermission());
            return true;
        }

        CustomBlockData blockData = new CustomBlockData(event.getBlock(), ARC.plugin);
        if (blockData.has(new NamespacedKey(ARC.plugin, "t"))) {
            event.getPlayer().sendActionBar(Component.text("Этот блок еще не восстановился!", NamedTextColor.GOLD));
            return true;
        }

        Collection<ItemStack> stacks = event.getBlock().getDrops();
        stacks.forEach(stack -> event.getPlayer().getInventory().addItem(stack));

        if (block.getType() == baseBlock) event.getPlayer().giveExp(1);
        else event.getPlayer().giveExp(2);

        event.getBlock().setType(tempBlock);
        brokenBlocks++;

        blockData.set(new NamespacedKey(ARC.plugin, "t"), PersistentDataType.BOOLEAN, true);
        tempBlocks.add(new TemporaryBlock(event.getBlock()));
        if (particles) ParticleManager.queue(event.getPlayer(), event.getBlock().getLocation().toCenterLocation());
        return true;
    }

    public void cancelTasks() {
        if (replaceCobblestoneTask != null && !replaceCobblestoneTask.isCancelled()) replaceCobblestoneTask.cancel();
        if (replaceCache != null && !replaceCache.isCancelled()) replaceCache.cancel();
    }

    private static class TemporaryBlock {
        long timestamp;
        Block block;

        public TemporaryBlock(Block block) {
            this.block = block;
            this.timestamp = System.currentTimeMillis();
        }

        public boolean ifExpire() {
            return (System.currentTimeMillis() - timestamp > 60000);
        }
    }

}
