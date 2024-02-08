package arc.arc.commands;

import arc.arc.autobuild.Building;
import arc.arc.autobuild.BuildingManager;
import arc.arc.configs.BuildingConfig;
import arc.arc.util.TextUtil;
import de.tr7zw.changeme.nbtapi.NBTItem;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static arc.arc.util.TextUtil.strip;

public class BuildBookCommand implements CommandExecutor {
    @Override
    public boolean onCommand(@NotNull CommandSender commandSender, @NotNull Command command, @NotNull String s, @NotNull String[] strings) {
        if (!(commandSender instanceof Player player)) {
            commandSender.sendMessage("You cant do it from console!");
            return true;
        }

        if (strings.length < 3) {
            commandSender.sendMessage("Need to specify filename as 1st argument");
            return true;
        }

        String fileName = strings[0];
        Building building = BuildingManager.getBuilding(fileName);
        if (building == null) {
            commandSender.sendMessage("No such building with name: " + fileName);
            return true;
        }
        int modelId = strings.length >= 2 ? Integer.parseInt(strings[1]) : 0;
        StringBuilder builder = new StringBuilder();
        for (int i = 2; i < strings.length; i++) {
            builder.append(strings[i]).append(" ");
        }
        String name = "&7"+builder.toString().trim();

        ItemStack stack = new ItemStack(Material.BOOK);
        NBTItem nbtItem = new NBTItem(stack);
        nbtItem.setString("arc:building_key", fileName);
        nbtItem.applyNBT(stack);
        ItemMeta meta = stack.getItemMeta();

        Component bName = LegacyComponentSerializer.legacyAmpersand().deserialize(name);
        int length = ((TextComponent) bName).content().length();
        int len2 = Math.max(0, (37 - length - 4) / 2);
        Component longName = strip(
                Component.text(String.join("", Collections.nCopies(len2, " ")))
                        .append(Component.text("\uD83D\uDEE0 ", NamedTextColor.GREEN))
                        .append(bName)
                        .append(Component.text(" \uD83D\uDEE0", NamedTextColor.GREEN))
        );

        Component display = strip(MiniMessage.miniMessage().deserialize(BuildingConfig.bookDisplay));
        List<Component> lore = BuildingConfig.bookLore.stream()
                .map(str -> MiniMessage.miniMessage().deserialize(str, TagResolver.builder()
                        .tag("name", Tag.inserting(LegacyComponentSerializer.legacyAmpersand().deserialize(name)))
                        .tag("long_name", Tag.inserting(longName))
                        .build()))
                .map(TextUtil::strip)
                .toList();

        meta.displayName(display);
        meta.lore(lore);

        if (modelId != 0) {
            meta.setCustomModelData(modelId);
        }
        stack.setItemMeta(meta);

        player.getInventory().addItem(stack);
        commandSender.sendMessage("You received the book for " + fileName);

        return true;
    }
}
