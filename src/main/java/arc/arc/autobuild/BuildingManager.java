package arc.arc.autobuild;

import arc.arc.ARC;
import arc.arc.autobuild.gui.BuildingGui;
import arc.arc.autobuild.gui.ConfirmGui;
import arc.arc.configs.Config;
import arc.arc.configs.ConfigManager;
import arc.arc.util.CooldownManager;
import arc.arc.util.TextUtil;
import lombok.extern.slf4j.Slf4j;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Slf4j
public class BuildingManager {

    static BukkitTask cleanupTask;

    static Map<String, Building> buildingMap = new ConcurrentHashMap<>();
    static Map<UUID, ConstructionSite> constructionSiteMap = new HashMap<>();
    static Config config;

    public static void addBuilding(Building building) {
        buildingMap.put(building.getFileName(), building);
    }

    public static Building getBuilding(String fileName) {
        return buildingMap.get(fileName);
    }

    public static Collection<Building> getBuildings() {
        return buildingMap.values();
    }

    public static void init() {
        config = ConfigManager.of(ARC.plugin.getDataPath(), "auto-build.yml");
        Path path = Paths.get(ARC.plugin.getDataFolder().toString(), "schematics");
        if (!Files.exists(path)) {
            try {
                Files.createDirectory(path);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        try (var stream = Files.walk(Paths.get(ARC.plugin.getDataFolder().toString(), "schematics"), 3,
                FileVisitOption.FOLLOW_LINKS)) {
            stream.filter(p -> !Files.isDirectory(p))
                    .map(Path::getFileName)
                    .map(Path::toString)
                    .map(Building::new)
                    .forEach(BuildingManager::addBuilding);
        } catch (Exception e) {
            log.error("Error while loading buildings", e);
        }
        setupCleanupTask();
    }

    private static void setupCleanupTask() {
        cancelTasks();
        cleanupTask = new BukkitRunnable() {
            @Override
            public void run() {
                Set<Map.Entry<UUID, ConstructionSite>> copy = new HashSet<>(constructionSiteMap.entrySet());
                for (var entry : copy) {
                    if (System.currentTimeMillis() - entry.getValue().getTimestamp() > 180000) {
                        if (entry.getValue().state == ConstructionSite.State.DISPLAYING_OUTLINE) {
                            entry.getValue().stopOutlineDisplay();
                            entry.getValue().cleanup(0);
                        } else if (entry.getValue().state == ConstructionSite.State.CONFIRMATION) {
                            entry.getValue().stopConfirmStep();
                            entry.getValue().cleanup(0);
                        } else if (entry.getValue().state == ConstructionSite.State.CREATED) {
                            entry.getValue().cleanup(0);
                        } else {
                            continue;
                        }
                        Component message = config.componentDef("inactivity-cancel-message", "<gray>\uD83D\uDEE0 Постройка отменена из-за неактивности.");
                        entry.getValue().player.sendMessage(message);
                    }
                }
            }
        }.runTaskTimer(ARC.plugin, 20L, config.integer("cleanup-interval", 20));
    }

    public static void stopAll() {
        Set<Map.Entry<UUID, ConstructionSite>> copy = new HashSet<>(constructionSiteMap.entrySet());
        for (var entry : copy) {
            if (entry.getValue().state == ConstructionSite.State.DISPLAYING_OUTLINE) {
                entry.getValue().stopOutlineDisplay();
                entry.getValue().cleanup(0);
            } else if (entry.getValue().state == ConstructionSite.State.CONFIRMATION) {
                entry.getValue().stopConfirmStep();
                entry.getValue().cleanup(0);
            } else if (entry.getValue().state == ConstructionSite.State.CREATED) {
                entry.getValue().cleanup(0);
            } else if (entry.getValue().state == ConstructionSite.State.BUILDING) {
                entry.getValue().finishBuildState();
                entry.getValue().cleanup(0);
            }
        }
    }

    public static void cancelTasks() {
        if (cleanupTask != null && !cleanupTask.isCancelled()) cleanupTask.cancel();
    }

    public static void createConstruction(Player player, Location center, Building building, int subRotation, int yOffset) {
        long cooldown = CooldownManager.cooldown(player.getUniqueId(), "building_cooldown");
        if (cooldown > 0) {
            Component message = config.componentDef("cooldown-message",
                    "<gray>\uD83D\uDEE0 <red>Вы не можете строить так часто. Подождите %time%.",
                    TagResolver.builder()
                            .tag("time", Tag.inserting(TextUtil.timeComponent(cooldown / 20L, TimeUnit.SECONDS)))
                            .build());
            player.sendMessage(message);
            return;
        }

        int rotation = rotationFromYaw(player.getYaw());
        ConstructionSite site = new ConstructionSite(building, center, player, rotation, center.getWorld(), subRotation, yOffset);

        if (!site.canBuild()) {
            Component message = config.componentDef("cant-build-message", "<gray>\uD83D\uDEE0 <red>Вы не можете строить здесь.");
            player.sendMessage(message);
            return;
        }
        constructionSiteMap.put(player.getUniqueId(), site);
        site.startDisplayingBorder(180);

        Component message = config.componentDef("start-message", "<gray>\uD83D\uDEE0 <green>Нажмите на тот же блок, чтобы подтвердить постройку");
        player.sendMessage(message);
    }

    public static void startConfirmation(ConstructionSite site) {
        int confirmTime = config.integer("confirm-time", 180);
        site.startDisplayingBorderAndDisplay(confirmTime);
        site.spawnConfirmNpc(confirmTime);
        site.player.sendMessage(config.componentDef("confirm-message", "<gray>\uD83D\uDEE0 <green>Подтвердите постройку, нажав ПКМ на NPC"));
    }

    public static void startConstruction(ConstructionSite site) {
        site.startBuild();
        site.player.sendMessage(config.componentDef("start-build-message", "<gray>\uD83D\uDEE0 <green>Постройка начата"));
    }

    public static void cancelConstruction(ConstructionSite site) {
        if (site.getState() == ConstructionSite.State.BUILDING) {
            site.stopBuild();
            site.player.sendMessage(config.componentDef("cancel-build-message", "<gray>\uD83D\uDEE0 <red>Постройка отменена"));
        } else if (site.getState() == ConstructionSite.State.DISPLAYING_OUTLINE) {
            site.stopOutlineDisplay();
        } else if (site.getState() == ConstructionSite.State.CONFIRMATION) {
            site.stopConfirmStep();
            site.player.sendMessage(config.componentDef("cancel-build-message", "<gray>\uD83D\uDEE0 <red>Постройка отменена"));
        } else if (site.getState() == ConstructionSite.State.CREATED) {
            site.cleanup(0);
        }
    }

    public static void processPlayerClick(Player player, Location location, String buildingId, String rot, String yOff) {
        if (config.bool("disable-building", false)) {
            player.sendMessage(config.componentDef("disabled-message", "<gray>\uD83D\uDEE0 <red>Постройка здесь отключена!"));
            return;
        }
        if (location.getBlock().getType() == Material.SHORT_GRASS || location.getBlock().getType() == Material.TALL_GRASS) {
            location = location.clone().add(0, -1, 0);
        }
        int yOffset = 0;
        if (yOff != null && !yOff.isEmpty()) {
            yOffset = Double.valueOf(yOff).intValue();
        }

        int subRotation = 0;
        if (rot != null && !rot.isEmpty()) {
            subRotation = Double.valueOf(rot).intValue();
        }

        ConstructionSite site = getConstruction(player.getUniqueId());
        Building building = getBuilding(buildingId);
        if (building == null) {
            log.error("Building with id {} not found!", buildingId);
            player.sendMessage(config.componentDef("building-not-found-message", "<gray>\uD83D\uDEE0 <red>Здание не найдено!"));
            return;
        }

        if (CooldownManager.cooldown(player.getUniqueId(), "clicked_npc") > 0L) {
            return;
        }


        if (site == null) createConstruction(player, location, building, subRotation, yOffset);
        else if (site.same(player, location, building) && site.getState() == ConstructionSite.State.DISPLAYING_OUTLINE) {
            startConfirmation(site);
        } else {
            if (site.getState() == ConstructionSite.State.BUILDING) {
                site.player.sendMessage(config.componentDef("already-building-message", "<gray>\uD83D\uDEE0 <red>Вы уже строите одно здание!"));
            } else {
                if (site.state == ConstructionSite.State.DISPLAYING_OUTLINE) site.stopOutlineDisplay();
                else if (site.state == ConstructionSite.State.CONFIRMATION) site.stopConfirmStep();
                site.cleanup(0);
                createConstruction(player, location, building, subRotation, yOffset);
            }
        }
    }


    public static void confirmConstruction(Player player, boolean confirm) {
        ConstructionSite site = getConstruction(player.getUniqueId());
        if (site == null) {
            log.info("Player {} tried to confirm construction but no site found", player.getName());
            return;
        }

        if (site.getState() == ConstructionSite.State.CONFIRMATION) {
            if (confirm) startConstruction(site);
            else cancelConstruction(site);
        }
    }

    public static ConstructionSite getConstruction(UUID playerUuid) {
        return constructionSiteMap.get(playerUuid);
    }

    public static int rotationFromYaw(float yaw) {
        yaw += 180;
        if (yaw > 315 || yaw <= 45) return 0;
        else if (yaw <= 135) return 90;
        else if (yaw <= 225) return 180;
        else return 270;
    }

    public static void removeConstruction(UUID uniqueId) {
        constructionSiteMap.remove(uniqueId);
    }


    public static void processNpcClick(Player clicker, int id) {
        ConstructionSite site = constructionSiteMap.get(clicker.getUniqueId());
        if (site == null) return;
        if (site.npcId != id) return;
        if (site.state == ConstructionSite.State.CONFIRMATION) {
            CooldownManager.addCooldown(clicker.getUniqueId(), "clicked_npc", 20L);
            ConfirmGui confirmGui = new ConfirmGui(clicker, site);
            confirmGui.show(clicker);
        } else if (site.state == ConstructionSite.State.BUILDING) {
            BuildingGui buildingGui = new BuildingGui(clicker, site);
            buildingGui.show(clicker);
        }
    }
}
