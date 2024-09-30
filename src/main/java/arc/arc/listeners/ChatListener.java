package arc.arc.listeners;

import arc.arc.ARC;
import arc.arc.TitleInput;
import arc.arc.ai.GPTManager;
import com.Zrips.CMI.events.CMIPlayerTeleportRequestEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.scheduler.BukkitRunnable;

public class ChatListener implements Listener {

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        processTitleInput(event);
        GPTManager.processMessage(event);
    }

    private void processTitleInput(AsyncPlayerChatEvent event) {
        if (!event.isAsynchronous() || !TitleInput.hasInput(event.getPlayer())) return;
        event.setCancelled(true);
        new BukkitRunnable() {
            @Override
            public void run() {
                TitleInput.processMessage(event.getPlayer(), event.getMessage());
            }
        }.runTask(ARC.plugin);
    }

    CMIPlayerTeleportRequestEvent event;

}
