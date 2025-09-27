package ru.arc.leafdecay;

import com.destroystokyo.paper.ParticleBuilder;
import com.jeff_media.customblockdata.CustomBlockData;
import lombok.extern.slf4j.Slf4j;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import ru.arc.ARC;
import ru.arc.configs.Config;
import ru.arc.configs.ConfigManager;
import ru.arc.util.ParticleManager;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;


@Slf4j
public class LeafDecayManager {

    public static LeafChecker leafChecker;
    private static BukkitTask checkTask;
    private static BukkitTask decayTask;
    private static BukkitTask tagClearTask;

    private static final ConcurrentSkipListSet<Chunk> chunkQueue =
            new ConcurrentSkipListSet<>(Comparator.comparingInt(Chunk::getX).thenComparingInt(Chunk::getZ));
    private static final Deque<Location> leafQueue = new ConcurrentLinkedDeque<>();
    private static final Deque<Block> awaitingTagClearing = new ConcurrentLinkedDeque<>();
    private static final NamespacedKey playerPlacedKey = new NamespacedKey(ARC.plugin, "lf");
    private static final Config config = ConfigManager.of(ARC.plugin.getDataPath(), "leafdecay.yml");

    private static long decayInterval = 5L;
    private static long checkInterval = 20L;
    private static long checkWorldEach = 1000L;
    private static long leafBatchSize = 100L;
    private static Set<Material> leafMaterials;
    private static Set<Material> trunkMaterials;
    private static boolean playParticles = true;
    private static boolean playSound = true;
    private static Set<String> worlds = Set.of();

    public static void init() {
        reload();
    }

    public static void reload() {
        decayInterval = config.integer("decay-interval", 5);
        checkInterval = config.integer("global-check-interval", 20);
        checkWorldEach = config.integer("world-check-interval", 1000);
        leafBatchSize = config.integer("leaf-batch-size", 100);
        leafMaterials = config.<String>list("leaf-materials").stream()
                .map(String::toUpperCase)
                .map(Material::valueOf)
                .collect(Collectors.toSet());
        trunkMaterials = config.<String>list("trunk-materials").stream()
                .map(String::toUpperCase)
                .map(Material::valueOf)
                .collect(Collectors.toSet());
        leafChecker = new LeafChecker(config, leafMaterials, trunkMaterials);
        playParticles = config.bool("play-particles", true);
        playSound = config.bool("play-sound", true);
        worlds = new HashSet<>(config.stringList("leaf-decay-worlds"));

        start();
    }

    public static void cancel() {
        if (checkTask != null && !checkTask.isCancelled()) checkTask.cancel();
        if (decayTask != null && !decayTask.isCancelled()) decayTask.cancel();
        if (tagClearTask != null && !tagClearTask.isCancelled()) tagClearTask.cancel();
    }

    public static void start() {
        cancel();
        AtomicInteger counter = new AtomicInteger();
        checkTask = new BukkitRunnable() {
            @Override
            public void run() {
                int count2 = 0;
                if (leafQueue.size() > 10000) return;
                while (!chunkQueue.isEmpty()) {
                    if (count2++ > 5) break;
                    Chunk chunk = chunkQueue.pollFirst();
                    if (chunk != null) {
                        String worldName = chunk.getWorld().getName();
                        if (worlds.contains(worldName)) checkChunk(chunk);
                    }
                }
                int count = counter.incrementAndGet();
                if (count >= checkWorldEach) {
                    counter.set(0);
                    for (String worldName : worlds) {
                        World world = Bukkit.getWorld(worldName);
                        //info("Checking world {}", world);
                        if (world == null) continue;
                        pollChunksInWorld(world);
                    }
                }
            }
        }.runTaskTimer(ARC.plugin, 60L, checkInterval);

        decayTask = new BukkitRunnable() {
            @Override
            public void run() {
                for (int i = 0; i < leafBatchSize; i++) {
                    Location leafData = leafQueue.poll();
                    if (leafData == null) return;
                    Block block = leafData.getBlock();
                    if (block.getType().isAir()) return;
                    Material type = block.getType();
                    block.breakNaturally();
                    if (playSound) block.getWorld().playSound(block.getLocation(), Sound.BLOCK_GRASS_BREAK, 1.0F, 1.0F);
                    if (playParticles) {
                        Collection<Player> nearbyPlayers = block.getWorld().getNearbyPlayers(block.getLocation(), 32);
                        ParticleManager.queue(new ParticleBuilder(Particle.BLOCK)
                                .count(2)
                                .location(block.getLocation().add(0.5, 0.5, 0.5))
                                .extra(0.1)
                                .data(type.createBlockData())
                                .offset(0.3, 0.3, 0.3)
                                .receivers(nearbyPlayers)
                        );
                    }
                }
            }
        }.runTaskTimer(ARC.plugin, 60L, decayInterval);

        tagClearTask = new BukkitRunnable() {
            @Override
            public void run() {
                while (!awaitingTagClearing.isEmpty()) {
                    Block block = awaitingTagClearing.pollFirst();
                    if (block == null) return;
                    CustomBlockData data = new CustomBlockData(block, ARC.plugin);
                    if (data.has(playerPlacedKey)) {
                        data.remove(playerPlacedKey);
                    }
                }
            }
        }.runTaskTimerAsynchronously(ARC.plugin, 60L, 1);
    }

    private static void checkChunk(Chunk chunk) {
        boolean neighborsLoaded = chunk.getWorld().isChunkLoaded(chunk.getX() + 1, chunk.getZ()) &&
                chunk.getWorld().isChunkLoaded(chunk.getX() - 1, chunk.getZ()) &&
                chunk.getWorld().isChunkLoaded(chunk.getX(), chunk.getZ() + 1) &&
                chunk.getWorld().isChunkLoaded(chunk.getX(), chunk.getZ() - 1);
        if (!neighborsLoaded) return;
        Collection<Location> leafData = leafChecker.checkChunk(chunk);
        leafQueue.addAll(leafData);
    }

    private static void pollChunksInWorld(World world) {
        if (leafQueue.size() > 10000) return;
        Collection<Player> players = world.getPlayers();
        Set<Chunk> chunks = players.stream()
                .map(Player::getLocation)
                .map(Location::getChunk)
                .flatMap(chunk -> Arrays.stream(new Chunk[]{
                        chunk,
                        world.getChunkAt(chunk.getX() + 1, chunk.getZ()),
                        world.getChunkAt(chunk.getX() - 1, chunk.getZ()),
                        world.getChunkAt(chunk.getX(), chunk.getZ() + 1),
                        world.getChunkAt(chunk.getX(), chunk.getZ() - 1),
                        world.getChunkAt(chunk.getX() + 1, chunk.getZ() + 1),
                        world.getChunkAt(chunk.getX() - 1, chunk.getZ() - 1),
                        world.getChunkAt(chunk.getX() + 1, chunk.getZ() - 1),
                        world.getChunkAt(chunk.getX() - 1, chunk.getZ() + 1)
                }))
                .collect(Collectors.toSet());
        chunkQueue.addAll(chunks);
    }

    public static void markAsPlayerPlaced(Block block) {
        if (!leafMaterials.contains(block.getType()) && !trunkMaterials.contains(block.getType())) return;
        CustomBlockData data = new CustomBlockData(block, ARC.plugin);
        data.set(playerPlacedKey, PersistentDataType.BOOLEAN, true);
    }

    public static void clearPlayerPlaced(Block block) {
        awaitingTagClearing.add(block);
    }
}