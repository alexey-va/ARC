package arc.arc.hooks.worldguard;

import arc.arc.configs.Config;
import arc.arc.util.TextUtil;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;

import java.util.HashSet;
import java.util.Set;

public class WGHook implements Listener {

    private static final Set<Material> whitelist = new HashSet<>() {{
        add(Material.FIRE);
        add(Material.SOUL_FIRE);
    }};
    private static boolean attempt = false;
    ProtectedRegion region = null;

    public WGHook() {
        try {
            RegionContainer container = WorldGuard.getInstance().getPlatform().getRegionContainer();
            World end = Bukkit.getWorld("world_the_end");
            if (end != null) {
                RegionManager manager = container.get(BukkitAdapter.adapt(end));
                region = manager.getRegion("end");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!Config.endProtection) return;
        if(!event.getBlock().getWorld().getName().equalsIgnoreCase("world_the_end")) return;
        if (region == null && !attempt) {
            try {
                RegionContainer container = WorldGuard.getInstance().getPlatform().getRegionContainer();
                World end = Bukkit.getWorld("world_the_end");
                if (end != null) {
                    RegionManager manager = container.get(BukkitAdapter.adapt(end));
                    region = manager.getRegion("end");
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            attempt = true;
        }
        if (region != null
                && region.contains(event.getBlock().getX(), event.getBlock().getY(), event.getBlock().getZ())
                && !whitelist.contains(event.getBlock().getType())
        ) {
            if (!event.getPlayer().hasPermission("mcfine.bypass-end-protection")) {
                event.setCancelled(true);
                event.getPlayer().sendMessage(TextUtil.noWGPermission());
            }
        }
    }


    @EventHandler(priority = EventPriority.LOWEST)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!Config.endProtection) return;
        if (region == null && !attempt) {
            try {
                RegionContainer container = WorldGuard.getInstance().getPlatform().getRegionContainer();
                World end = Bukkit.getWorld("world_the_end");
                if (end != null) {
                    RegionManager manager = container.get(BukkitAdapter.adapt(end));
                    region = manager.getRegion("end");
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            attempt = true;
        }
        if (region != null
                && region.contains(event.getBlock().getX(), event.getBlock().getY(), event.getBlock().getZ())
        ) {
            if (!event.getPlayer().hasPermission("mcfine.bypass-end-protection")) {
                event.setCancelled(true);
                event.getPlayer().sendMessage(TextUtil.noWGPermission());
            }
        }
    }


}
