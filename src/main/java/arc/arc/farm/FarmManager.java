package arc.arc.farm;

import arc.arc.ARC;
import arc.arc.configs.Config;
import arc.arc.configs.ConfigManager;
import arc.arc.hooks.HookRegistry;
import lombok.extern.slf4j.Slf4j;
import org.bukkit.Material;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.time.LocalDate;
import java.util.*;

@Slf4j
public class FarmManager {

    private static List<Mine> mines = new ArrayList<>();
    private static Farm farm;
    private static Lumbermill lumbermill;
    private static BukkitTask clearLimitTask;
    private static int lastResetDay = -1;

    static Config config = ConfigManager.of(ARC.plugin.getDataFolder().toPath(), "farms.yml");

    static Set<Material> farmMaterials = new HashSet<>();
    static Set<Material> lumberMaterials = new HashSet<>();

    public static void init() {
        if (HookRegistry.wgHook == null) {
            log.info("WorldGuard not found! Disabling farm features...");
            return;
        }
        clear();
        setupTasks();
        loadFarm();
        loadLumbermill();
        loadMines();
    }

    public static void addMine(Mine mine) {
        mines.stream()
                .filter(m -> Objects.equals(m.getMineId(), mine.getMineId()))
                .findAny()
                .ifPresentOrElse(m -> log.error("Mine with id {} already exists!", mine.getMineId()),
                        () -> mines.add(mine));
        mines.sort(Comparator.comparingInt(Mine::getPriority).reversed());
    }

    private static void setupTasks() {
        cancelTasks();

        clearLimitTask = new BukkitRunnable() {
            @Override
            public void run() {
                int currentDay = LocalDate.now().getDayOfMonth();
                if (currentDay != lastResetDay) {
                    lastResetDay = currentDay;
                    if (farm != null) farm.resetLimit();
                    mines.forEach(Mine::resetLimit);
                }
            }
        }.runTaskTimer(ARC.plugin, 0L, 60L * 20L);
    }

    public static void cancelTasks() {
        if (clearLimitTask != null && !clearLimitTask.isCancelled()) clearLimitTask.cancel();
    }

    public static void clear() {
        mines.forEach(Mine::cancelTasks);
    }

    public static void processEvent(BlockBreakEvent event) {
        if (farm != null && farm.processBreakEvent(event)) return;
        if (lumbermill != null && lumbermill.processBreakEvent(event)) return;
        for (Mine mine : mines) {
            if (mine.processBreakEvent(event)) return;
        }
    }

    private static void loadFarm() {
        String prefix = "farm.";
        boolean enabled = config.bool(prefix + "enabled", true);
        if (!enabled) {
            log.info("Farm is disabled in config! Skipping...");
            return;
        }

        int maxBlocksPerHour = config.integer(prefix + "blocks-per-hour", 256);
        boolean particles = config.bool(prefix + "particles", true);
        String permission = config.string(prefix + "permission", null);
        String regionName = config.string(prefix + "region");
        String worldName = config.string(prefix + "world");
        if (worldName == null || regionName == null || permission == null) {
            log.info("Farm is misconfigured! Missing world-name or region!");
            return;
        }

        for (String s : config.stringList(prefix + "blocks")) {
            Material material = Material.matchMaterial(s.toUpperCase());
            farmMaterials.add(material);
        }

        FarmManager.farm = new Farm(worldName, regionName, particles, permission, maxBlocksPerHour);
    }

    private static void loadLumbermill() {
        String prefix = "lumbermill.";
        boolean enabled = config.bool(prefix + "enabled", true);
        if (!enabled) {
            log.info("Lumbermill is disabled in config! Skipping...");
            return;
        }

        boolean particles = config.bool(prefix + "particles", true);
        String permission = config.string(prefix + "permission", null);
        String regionName = config.string(prefix + "region");
        String worldName = config.string(prefix + "world");
        if (worldName == null || regionName == null || permission == null) {
            log.info("Lumbermill is misconfigured! Missing world-name or region!");
            return;
        }

        for (String s : config.stringList(prefix + "blocks")) {
            Material material = Material.matchMaterial(s.toUpperCase());
            lumberMaterials.add(material);
        }

        FarmManager.lumbermill = new Lumbermill(worldName, regionName, particles, permission);
    }

    private static void loadMines() {
        mines.clear();
        Map<String, Object> map = config.map("mines");
        for (String mineId : map.keySet()) {
            String prefix = "mines." + mineId + ".";
            boolean enabled = config.bool(prefix + "enabled", true);
            if (!enabled) {
                log.info("{} is disabled in config! Skipping...", mineId);
                continue;
            }

            Map<Material, Integer> materialMap = new HashMap<>();
            for (String s : config.stringList(prefix + "blocks")) {
                String[] strings = s.split(":");
                Material material = Material.matchMaterial(strings[0].toUpperCase());
                int weight = Integer.parseInt(strings[1]);
                materialMap.put(material, weight);
                farmMaterials.add(material);
            }

            int maxBlocksPerHour = config.integer(prefix + "blocks-per-hour", 256);
            boolean particles = config.bool(prefix + "particles", true);
            String permission = config.string(prefix + "permission", null);
            String regionName = config.string(prefix + "region");
            String worldName = config.string(prefix + "world");
            if (worldName == null || regionName == null || permission == null) {
                log.info("Mine {} is misconfigured! Missing world-name or region!", mineId);
                return;
            }

            Material tempBlock = config.material(prefix + "temp-material", Material.BEDROCK);
            int priority = config.integer(prefix + "priority", 1);
            Material baseBlock = config.material(prefix + "base-material", Material.STONE);

            Mine mine = new Mine(mineId, materialMap, regionName, worldName, tempBlock,
                    priority, baseBlock, permission, particles, maxBlocksPerHour);
            addMine(mine);
        }
    }

}
