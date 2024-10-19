package arc.arc.treasurechests;

import arc.arc.ARC;
import arc.arc.configs.TreasureHuntConfig;
import arc.arc.generic.treasure.Treasure;
import arc.arc.generic.treasure.TreasurePool;
import arc.arc.hooks.HookRegistry;
import arc.arc.network.ServerLocation;
import arc.arc.treasurechests.chests.CustomChest;
import arc.arc.treasurechests.chests.ItemsAdderCustomChest;
import arc.arc.treasurechests.chests.VanillaChest;
import arc.arc.treasurechests.locationpools.LocationPool;
import arc.arc.util.ParticleManager;
import com.destroystokyo.paper.ParticleBuilder;
import com.jeff_media.customblockdata.CustomBlockData;
import lombok.RequiredArgsConstructor;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

import static arc.arc.util.TextUtil.mm;

@RequiredArgsConstructor
public class TreasureHunt {

    private static final Logger log = LoggerFactory.getLogger(TreasureHunt.class);
    final LocationPool locationPool;
    final int chests;
    final String namespaceId;
    final Type type;
    final TreasurePool treasurePool;

    int max;
    int left;
    Set<Location> locations;
    Map<Location, CustomChest> customChests = new HashMap<>();
    BukkitTask displayTask;
    BossBar bossBar;
    Set<Player> bossBarAudience = new HashSet<>();
    World world;


    public Set<Location> start() {
        for (Location location : locations) {
            CustomChest customChest;
            Block block = location.getBlock();
            if (type == Type.IA) customChest = new ItemsAdderCustomChest(block, namespaceId);
            else if (type == Type.VANILLA) customChest = new VanillaChest(block);
            else throw new IllegalArgumentException("No such chest type: " + type);
            customChest.create();
            customChests.put(block.getLocation().toCenterLocation(), customChest);
        }
        max = locations.size();
        left = max;

        return customChests.keySet();
    }

    public void stop() {
        clearChests();
        stopDisplayBossbar();
        stopDisplayingLocations();
    }

    public void clearChests() {
        NamespacedKey key = new NamespacedKey(ARC.plugin, "custom_chest");
        for (Location location : locationPool.getLocations().stream().map(ServerLocation::toLocation).toList()) {
            CustomBlockData data = new CustomBlockData(location.getBlock(), ARC.plugin);
            if (data.has(key)) {
                String type = data.get(key, PersistentDataType.STRING);
                CustomChest customChest = null;
                if ("ia".equals(type)) customChest = new ItemsAdderCustomChest(location.getBlock(), null);
                else if ("vanilla".equals(type)) customChest = new VanillaChest(location.getBlock());
                if (customChest != null) customChest.destroy();
            }
        }
    }

    void popChest(Block block, Player player) {
        CustomChest customChest = customChests.get(block.getLocation().toCenterLocation());

        customChest.destroy();
        ParticleManager.queue(new ParticleBuilder(TreasureHuntConfig.treasureHuntParticleClaimed)
                .location(block.getLocation().toCenterLocation())
                .count(TreasureHuntConfig.treasureHuntParticleCountClaimed)
                .extra(TreasureHuntConfig.treasureHuntParticleExtraClaimed)
                .offset(TreasureHuntConfig.treasureHuntParticleOffsetClaimed, TreasureHuntConfig.treasureHuntParticleOffsetClaimed, TreasureHuntConfig.treasureHuntParticleOffsetClaimed)
                .receivers(block.getWorld().getPlayers()));
        block.getWorld().playSound(block.getLocation(), Sound.BLOCK_AMETHYST_CLUSTER_BREAK, 1.0f, 1.0f);

        executeAction(player, block);

        locations.remove(block.getLocation().toCenterLocation());
        left--;
        if (left == 0) stop();
    }

    private void executeAction(Player player, Block block) {
        if (treasurePool == null) {
            System.out.println("Treasure pool is null for " + locationPool.getId() + " hunt!");
            return;
        }

        Treasure treasure = treasurePool.random();
        treasure.give(player);

        String message = (String) treasure.attributes().get("message");
        if (message != null) {
            message = HookRegistry.papiHook == null ? message : HookRegistry.papiHook.parse(message, player);
            Component text = mm(message);
            player.sendMessage(text);
        }

        Boolean announce = (Boolean) treasure.attributes().get("announce");
        if (announce != null && announce) {
            String globalMessage = (String) treasure.attributes().get("globalMessage");
            if (globalMessage == null) {
                log.error("Global message is null for {} hunt!", locationPool.getId());
            } else {
                globalMessage = HookRegistry.papiHook == null ? globalMessage :
                        HookRegistry.papiHook.parse(globalMessage, player);
                Component text = mm(globalMessage);
                world.getPlayers()
                        .stream()
                        .filter(p -> p != player)
                        .forEach(p -> p.sendMessage(text));
            }
        }
    }

    public void generateLocations() {
        locations = locationPool.getNRandom(chests);
        locations.stream().limit(1).map(Location::getWorld).findAny().ifPresent(w -> this.world = w);
    }

    public void displayLocations() {
        stopDisplayingLocations();

        displayTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (locations.isEmpty()) return;
                Collection<Player> players = world.getPlayers();
                for (Location location : locations) {
                    ParticleManager.queue(new ParticleBuilder(TreasureHuntConfig.treasureHuntParticleIdle)
                            .location(location)
                            .count(TreasureHuntConfig.treasureHuntParticleCountIdle)
                            .extra(TreasureHuntConfig.treasureHuntParticleExtraIdle)
                            .offset(TreasureHuntConfig.treasureHuntParticleOffsetIdle,
                                    TreasureHuntConfig.treasureHuntParticleOffsetIdle,
                                    TreasureHuntConfig.treasureHuntParticleOffsetIdle)
                            .receivers(players));
                }

                displayBossbar();
            }
        }.runTaskTimer(ARC.plugin, TreasureHuntConfig.particleDelay, TreasureHuntConfig.particleDelay);
    }

    public void displayBossbar() {
        List<Player> players = world.getPlayers();
        float newProgress = ((float) left) / max;
        if (bossBar == null) {
            final Component name = Component.text("Охота за сокровищами!");
            bossBar = BossBar.bossBar(name, newProgress, BossBar.Color.BLUE, BossBar.Overlay.NOTCHED_6);
        } else if (bossBar.progress() != newProgress) bossBar.progress(newProgress);

        Iterator<Player> iterator = bossBarAudience.iterator();
        while (iterator.hasNext()) {
            Player player = iterator.next();
            if (player.getWorld() != world) {
                player.hideBossBar(bossBar);
                iterator.remove();
            }
        }

        for (Player player : players) {
            if (!bossBarAudience.contains(player)) {
                player.showBossBar(bossBar);
                bossBarAudience.add(player);
            }
        }

    }

    public void stopDisplayBossbar() {
        for (Player player : bossBarAudience) {
            player.hideBossBar(bossBar);
        }
    }

    public void stopDisplayingLocations() {
        if (displayTask != null && !displayTask.isCancelled()) displayTask.cancel();
    }

    enum Type {
        VANILLA, IA
    }

}
