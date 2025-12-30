package ru.arc.autobuild;

import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import ru.arc.ARC;
import ru.arc.autobuild.gui.BuildingGui;
import ru.arc.autobuild.gui.ConfirmGui;
import ru.arc.configs.Config;
import ru.arc.configs.ConfigManager;
import ru.arc.hooks.HookRegistry;
import ru.arc.util.CooldownManager;
import ru.arc.util.TextUtil;

import static ru.arc.util.Logging.error;
import static ru.arc.util.Logging.info;

public class BuildingManager {

    static BukkitTask cleanupTask;

    static Map<String, Building> buildingMap = new ConcurrentHashMap<>();
    static Map<UUID, ConstructionSite> pendingConstructionSiteMap = new ConcurrentHashMap<>();
    static Map<UUID, List<ConstructionSite>> activeConstructionSiteMap = new ConcurrentHashMap<>();
    static Config config;

    public static Map<String, String> skins = Map.of(
            "&6Петрович", "https://minesk.in/faca74c68a104b6987bc8c11ffebb092",
            "&6Николаич", "https://minesk.in/6666ba384aa3486b88c21fa7541fb856",
            "&6Иваныч", "https://minesk.in/3ff30e8f08ae48c2abece46bbf0c09d6",
            "&6Агадиль", "https://minesk.in/e8eae58c095949de87ff9c9b5b7c17f2");

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
            error("Error while loading buildings", e);
        }
        setupCleanupTask();

        Set<String> npcNames = config.map("construction.npc-skins", skins).keySet();
        if (HookRegistry.citizensHook != null) {
            HookRegistry.citizensHook.deleteWithNames(npcNames);
        }
    }

    private static void setupCleanupTask() {
        cancelTasks();
        cleanupTask = new BukkitRunnable() {
            @Override
            public void run() {
                clean(false);
            }
        }.runTaskTimer(ARC.plugin, 20L, config.integer("cleanup-interval", 20));
    }

    private static void clean(boolean force) {
        List<ConstructionSite> sitesToRemove = new ArrayList<>();
        sitesToRemove.addAll(pendingConstructionSiteMap.values());
        for (var entry : activeConstructionSiteMap.entrySet()) {
            sitesToRemove.addAll(entry.getValue());
        }
        Component message = config.componentDef("inactivity-cancel-message", "<gray>\uD83D\uDEE0 Постройка отменена из-за неактивности.");
        for (var site : sitesToRemove) {
            try {
                if (System.currentTimeMillis() - site.getTimestamp() > 180000 || force) {
                    info("Cleaning up construction site for player {} {}", site.player.getName(), site);
                    try {
                        switch (site.state) {
                            case DISPLAYING_OUTLINE -> {
                                site.stopOutlineDisplay();
                                site.cleanup(0);
                                site.player.sendMessage(message);
                            }
                            case CONFIRMATION -> {
                                site.stopConfirmStep();
                                site.cleanup(0);
                                site.player.sendMessage(message);
                            }
                            case BUILDING -> {
                                if (force) {
                                    site.finishBuildStateAndCleanup();
                                }
                            }
                            case DONE, CREATED, CANCELLED -> site.cleanup(0);
                            case null -> error("Site state is null for player {}", site.player.getName());
                        }
                    } catch (Exception e) {
                        error("Error while cleaning up site for player {}", site.player.getName(), e);
                    } finally {
                        BuildingManager.removeConstruction(site);
                    }
                }
            } catch (Exception e) {
                error("Error while cleaning up", e);
            }
        }
    }

    public static void stopAll() {
        clean(true);
        cancelTasks();
    }

    public static void cancelTasks() {
        if (cleanupTask != null && !cleanupTask.isCancelled()) cleanupTask.cancel();
    }

    public static void createConstruction(Player player, Location center, Building building, int subRotation, int yOffset) {
        long cooldown = CooldownManager.cooldown(player.getUniqueId(), "building_cooldown");
        if (cooldown > 0 && !player.hasPermission("arc.admin")) {
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

        if (!site.canBuild() && !player.hasPermission("arc.admin")) {
            Component message = config.componentDef("cant-build-message", "<gray>\uD83D\uDEE0 <red>Вы не можете строить здесь.");
            player.sendMessage(message);
            return;
        }
        pendingConstructionSiteMap.put(player.getUniqueId(), site);
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
        moveToActive(site);
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
        if (config.bool("disable-building", false) && !player.hasPermission("arc.admin")) {
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

        ConstructionSite site = getPendingConstruction(player.getUniqueId());
        Building building = getBuilding(buildingId);
        if (building == null) {
            error("Building with id {} not found!", buildingId);
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
        ConstructionSite site = getPendingConstruction(player.getUniqueId());
        if (site == null) {
            info("Player {} tried to confirm construction but no site found", player.getName());
            return;
        }

        if (site.getState() == ConstructionSite.State.CONFIRMATION) {
            if (confirm) startConstruction(site);
            else cancelConstruction(site);
        }
    }

    public static ConstructionSite getPendingConstruction(UUID playerUuid) {
        return pendingConstructionSiteMap.get(playerUuid);
    }

    public static int rotationFromYaw(float yaw) {
        yaw += 180;
        if (yaw > 315 || yaw <= 45) return 0;
        else if (yaw <= 135) return 90;
        else if (yaw <= 225) return 180;
        else return 270;
    }

    public static void removeConstruction(ConstructionSite site) {
        List<ConstructionSite> constructionSites = activeConstructionSiteMap.get(site.player.getUniqueId());
        if(constructionSites != null) {
            constructionSites.remove(site);
            if (constructionSites.isEmpty()) activeConstructionSiteMap.remove(site.player.getUniqueId());
        }
        pendingConstructionSiteMap.remove(site.player.getUniqueId());
    }

    private static void moveToActive(ConstructionSite site) {
        List<ConstructionSite> constructionSites = activeConstructionSiteMap
                .computeIfAbsent(site.player.getUniqueId(), k -> new ArrayList<>());
        constructionSites.add(site);
        pendingConstructionSiteMap.remove(site.player.getUniqueId());
    }

    private static ConstructionSite findByNpcId(int id) {
        for (ConstructionSite site : pendingConstructionSiteMap.values()) {
            if (site.npcId == id) return site;
        }
        for (List<ConstructionSite> sites : activeConstructionSiteMap.values()) {
            for (ConstructionSite site : sites) {
                if (site.npcId == id) return site;
            }
        }
        return null;
    }


    public static void processNpcClick(Player clicker, int id) {
        ConstructionSite site = findByNpcId(id);
        if (site == null) return;
        if (!site.player.getUniqueId().equals(clicker.getUniqueId()) && !clicker.hasPermission("arc.admin")) {
            clicker.sendMessage(config.componentDef("not-your-npc", "<gray>\uD83D\uDEE0 <red>Этот NPC не принадлежит вам!"));
            return;
        }
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
