package me.dartanman.duels.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;

public final class Messages {
    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final String PREFIX = "<#c42323>⚔ <#666666>• ";

    private Messages() {}

    public static Component component(String message) {
        return MINI.deserialize(PREFIX + message);
    }

    public static void send(CommandSender sender, String message) {
        sender.sendMessage(component(message));
    }
}
