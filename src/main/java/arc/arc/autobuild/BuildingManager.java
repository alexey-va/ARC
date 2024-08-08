package arc.arc.autobuild;

import arc.arc.ARC;
import arc.arc.autobuild.gui.BuildingGui;
import arc.arc.autobuild.gui.ConfirmGui;
import arc.arc.util.CooldownManager;
import arc.arc.util.TextUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class BuildingManager {

    static Map<String, Building> buildingMap = new ConcurrentHashMap<>();
    static Map<UUID, ConstructionSite> constructionSiteMap = new HashMap<>();

    public static void addBuilding(Building building) {
        buildingMap.put(building.getFileName(), building);
    }

    public static Building getBuilding(String fileName) {
        return buildingMap.get(fileName);
    }

    public static Collection<Building> getBuildings() {
        return buildingMap.values();
    }

    private static BukkitTask cleanupTask;

    public static void init(){
        // find all display entities with key "db" and remove them
        for (var world : ARC.plugin.getServer().getWorlds()) {
            for (var entity : world.getEntitiesByClass(BlockDisplay.class)) {
                if (entity.getPersistentDataContainer().has(new NamespacedKey(ARC.plugin, "db"), PersistentDataType.STRING)) {
                    entity.remove();
                }
            }
        }
    }

    public static void setupCleanupTask() {
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
                        entry.getValue().player.sendMessage(TextUtil.strip(
                                Component.text("\uD83D\uDEE0 ", NamedTextColor.GRAY)
                                        .append(Component.text("Строительство было отменено из-за неактивности.", NamedTextColor.GRAY))
                        ));
                    }
                }
            }
        }.runTaskTimer(ARC.plugin, 20L, 20L);
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

    public static void createConstruction(Player player, Location center, Building building) {
        long cooldown = CooldownManager.cooldown(player.getUniqueId(), "building_cooldown");
        if(cooldown > 0){
            player.sendMessage(TextUtil.strip(
                            Component.text("\uD83D\uDEE0 ", NamedTextColor.GRAY)
                                    .append(Component.text("Вы уже недавно строили здание!", NamedTextColor.RED))
                                    .append(Component.text(" Подождите еще ", NamedTextColor.GRAY))
                                    .append(Component.text(cooldown/1000/60+" минут", NamedTextColor.YELLOW))
                                    .append(Component.text(" перед тем как построить еще одно."))
                    )
            );
            return;
        }

        int rotation = rotationFromYaw(player.getYaw());
        ConstructionSite site = new ConstructionSite(building, center,
                player, rotation, center.getWorld());
        boolean canBuild = site.canBuild();
        if (!canBuild) {
            site.player.sendMessage(TextUtil.strip(
                    Component.text("\uD83D\uDEE0 ", NamedTextColor.GRAY)
                            .append(Component.text("Вы не можете здесь строить!", NamedTextColor.DARK_RED))
                            .append(Component.text(" Территория пересекается с чьим то приватом.", NamedTextColor.GRAY)))
            );
            return;
        }
        constructionSiteMap.put(player.getUniqueId(), site);
        site.startDisplayingBorder(180);
        site.player.sendMessage(TextUtil.strip(
                Component.text("\uD83D\uDEE0 ", NamedTextColor.GRAY)
                        .append(Component.text("Повторно нажмите на блок, чтобы построить.", NamedTextColor.GOLD)))
        );
    }

    public static void startConfirmation(ConstructionSite site) {
        site.startDisplayingBorderAndDisplay(180);
        site.spawnConfirmNpc(180);
        site.player.sendMessage(TextUtil.strip(
                Component.text("\uD83D\uDEE0 ", NamedTextColor.GRAY)
                        .append(Component.text("Потвердите строителство через NPC.", NamedTextColor.GOLD)))
        );
    }

    public static void startConstruction(ConstructionSite site) {
        site.startBuild();
        site.player.sendMessage(TextUtil.strip(
                Component.text("\uD83D\uDEE0 ", NamedTextColor.GRAY)
                        .append(Component.text("Строительство началось!", NamedTextColor.GOLD)))
        );
    }

    public static void cancelConstruction(ConstructionSite site) {
        if (site.getState() == ConstructionSite.State.BUILDING) {
            site.stopBuild();
            site.player.sendMessage(TextUtil.strip(
                    Component.text("\uD83D\uDEE0 ", NamedTextColor.GRAY)
                            .append(Component.text("Строительство отменено!", NamedTextColor.GRAY)))
            );
        } else if (site.getState() == ConstructionSite.State.DISPLAYING_OUTLINE) {
            site.stopOutlineDisplay();
            //site.player.sendMessage("Cancelled outline display");
        } else if (site.getState() == ConstructionSite.State.CONFIRMATION) {
            site.stopConfirmStep();
            site.player.sendMessage(TextUtil.strip(
                    Component.text("\uD83D\uDEE0 ", NamedTextColor.GRAY)
                            .append(Component.text("Строительство отменено!", NamedTextColor.GRAY)))
            );
        } else if (site.getState() == ConstructionSite.State.CREATED) {
            site.cleanup(0);
            //site.player.sendMessage("Construction site in CREATE state removed!");
        }
    }

    public static void processPlayerClick(Player player, Location location, String buildingId) {
        if(location.getBlock().getType() == Material.SHORT_GRASS || location.getBlock().getType() == Material.TALL_GRASS){
            location = location.clone().add(0,-1,0);
        }

        ConstructionSite site = getConstruction(player.getUniqueId());
        Building building = getBuilding(buildingId);
        if (building == null) {
            System.out.println("No building with id: " + buildingId + " found!");
            return;
        }



        if (site == null) createConstruction(player, location, building);
        else if (site.same(player, location, building) && site.getState() == ConstructionSite.State.DISPLAYING_OUTLINE){
            startConfirmation(site);
        } else {
            if (site.getState() == ConstructionSite.State.BUILDING) {
                site.player.sendMessage(TextUtil.strip(
                        Component.text("\uD83D\uDEE0 ", NamedTextColor.GRAY)
                                .append(Component.text("Вы уже строите одно здание!", NamedTextColor.RED)))
                );
            } else {
                if (site.state == ConstructionSite.State.DISPLAYING_OUTLINE) site.stopOutlineDisplay();
                else if (site.state == ConstructionSite.State.CONFIRMATION) site.stopConfirmStep();
                site.cleanup(0);
                createConstruction(player, location, building);
            }
        }
    }


    public static void confirmConstruction(Player player, boolean confirm) {
        ConstructionSite site = getConstruction(player.getUniqueId());
        if (site == null) {
            System.out.println("Player " + player.getName() + " does not have any construction!");
            return;
        }

        if (site.getState() == ConstructionSite.State.CONFIRMATION) {
            if (confirm) startConstruction(site);
            else cancelConstruction(site);
            return;
        }

        if (site.getState() == ConstructionSite.State.BUILDING) {
            player.sendMessage(TextUtil.strip(
                    Component.text("\uD83D\uDEE0 ", NamedTextColor.GRAY)
                            .append(Component.text("Строительство уже идет!", NamedTextColor.RED))
                    )
            );
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
        if (site == null) {
            return;
        }
        if (site.npcId != id) {
            System.out.println("Чужой NPC");
            return;
        }
        if (site.state == ConstructionSite.State.CONFIRMATION) {
            ConfirmGui confirmGui = new ConfirmGui(clicker, site);
            confirmGui.show(clicker);
        } else if (site.state == ConstructionSite.State.BUILDING) {
            BuildingGui buildingGui = new BuildingGui(clicker, site);
            buildingGui.show(clicker);
        }
    }
}
