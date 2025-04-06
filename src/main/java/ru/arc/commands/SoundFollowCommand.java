package ru.arc.commands;

import ru.arc.util.TextUtil;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public class SoundFollowCommand implements CommandExecutor {
    @Override
    public boolean onCommand(@NotNull CommandSender commandSender, @NotNull Command command, @NotNull String s, @NotNull String[] strings) {

        if(!commandSender.hasPermission("arc.sound-follow")){
            commandSender.sendMessage(TextUtil.noPermissions());
            return true;
        }

        if(strings.length != 2){
            commandSender.sendMessage("Wrong number of args!");
            return true;
        }

        String playerName = strings[0];
        var oPlayer = Bukkit.getOnlinePlayers().stream().filter(p -> p.getName().equals(playerName)).findAny();
        if(oPlayer.isEmpty()){
            commandSender.sendMessage("Could not find player: "+playerName);
            return true;
        }

        Player player = oPlayer.get();

        String[] soundNames = strings[1].split(":");

        Sound sound = null;
        if(soundNames.length == 1) sound = Sound.sound(Key.key(soundNames[0]), Sound.Source.MUSIC, 1f, 1f);
        else if(soundNames.length == 2) sound = Sound.sound(Key.key(soundNames[0], soundNames[1]), Sound.Source.MUSIC, 1f, 1f);
        if(sound == null){
            commandSender.sendMessage("Could not find sound: "+strings[1]);
            return true;
        }

        player.playSound(sound, Sound.Emitter.self());
        return true;
    }
}
