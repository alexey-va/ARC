package arc.arc.treasurechests;

import arc.arc.ARC;
import arc.arc.configs.TreasureHuntConfig;
import arc.arc.network.ServerLocation;
import arc.arc.treasurechests.chests.CustomChest;
import arc.arc.treasurechests.chests.ItemsAdderCustomChest;
import arc.arc.treasurechests.chests.VanillaChest;
import arc.arc.treasurechests.locationpools.LocationPool;
import arc.arc.util.ParticleManager;
import com.jeff_media.customblockdata.CustomBlockData;
import lombok.RequiredArgsConstructor;
import me.clip.placeholderapi.PlaceholderAPI;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

@RequiredArgsConstructor
public class TreasureHunt {

    final LocationPool locationPool;
    final int chests;
    final String namespaceId;
    final Type type;
    final String id;
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

    public void stop(){
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
        ParticleManager.queue(ParticleManager.ParticleDisplay.builder()
                .extra(TreasureHuntConfig.treasureHuntParticleExtraClaimed)
                .offsetX(TreasureHuntConfig.treasureHuntParticleOffsetClaimed)
                .offsetY(TreasureHuntConfig.treasureHuntParticleOffsetClaimed)
                .offsetZ(TreasureHuntConfig.treasureHuntParticleOffsetClaimed)
                .particle(TreasureHuntConfig.treasureHuntParticleClaimed)
                .count(TreasureHuntConfig.treasureHuntParticleCountClaimed)
                .players(block.getWorld().getPlayers())
                .location(block.getLocation().toCenterLocation())
                .build());
        block.getWorld().playSound(block.getLocation(), Sound.BLOCK_AMETHYST_CLUSTER_BREAK, 1.0f, 1.0f);

        executeAction(player, block);

        locations.remove(block.getLocation().toCenterLocation());
        left--;
        if(left == 0){
            stop();
        }
    }

    private void executeAction(Player player, Block block){
        String command = TreasureHuntConfig.treasureHuntCommands.get(id);
        command = PlaceholderAPI.setPlaceholders(player, command);
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
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
                if(locations.isEmpty()) return;
                Collection<Player> players = world.getPlayers();
                for (Location location : locations) {
                    ParticleManager.queue(
                            ParticleManager.ParticleDisplay.builder()
                                    .extra(TreasureHuntConfig.treasureHuntParticleExtraIdle)
                                    .offsetX(TreasureHuntConfig.treasureHuntParticleOffsetIdle)
                                    .offsetY(TreasureHuntConfig.treasureHuntParticleOffsetIdle)
                                    .offsetZ(TreasureHuntConfig.treasureHuntParticleOffsetIdle)
                                    .particle(TreasureHuntConfig.treasureHuntParticleIdle)
                                    .count(TreasureHuntConfig.treasureHuntParticleCountIdle)
                                    .players(players)
                                    .location(location)
                                    .build()
                    );
                }

                displayBossbar();
            }
        }.runTaskTimer(ARC.plugin, TreasureHuntConfig.particleDelay, TreasureHuntConfig.particleDelay);
    }

    public void displayBossbar(){
        List<Player> players = world.getPlayers();
        float newProgress = ((float) left)/max;
        if(bossBar == null){
            final Component name = Component.text("Охота за сокровищами!");
            bossBar = BossBar.bossBar(name, newProgress, BossBar.Color.BLUE, BossBar.Overlay.NOTCHED_6);
        } else if(bossBar.progress() != newProgress) bossBar.progress(newProgress);

        Iterator<Player> iterator = bossBarAudience.iterator();
        while (iterator.hasNext()){
            Player player = iterator.next();
            if(player.getWorld() != world){
                player.hideBossBar(bossBar);
                iterator.remove();
            }
        }

        for(Player player : players){
            if(!bossBarAudience.contains(player)){
                player.showBossBar(bossBar);
                bossBarAudience.add(player);
            }
        }

    }

    public void stopDisplayBossbar(){
        for(Player player : bossBarAudience){
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
