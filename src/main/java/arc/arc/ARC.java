package arc.arc;

import com.magmaguy.elitemobs.api.EliteExplosionEvent;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import io.github.thebusybiscuit.slimefun4.api.events.PlayerRightClickEvent;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

public final class ARC extends JavaPlugin implements Listener {

    private static boolean backpacks = false;
    private static boolean endProtection = false;
    private static Set<String> noExpWorlds = new HashSet<>();
    private static Set<Material> whitelist = new HashSet<>() {{
        add(Material.FIRE);
        add(Material.SOUL_FIRE);
    }};
    ProtectedRegion region = null;

    @Override
    public void onEnable() {
        // Plugin startup logic
        Bukkit.getPluginManager().registerEvents(this, this);
        getConfig().options().copyDefaults();
        saveDefaultConfig();

        backpacks = getConfig().getBoolean("disable-backpacks", false);
        endProtection = getConfig().getBoolean("end-protection", false);
        noExpWorlds = new HashSet<>(getConfig().getStringList("no-explosion-worlds"));

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

    @Override
    public void onDisable() {
        // Plugin shutdown logic
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!endProtection) return;
        if (region != null
                && region.contains(event.getBlock().getX(), event.getBlock().getY(), event.getBlock().getZ())
                && whitelist.contains(event.getBlock().getType())
        ) {
            if (!event.getPlayer().hasPermission("mcfine.bypass-end-protection")) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onUseBackpack(PlayerRightClickEvent event) {
        if (!backpacks) return;
        Optional<SlimefunItem> optional = event.getSlimefunItem();
        if (optional.isPresent()) {
            SlimefunItem item = optional.get();
            if (item.getId().contains("BACKPACK")) {
                event.cancel();
            }
        }
    }

    @EventHandler
    public void emExplosion(EliteExplosionEvent event) {
        if (noExpWorlds.isEmpty()) return;
        if (noExpWorlds.contains(event.getExplosionSourceLocation().getWorld().getName())) {
            event.setCancelled(true);
        }
    }
}
