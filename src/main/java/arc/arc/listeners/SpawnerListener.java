package arc.arc.listeners;

import arc.arc.hooks.ArcModule;
import arc.arc.util.CooldownManager;
import arc.arc.util.TextUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.TileState;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.persistence.PersistentDataContainer;

import java.util.Set;

public class SpawnerListener implements ArcModule, Listener {

    @EventHandler
    public void spawnerBreak(BlockBreakEvent event){
        if(event.getBlock().getType() != Material.SPAWNER) return;
        if(CooldownManager.cooldown(event.getPlayer().getUniqueId(), "spawner_break") > 0) return;
        if(event.getBlock().getState() instanceof TileState tileState){
            PersistentDataContainer container = tileState.getPersistentDataContainer();
            Set<NamespacedKey> keySet = container.getKeys();
            boolean res = false;
            for(NamespacedKey key : keySet){
                if(key.getNamespace().startsWith("oh_the_dungeons")){
                    res = true;
                    break;
                }
            }
            if(!res) return;
            Player player = event.getPlayer();
            player.sendActionBar(TextUtil.strip(
                    Component.text("В этом данже спавнеры не добываются!")
            ));
            CooldownManager.addCooldown(event.getPlayer().getUniqueId(), "spawner_break", 1200);
        }
    }
}
