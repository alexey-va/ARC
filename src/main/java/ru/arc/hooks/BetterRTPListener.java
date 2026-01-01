package ru.arc.hooks;

import ru.arc.ARC;
import ru.arc.commands.arc.subcommands.RespawnOnRtpSubCommand;
import ru.arc.configs.Config;
import ru.arc.configs.ConfigManager;
import me.SuperRonanCraft.BetterRTP.references.customEvents.RTP_TeleportPostEvent;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

public class BetterRTPListener implements Listener {

    Config config = ConfigManager.of(ARC.plugin.getDataPath(), "misc.yml");

    @EventHandler
    public void onBetterRTPEvent(RTP_TeleportPostEvent event) {
        Player player = event.getPlayer();
        Object ifPresent = RespawnOnRtpSubCommand.INSTANCE.getPlayersForRtp().getIfPresent(player.getName());
        if (ifPresent != null) {
            RespawnOnRtpSubCommand.INSTANCE.getPlayersForRtp().invalidate(player.getName());
            Location location = event.getLocation();
            player.setRespawnLocation(location, true);
            player.sendMessage(config.componentDef("rtp-respawn.set-spawn-message", "<green>Ваша точка возрождения установлена здесь! <gray>Чтобы изменить ее, используйте команду /sethome"));
        }
    }

}
