package ru.arc.autobuild;

import ru.arc.ARC;
import ru.arc.configs.Config;
import ru.arc.configs.ConfigManager;
import ru.arc.hooks.HookRegistry;
import ru.arc.hooks.citizens.CitizensHook;
import ru.arc.util.ParticleManager;
import ru.arc.util.Utils;
import com.destroystokyo.paper.ParticleBuilder;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.block.Barrel;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.block.data.BlockData;
import org.bukkit.inventory.ItemStack;
import org.bukkit.loot.LootContext;
import org.bukkit.loot.LootTables;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

import static org.bukkit.Material.*;
import static ru.arc.util.Utils.rotateBlockData;

public class Construction {

    final ConstructionSite site;
    private List<BlockVector3> vectors = new ArrayList<>();
    private BukkitTask buildTask;
    private BukkitTask removeNpcTask;
    AtomicInteger pointer = new AtomicInteger(-1);
    boolean lookClose = false;
    int npcId = -1;

    Set<Material> skipMaterial = Set.of(CHEST, BARREL,
            TRAPPED_CHEST, PLAYER_HEAD, PLAYER_WALL_HEAD, BEDROCK);
    Set<Material> nonDrop = Set.of(SHORT_GRASS, TALL_GRASS, DIRT, GRASS_BLOCK);
    Map<String, String> skins = Map.of(
            "&6Петрович", "https://minesk.in/faca74c68a104b6987bc8c11ffebb092",
            "&6Николаич", "https://minesk.in/6666ba384aa3486b88c21fa7541fb856",
            "&6Иваныч", "https://minesk.in/3ff30e8f08ae48c2abece46bbf0c09d6",
            "&6Агадиль", "https://minesk.in/e8eae58c095949de87ff9c9b5b7c17f2");

    Config config = ConfigManager.of(ARC.plugin.getDataPath(), "auto-build.yml");

    public Construction(ConstructionSite site) {
        this.site = site;

        skins = config.map("construction.npc-skins", skins);
        nonDrop = config.materialSet("construction.not-drop-materials", nonDrop);
        skipMaterial = config.materialSet("construction.skip-materials", skipMaterial);
    }

    public void startBuilding() {
        if (npcId == -1) createNpc(site.centerBlock, -1);
        if (npcId != -1 && HookRegistry.citizensHook != null && lookClose) HookRegistry.citizensHook.lookClose(npcId);
        if (removeNpcTask != null && !removeNpcTask.isCancelled()) removeNpcTask.cancel();
        new BukkitRunnable() {
            @Override
            public void run() {
                ConstructionSite.Corners corners = site.getCorners();

                final int minX = corners.corner1().x();
                final int minY = corners.corner1().y();
                final int minZ = corners.corner1().z();
                final int maxX = corners.corner2().x();
                final int maxY = corners.corner2().y();
                final int maxZ = corners.corner2().z();

                vectors.clear();
                for (int y = minY; y <= maxY; y++) {
                    for (int x = minX; x <= maxX; x++) {
                        for (int z = minZ; z <= maxZ; z++) {
                            vectors.add(BlockVector3.at(x, y, z));
                        }
                    }
                }

                buildTask = new BukkitRunnable() {
                    @Override
                    public void run() {
                        if (buildNextBlocks(config.integer("construction.blocks-per-tick", 3))) {
                            this.cancel();
                            site.finalizeBuilding();
                        }
                    }
                }.runTaskTimer(ARC.plugin, 1L, config.integer("construction.cycle-duration-ticks", 10));
            }
        }.runTaskAsynchronously(ARC.plugin);
    }

    public void cancel(int destroyNpcDelaySeconds) {
        if (buildTask != null && !buildTask.isCancelled()) buildTask.cancel();
        if (removeNpcTask != null && !removeNpcTask.isCancelled()) removeNpcTask.cancel();
        Bukkit.getScheduler().runTaskLater(ARC.plugin, this::destroyNpc, destroyNpcDelaySeconds * 20L);
    }

    public int createNpc(Location location, int seconds) {
        if (HookRegistry.citizensHook == null) return -1;
        var entry = Utils.random(skins);
        npcId = HookRegistry.citizensHook.createNpc(entry.getKey(), location.toCenterLocation());

        HookRegistry.citizensHook.addChatBubble(npcId, List.of(
                new CitizensHook.HologramLine("&6Нажмите ПКМ, чтобы начать строительство", 100)
        ));
        HookRegistry.citizensHook.setSkin(npcId, entry.getValue());
        if (seconds > 0) {
            removeNpcTask = new BukkitRunnable() {
                @Override
                public void run() {
                    HookRegistry.citizensHook.deleteNpc(npcId);
                }
            }.runTaskLater(ARC.plugin, 20L * seconds);
            HookRegistry.citizensHook.lookClose(npcId);
            lookClose = true;
        }
        return npcId;
    }


    private boolean buildNextBlocks(int blocks) {
        int count = 0;
        while (true) {
            int index = pointer.incrementAndGet();
            if (index >= vectors.size()) return true;

            BlockVector3 v = vectors.get(index);

            int yOff = site.yOffset;
            Location location = new Location(site.world,
                    site.centerBlock.x() + v.x(),
                    site.centerBlock.y() + v.y() + yOff,
                    site.centerBlock.z() + v.z());
            int fullRotation = site.fullRotation();
            BlockData data = BukkitAdapter.adapt(site.building.getBlock(v, fullRotation));
            rotateBlockData(data, fullRotation);

            Block currentBlock = site.world.getBlockAt(location);

            if (data.getMaterial() == AIR && currentBlock.getType() == AIR) continue;
            if (data.equals(currentBlock.getBlockData()) || skipMaterial.contains(currentBlock.getType()))
                continue;
            if (HookRegistry.sfHook != null && HookRegistry.sfHook.isSlimefunBlock(currentBlock)) continue;

            if (currentBlock.getType() != data.getMaterial() &&
                    !nonDrop.contains(currentBlock.getType())) giveDrop(currentBlock);

            site.world.getBlockAt(location).setBlockData(data, false);
            if (location.getBlock().getState() instanceof Chest chest) {
                LootTables.SPAWN_BONUS_CHEST.getLootTable()
                        .fillInventory(
                                chest.getInventory(),
                                ThreadLocalRandom.current(),
                                new LootContext.Builder(location).build()
                        );
            } else if (location.getBlock().getState() instanceof Barrel barrel) {
                LootTables.SPAWN_BONUS_CHEST.getLootTable()
                        .fillInventory(
                                barrel.getInventory(),
                                ThreadLocalRandom.current(),
                                new LootContext.Builder(location).build()
                        );
            }
            boolean playerNearby = site.player.getLocation().distance(site.centerBlock) < 50;
            if (count == 0 && playerNearby) playEffects(location, data);
            count++;

            if (count >= blocks) break;
        }
        return false;
    }

    private void giveDrop(Block block) {
        block.getDrops().stream()
                .map(stack -> site.player.getInventory().addItem(stack))
                .flatMap(map -> map.values().stream())
                .forEach(stack -> site.player.getWorld().dropItem(site.player.getLocation(), stack));
    }

    private void playEffects(Location location, BlockData data) {
        if (config.bool("construction.play-sounds", true))
            location.getWorld().playSound(location, data.getSoundGroup().getPlaceSound(), 1f, 1f);
        if (config.bool("construction.show-particles", false)) {
            ParticleManager.queue(new ParticleBuilder(config.particle("construction.place-particle", Particle.FLAME))
                    .count(config.integer("construction.particle-count", 5))
                    .location(location)
                    .receivers(List.of(site.player))
                    .offset(config.real("construction.particle-offset", 0.25),
                            config.real("construction.particle-offset", 0.25),
                            config.real("construction.particle-offset", 0.25))
                    .extra(0.05));
        }
        if (npcId != -1) {
            if (ThreadLocalRandom.current().nextDouble() > 0.8) HookRegistry.citizensHook.faceNpc(npcId, location);
            if (data.getMaterial().isItem())
                HookRegistry.citizensHook.setMainHand(npcId, new ItemStack(data.getMaterial()));
            HookRegistry.citizensHook.animateNpc(npcId, CitizensHook.Animation.ARM_SWING);
        }
    }

    public void destroyNpc() {
        if (HookRegistry.citizensHook == null || npcId == -1) return;
        if (removeNpcTask != null && !removeNpcTask.isCancelled()) removeNpcTask.cancel();
        HookRegistry.citizensHook.deleteNpc(npcId);
    }


    public void finishInstantly() {
        cancel(0);
        buildNextBlocks(1000000);
    }
}
