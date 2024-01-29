package arc.arc.farm;

import arc.arc.hooks.ArcModule;
import arc.arc.util.ParticleUtil;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;

import java.util.HashSet;
import java.util.Set;

public class LumberListener implements Listener, ArcModule {

    private static final Set<Material> wood = new HashSet<>() {{
        add(Material.OAK_WOOD);
        add(Material.OAK_LOG);
        add(Material.SPRUCE_WOOD);
        add(Material.SPRUCE_LOG);
        add(Material.BIRCH_WOOD);
        add(Material.BIRCH_LOG);
        add(Material.ACACIA_WOOD);
        add(Material.ACACIA_LOG);
        add(Material.DARK_OAK_WOOD);
        add(Material.DARK_OAK_LOG);
        add(Material.CHERRY_WOOD);
        add(Material.CHERRY_LOG);
        add(Material.JUNGLE_WOOD);
        add(Material.JUNGLE_LOG);
        add(Material.MANGROVE_WOOD);
        add(Material.MANGROVE_LOG);
        add(Material.SHORT_GRASS);
        add(Material.TALL_GRASS);
        add(Material.ACACIA_LEAVES);
        add(Material.BIRCH_LEAVES);
        add(Material.AZALEA_LEAVES);
        add(Material.CHERRY_LEAVES);
        add(Material.DARK_OAK_LEAVES);
        add(Material.OAK_LEAVES);
        add(Material.JUNGLE_LEAVES);
        add(Material.SPRUCE_LEAVES);
        add(Material.FLOWERING_AZALEA_LEAVES);
        add(Material.MANGROVE_LEAVES);
    }};
    private final String regionName;
    private final String worldName;
    private final World world;
    ProtectedRegion region;

    public LumberListener() {
        regionName = "lumber";
        this.worldName = "sp11";

        world = Bukkit.getWorld(worldName);
        if (world == null) {
            //System.out.print("World is null");
            return;
        }

        RegionContainer regionContainer = WorldGuard.getInstance().getPlatform().getRegionContainer();
        this.region = regionContainer.get(BukkitAdapter.adapt(world)).getRegion(regionName);
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        if (region == null) return;
        Block block = event.getBlock();
        if (block.getLocation().getWorld() != world) return;
        if (event.getPlayer().hasPermission("arc.admin")) return;
        if (!region.contains(event.getBlock().getX(), event.getBlock().getY(), event.getBlock().getZ())) return;

        if (!event.getPlayer().hasPermission("arc.lumber") || !wood.contains(event.getBlock().getType())) {
            sendDenyMessage(0, event.getPlayer());
            event.setCancelled(true);
            return;
        }
        ParticleUtil.queue(event.getPlayer(), event.getBlock().getLocation().toCenterLocation());
    }

    private void sendDenyMessage(int n, Player player) {
        Component text = null;
        if (n == 0) text = Component.text("Вы не можете ломать этот блок!", NamedTextColor.RED);

        if (text != null) player.sendActionBar(text);
    }

}
