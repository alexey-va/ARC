package arc.arc.farm;

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
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

public class Farm {

    private static Set<Material> blocks = new HashSet<>() {{
        add(Material.WHEAT);
        add(Material.CARROTS);
        add(Material.POTATOES);
        add(Material.BEETROOTS);
        add(Material.MELON);
        add(Material.PUMPKIN);
    }};
    private final String regionName;
    private final String worldName;
    private final World world;
    ProtectedRegion region;

    public Farm() {
        this.regionName = "farm";
        this.worldName = "sp11";

        world = Bukkit.getWorld(worldName);
        if (world == null) {
            //System.out.print("World is null");
            return;
        }

        RegionContainer regionContainer = WorldGuard.getInstance().getPlatform().getRegionContainer();
        this.region = regionContainer.get(BukkitAdapter.adapt(world)).getRegion(regionName);

        if (region == null) {
            System.out.print("No such region");
            return;
        }


    }

    public void processBreakEvent(BlockBreakEvent event) {
        if (region == null) return;
        Block block = event.getBlock();
        if (block.getLocation().getWorld() != world) return;
        if (event.getPlayer().hasPermission("arc.admin")) return;
        if (!region.contains(event.getBlock().getX(), event.getBlock().getY(), event.getBlock().getZ())) return;

        event.setCancelled(true);

        if (!blocks.contains(block.getType())) return;


        if (!event.getPlayer().hasPermission("arc.farm")) {
            sendDenyMessage(0, event.getPlayer());
            return;
        }

        Ageable ageable = null;
        if (block.getBlockData() instanceof Ageable age) {
            ageable = age;
            if (ageable.getAge() != ageable.getMaximumAge()) return;
        }

        if (block.getType() == Material.PUMPKIN || block.getType() == Material.MELON) event.getPlayer().giveExp(5);
        else event.getPlayer().giveExp(1);

        if (ageable != null) {
            Collection<ItemStack> stacks = event.getBlock().getDrops();
            stacks.forEach(stack -> {
                event.getPlayer().getInventory().addItem(stack);
            });
            ageable.setAge(0);
            block.setBlockData(ageable);
        } else {
            block.breakNaturally();
        }
        ParticleUtil.queue(event.getPlayer(), event.getBlock().getLocation().toCenterLocation());
    }

    private void sendDenyMessage(int n, Player player) {
        Component text = null;
        if (n == 0) text = Component.text("Вы не можете ломать этот блок!", NamedTextColor.RED);

        if (text != null) player.sendActionBar(text);
    }

}
