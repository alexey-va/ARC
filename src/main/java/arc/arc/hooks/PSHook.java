package arc.arc.hooks;

import arc.arc.ARC;
import arc.arc.hooks.ps.guis.PSMenu;
import com.destroystokyo.paper.ParticleBuilder;
import com.jeff_media.customblockdata.CustomBlockData;
import dev.espi.protectionstones.PSRegion;
import dev.espi.protectionstones.event.PSCreateEvent;
import dev.espi.protectionstones.event.PSRemoveEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class PSHook implements Listener, ArcModule {

    final NamespacedKey key1;
    private final HashSet<UUID> cooldownMessage = new HashSet<>();
    public BukkitTask particleTask;

    public PSHook() {
        key1 = new NamespacedKey(ARC.plugin, "ps");
        init();
    }

    public static String getRegionName(Location location) {
        if (PSRegion.fromLocation(location) == null) {
            if (location.getWorld().getName().equalsIgnoreCase("world_the_end"))
                return ChatColor.translateAlternateColorCodes('&', "&2Ничейные");
            else
                return ChatColor.translateAlternateColorCodes('&', "&6Серверная");

        }
        var owners = PSRegion.fromLocation(location).getOwners();
        if (owners.size() == 0) return "...";
        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(owners.get(0));
        return offlinePlayer.getName();
    }

    public void cancel() {
        if (particleTask != null && !particleTask.isCancelled()) particleTask.cancel();
    }

    public void init() {
        if (particleTask == null || particleTask.isCancelled()) {
            particleTask = new BukkitRunnable() {
                @Override
                public void run() {
                    World world = Bukkit.getWorld("world_the_end");
                    if (world == null) return;
                    final Set<Block> protectionBlocks = new HashSet<>();
                    for (Chunk chunk : world.getLoadedChunks()) {
                        if (chunk.getLoadLevel() != Chunk.LoadLevel.ENTITY_TICKING) continue;
                        protectionBlocks.addAll(CustomBlockData.getBlocksWithCustomData(ARC.plugin, chunk));
                    }

                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            for (Block block : protectionBlocks) {
                                new ParticleBuilder(Particle.END_ROD).location(block.getLocation().toCenterLocation()).count(15)
                                        .offset(0.55, 0.55, 0.55).receivers(30).extra(0).spawn();
                            }
                        }
                    }.runTask(ARC.plugin);
                }
            }.runTaskTimerAsynchronously(ARC.plugin, 10L, 20L);
        }
    }

    @EventHandler
    public void psCreate(PSCreateEvent event) {
        PSRegion region1 = event.getRegion();
        Block center = event.getRegion().getProtectBlock();
        CustomBlockData data = new CustomBlockData(center, ARC.plugin);
        data.set(key1, PersistentDataType.STRING, region1.getId());
    }

    @EventHandler
    public void psRemove(PSRemoveEvent event) {
        Block center = event.getRegion().getProtectBlock();
        CustomBlockData data = new CustomBlockData(center, ARC.plugin);
        data.remove(key1);
    }

    @EventHandler
    public void psInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || !event.hasBlock()) return;
        if (event.getClickedBlock() == null) return;
        CustomBlockData data = new CustomBlockData(event.getClickedBlock(), ARC.plugin);
        String name = data.get(key1, PersistentDataType.STRING);
        if (name == null) return;

        PSRegion region = PSRegion.fromLocation(event.getClickedBlock().getLocation());
        boolean owner = region.isOwner(event.getPlayer().getUniqueId());
        boolean member = region.isMember(event.getPlayer().getUniqueId());

        if (!owner && !member && !event.getPlayer().hasPermission("mcfine.ps-bypass")) {
            if (cooldownMessage.contains(event.getPlayer().getUniqueId())) return;
            event.getPlayer().sendMessage(Component.text("Вы не участник этого региона!", NamedTextColor.RED).decoration(TextDecoration.ITALIC, false));
            cooldownMessage.add(event.getPlayer().getUniqueId());
            new BukkitRunnable() {
                @Override
                public void run() {
                    cooldownMessage.remove(event.getPlayer().getUniqueId());
                }
            }.runTaskLater(ARC.plugin, 3L);
            return;
        }
        
        new PSMenu(region, event.getPlayer()).show(event.getPlayer());
    }

    @EventHandler
    public void aeInteract(BlockBreakEvent event) {
        if (!event.getBlock().getWorld().getEnvironment().equals(World.Environment.THE_END)) return;
        CustomBlockData data = new CustomBlockData(event.getBlock(), ARC.plugin);
        String name = data.get(key1, PersistentDataType.STRING);
        if (name == null) return;

        event.setDropItems(false);
    }

}
