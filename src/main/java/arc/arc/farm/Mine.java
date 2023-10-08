package arc.arc.farm;

import arc.arc.ARC;
import arc.arc.util.ParticleUtil;
import com.jeff_media.customblockdata.CustomBlockData;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

public class Mine implements Listener {

    private static final Set<Material> ores = new HashSet<>() {{
        add(Material.STONE);
        add(Material.COBBLESTONE);
        add(Material.COAL_ORE);
        add(Material.GOLD_ORE);
        add(Material.COPPER_ORE);
        add(Material.IRON_ORE);
        add(Material.DIAMOND_ORE);
        add(Material.EMERALD_ORE);
        add(Material.REDSTONE_ORE);
        add(Material.LAPIS_ORE);
        add(Material.NETHER_GOLD_ORE);
        add(Material.NETHER_QUARTZ_ORE);
        add(Material.DEEPSLATE_COAL_ORE);
        add(Material.DEEPSLATE_DIAMOND_ORE);
        add(Material.DEEPSLATE_COPPER_ORE);
        add(Material.DEEPSLATE_REDSTONE_ORE);
        add(Material.DEEPSLATE_EMERALD_ORE);
        add(Material.DEEPSLATE_LAPIS_ORE);
        add(Material.DEEPSLATE_IRON_ORE);
        add(Material.DEEPSLATE_GOLD_ORE);
        add(Material.BLACKSTONE);
        add(Material.ANCIENT_DEBRIS);
        add(Material.GLOWSTONE);
        add(Material.AMETHYST_BLOCK);
        add(Material.CLAY);
        add(Material.GRANITE);
        add(Material.ANDESITE);
        add(Material.GRAVEL);
        add(Material.SAND);
        add(Material.SANDSTONE);
        add(Material.SMOOTH_SANDSTONE);
        add(Material.NETHERRACK);
    }};

    private final List<TemporaryBlock> tempBlocks = new ArrayList<>();
    private final Map<Material, Integer> materialMap = new HashMap<>();
    ProtectedRegion region;
    Set<Block> cache;
    BukkitTask replaceCobblestoneTask;
    BukkitTask replaceCache;
    int brokenBlocks = 0;
    int totalWeight = 0;
    private String regionName;
    private String worldName;
    private World world;
    private String mineId;
    private Material tempBlock;
    private Material baseBlock;
    int priority;

    public Mine(String mineId) {

        loadConfig(mineId);
        if(region == null){
            System.out.print(mineId+" is invalid!");
            return;
        }
        computeCache(true);
        setupTasks();

    }


    private void loadConfig(String mineId) {
        ConfigurationSection section = ARC.plugin.getConfig().getConfigurationSection("mine." + mineId);
        if (section == null) {
            System.out.print("Config is not set up! " + mineId);
            return;
        }

        for (String s : section.getStringList("blocks")) {
            String[] strings = s.split(":");
            Material material = Material.matchMaterial(strings[0].toUpperCase());
            int weight = Integer.parseInt(strings[1]);

            totalWeight += weight;
            materialMap.put(material, weight);
        }

        this.regionName = section.getString("region");
        this.worldName = section.getString("world");
        if (worldName == null || regionName == null) {
            System.out.print(mineId + " is misconfigured");
            return;
        }

        world = Bukkit.getWorld(worldName);
        if (world == null) {
            //System.out.print("World is null");
            return;
        }

        RegionContainer regionContainer = WorldGuard.getInstance().getPlatform().getRegionContainer();
        this.region = regionContainer.get(BukkitAdapter.adapt(world)).getRegion(regionName);

        this.tempBlock = Material.matchMaterial(section.getString("temp-material", "cobblestone").toUpperCase());
        this.priority = section.getInt("priority", 1);
        this.baseBlock = Material.matchMaterial(section.getString("base-block", "stone").toUpperCase());

        if (region == null) {
            System.out.print("No such region");
            return;
        }
    }

    private Material pickRandomMaterial() {
        int rng = (new Random()).nextInt(totalWeight);
        int counter = 0;
        Material res = Material.OBSIDIAN;
        for (var entry : materialMap.entrySet()) {
            counter += entry.getValue();
            if (counter >= rng) {
                return entry.getKey();
            }
            res = entry.getKey();
        }
        return res;
    }

    private void setupTasks() {
        cancel();
        replaceCobblestoneTask = new BukkitRunnable() {
            @Override
            public void run() {
                List<TemporaryBlock> toRemove = new ArrayList<>();
                tempBlocks.stream().filter(TemporaryBlock::ifExpire).forEach(block -> {
                    CustomBlockData blockData = new CustomBlockData(block.block, ARC.plugin);
                    blockData.remove(new NamespacedKey(ARC.plugin, "t"));
                    block.block.setType(baseBlock);
                    toRemove.add(block);
                });
                tempBlocks.removeAll(toRemove);
            }
        }.runTaskTimer(ARC.plugin, 20L, 10L);

        replaceCache = new BukkitRunnable() {

            @Override
            public void run() {
                if (brokenBlocks <= 0) return;

                Set<Integer> rng = new HashSet<>();
                rng.add((new Random()).nextInt(cache.size()));
                rng.add((new Random()).nextInt(cache.size()));
                rng.add((new Random()).nextInt(cache.size()));
                rng.add((new Random()).nextInt(cache.size()));
                rng.add((new Random()).nextInt(cache.size()));

                int i = 0;
                for (Block block : cache) {
                    if (block.getType() != baseBlock) continue;
                    if (rng.contains(i)) {
                        brokenBlocks--;
                        block.setType(pickRandomMaterial());
                    }
                    if (brokenBlocks <= 0) return;
                    i++;
                }
            }
        }.runTaskTimer(ARC.plugin, 22L, 10L);
    }

    public void cancel() {
        if (replaceCobblestoneTask != null && !replaceCobblestoneTask.isCancelled()) replaceCobblestoneTask.cancel();
        if (replaceCache != null && !replaceCache.isCancelled()) replaceCache.cancel();
    }

    private void computeCache(boolean replaceCobblestone) {
        Set<Block> blocks = new HashSet<>();
        for (BlockVector3 vector3 : new CuboidRegion(BukkitAdapter.adapt(world), region.getMinimumPoint(), region.getMaximumPoint())) {
            int x = vector3.getBlockX();
            int y = vector3.getBlockY();
            int z = vector3.getBlockZ();
            Block block = world.getBlockAt(x, y, z);


            if (block.getType() == tempBlock && replaceCobblestone){
                block.setType(baseBlock);
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        CustomBlockData blockData = new CustomBlockData(block, ARC.plugin);
                        blockData.remove(new NamespacedKey(ARC.plugin, "t"));
                    }
                }.runTaskAsynchronously(ARC.plugin);
            }

            if ((world.getBlockAt(x + 1, y, z)).getType() == Material.AIR || (world.getBlockAt(x - 1, y, z)).getType() == Material.AIR || (world.getBlockAt(x, y + 1, z)).getType() == Material.AIR || (world.getBlockAt(x, y - 1, z)).getType() == Material.AIR || (world.getBlockAt(x, y, z + 1)).getType() == Material.AIR || (world.getBlockAt(x, y, z - 1)).getType() == Material.AIR)
                blocks.add(block);
        }

        this.cache = blocks;
    }

    public boolean processBreakEvent(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (block.getLocation().getWorld() != world || region == null) return false;
        if (!region.contains(event.getBlock().getX(), event.getBlock().getY(), event.getBlock().getZ())) return false;
        if (event.getPlayer().hasPermission("arc.admin")) return true;

        event.setCancelled(true);
        if (!ores.contains(block.getType())) return true;

        if (!event.getPlayer().hasPermission("arc.mine")) {
            sendDenyMessage(0, event.getPlayer());
            return true;
        }

        CustomBlockData blockData = new CustomBlockData(event.getBlock(), ARC.plugin);
        if (blockData.has(new NamespacedKey(ARC.plugin, "t"))) {
            return true;
        }


        Collection<ItemStack> stacks = event.getBlock().getDrops();
        stacks.forEach(stack -> {
            event.getPlayer().getInventory().addItem(stack);
        });

        if (block.getType() == baseBlock) event.getPlayer().giveExp(2);
        else event.getPlayer().giveExp(5);

        event.getBlock().setType(tempBlock);
        brokenBlocks++;

        blockData.set(new NamespacedKey(ARC.plugin, "t"), PersistentDataType.BOOLEAN, true);
        tempBlocks.add(new TemporaryBlock(event.getBlock()));
        ParticleUtil.queue(event.getPlayer(), event.getBlock().getLocation().toCenterLocation());
        return true;
    }

    private void sendDenyMessage(int n, Player player) {
        Component text = null;
        if (n == 0) text = Component.text("Вы не можете ломать этот блок!", NamedTextColor.RED);

        if (text != null) player.sendActionBar(text);
    }

    private static class TemporaryBlock {
        long timestamp;
        Block block;

        public TemporaryBlock(Block block) {
            this.block = block;
            this.timestamp = System.currentTimeMillis();
        }

        public boolean ifExpire() {
            return (System.currentTimeMillis() - timestamp > 20000);
        }
    }

}
