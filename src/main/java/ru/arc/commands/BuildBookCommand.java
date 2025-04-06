package ru.arc.commands;

import ru.arc.ARC;
import ru.arc.autobuild.Building;
import ru.arc.autobuild.BuildingManager;
import ru.arc.configs.Config;
import ru.arc.configs.ConfigManager;
import ru.arc.util.TextUtil;
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
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.List;

import static ru.arc.util.TextUtil.strip;

public class BuildBookCommand implements CommandExecutor, TabCompleter {

    Config config = ConfigManager.of(ARC.plugin.getDataPath(), "auto-build.yml");

    @Override
    public boolean onCommand(@NotNull CommandSender commandSender, @NotNull Command command, @NotNull String s, @NotNull String[] strings) {
        if (!(commandSender instanceof Player player)) {
            commandSender.sendMessage("You cant do it from console!");
            return true;
        }

        if (!commandSender.hasPermission("arc.command.buildbook")) {
            commandSender.sendMessage(TextUtil.noPermissions());
            return true;
        }

        if (strings.length < 2) {
            commandSender.sendMessage("Invalid arguments! /buildbook <building> [model-id] [rotation] [y-offset] [name]");
            return true;
        }

        String fileName = strings[0];
        Building building = BuildingManager.getBuilding(fileName);
        if (building == null) {
            commandSender.sendMessage("No such building with name: " + fileName);
            return true;
        }
        int modelId = Integer.parseInt(strings[1]);
        StringBuilder builder = new StringBuilder();
        for (int i = 4; i < strings.length; i++) {
            builder.append(strings[i]).append(" ");
        }
        if (builder.isEmpty()) {
            builder.append(config.string("build-book.default-name", "Дом"));
        }
        String name = "&7" + builder.toString().trim();

        ItemStack stack = new ItemStack(Material.BOOK);

        NBTItem nbtItem = new NBTItem(stack);
        nbtItem.setString("arc:building_key", fileName);

        if (strings.length >= 3) {
            String rotation = strings[2];
            nbtItem.setString("arc:rotation", rotation);
        }

        if (strings.length >= 4) {
            String yOff = strings[3];
            nbtItem.setString("arc:y_offset", yOff);
        }

        nbtItem.applyNBT(stack);
        ItemMeta meta = stack.getItemMeta();
        Component longName = longName(name);

        Component display = config.componentDef("build-book.display-name",
                "     <gray>\uD83D\uDEE0 <gold>Книга строительства <gray>\uD83D\uDEE0", TagResolver.builder()
                        .tag("building", Tag.inserting(Component.text(fileName, NamedTextColor.GOLD)))
                        .build());
        List<Component> lore = config.stringList("build-book.lore", List.of(
                        " ",
                        "      <gray>Эта книга позволяет вам",
                        "    <gray>возвести готовое строение    ",
                        " ",
                        "<long_name>",
                        " ",
                        "        <gray>Нажмите <green>ПКМ <gray>по земле"
                )).stream()
                .map(str -> MiniMessage.miniMessage().deserialize(str, TagResolver.builder()
                        .tag("name", Tag.inserting(LegacyComponentSerializer.legacyAmpersand().deserialize(name)))
                        .tag("long_name", Tag.inserting(longName))
                        .build()))
                .map(TextUtil::strip)
                .toList();

        meta.displayName(display);
        meta.lore(lore);

        if (modelId != 0) meta.setCustomModelData(modelId);
        stack.setItemMeta(meta);
        player.getInventory().addItem(stack);
        Component message = config.componentDef("build-book.received", "<green>Вы получили книгу для <building>", TagResolver.builder()
                .tag("building", Tag.inserting(Component.text(fileName, NamedTextColor.GOLD)))
                .build());
        commandSender.sendMessage(message);

        return true;
    }

    private Component longName(String name) {
        TextComponent bName = LegacyComponentSerializer.legacyAmpersand().deserialize(name);
        int length = bName.content().length();
        int len2 = Math.max(0, (37 - length - 4) / 2);
        if (length < 9) len2 += 1;
        if (length < 13) len2 += 1;
        return strip(
                Component.text(String.join("", Collections.nCopies(len2, " ")))
                        .append(Component.text("\uD83D\uDEE0 ", NamedTextColor.GREEN))
                        .append(bName)
                        .append(Component.text(" \uD83D\uDEE0", NamedTextColor.GREEN))
        );
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender commandSender, @NotNull Command command, @NotNull String s, @NotNull String[] strings) {
        if (strings.length == 1) {
            return BuildingManager.getBuildings().stream().map(Building::getFileName).toList();
        }

        if (strings.length == 2) {
            return List.of("[model-id]");
        }


        if (strings.length == 3) {
            return List.of("0", "90", "180", "270");
        }

        if (strings.length == 4) {
            return List.of("[y-offset]");
        }

        if (strings.length == 5) {
            return List.of("[name]");
        }

        return null;
    }
}
