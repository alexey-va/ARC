package ru.arc.common.treasure.gui;

import ru.arc.ARC;
import ru.arc.TitleInput;
import ru.arc.board.guis.Inputable;
import ru.arc.configs.Config;
import ru.arc.configs.ConfigManager;
import ru.arc.common.treasure.Treasure;
import ru.arc.common.treasure.TreasurePool;
import ru.arc.common.treasure.impl.GaussData;
import ru.arc.common.treasure.impl.TreasureCommand;
import ru.arc.common.treasure.impl.TreasureItem;
import ru.arc.util.GuiUtils;
import ru.arc.util.ItemStackBuilder;
import ru.arc.util.TextUtil;
import com.github.stefvanschie.inventoryframework.adventuresupport.TextHolder;
import com.github.stefvanschie.inventoryframework.gui.GuiItem;
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui;
import com.github.stefvanschie.inventoryframework.pane.OutlinePane;
import com.github.stefvanschie.inventoryframework.pane.Pane;
import com.github.stefvanschie.inventoryframework.pane.StaticPane;
import lombok.extern.slf4j.Slf4j;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.stream.Stream;

@Slf4j
public class TreasureGui extends ChestGui implements Inputable {

    private static final int rows = 2;
    Player player;
    TreasurePool treasurePool;
    Treasure treasure;
    private static final Config config = ConfigManager.of(ARC.plugin.getDataPath(), "treasures.yml");
    boolean destroyed = false;

    public TreasureGui(Player player, TreasurePool treasurePool, Treasure treasure) {
        super(rows, TextHolder.deserialize(TextUtil.toLegacy(
                config.string("treasure.title").replace("%pool%", treasurePool.getId())
        )));

        this.player = player;
        this.treasurePool = treasurePool;
        this.treasure = treasure;

        setupBackground();
        setupNavigation();
        setupButtons();
    }

    private void setupButtons() {
        StaticPane staticPane = new StaticPane(0, 0, 9, rows - 1);
        GuiItem weightGuiItem = new ItemStackBuilder(Material.CHEST)
                .display(config.string("treasure.weight")
                        .replace("%weight%", String.valueOf(treasure.getWeight())))
                .lore(config.stringList("treasure.weight-lore")
                        .stream().map(s -> s.replace("%weight%", String.valueOf(treasure.getWeight()))).toList())
                .toGuiItemBuilder()
                .clickEvent(click -> {
                    click.setCancelled(true);
                    new TitleInput(player, this, 0);
                    click.getWhoClicked().closeInventory();
                })
                .build();
        staticPane.addItem(weightGuiItem, 1, 0);

        if (treasure instanceof TreasureCommand treasureCommand) {
            GuiItem commandGuiItem = new ItemStackBuilder(Material.COMMAND_BLOCK)
                    .display(config.string("treasure.command")
                            .replace("%command%", treasureCommand.getCommands().isEmpty() ? "" : treasureCommand.getCommands().getFirst()))
                    .lore(config.stringList("treasure.command-lore")
                            .stream().flatMap(s -> {
                                if (s.contains("%command%")) {
                                    return treasureCommand.getCommands().stream().map(s1 -> s.replace("%command%", s1));
                                } else {
                                    return Stream.of(s);
                                }
                            }).toList())
                    .toGuiItemBuilder()
                    .clickEvent(click -> {
                        click.setCancelled(true);
                        new TitleInput(player, this, 1);
                        click.getWhoClicked().closeInventory();
                    })
                    .build();
            staticPane.addItem(commandGuiItem, 3, 0);
        }

        if (treasure instanceof TreasureItem treasureItem) {
            int amount = treasureItem.getMinAmount();
            GaussData gaussData = treasureItem.getGaussData();

            GuiItem amountGuiItem = new ItemStackBuilder(Material.PAPER)
                    .display(config.string("treasure.amount")
                            .replace("%amount%", String.valueOf(amount)))
                    .lore(config.stringList("treasure.amount-lore")
                            .stream().map(s -> s.replace("%amount%", String.valueOf(amount))).toList())
                    .toGuiItemBuilder()
                    .clickEvent(click -> {
                        click.setCancelled(true);
                        new TitleInput(player, this, 2);
                        click.getWhoClicked().closeInventory();
                    })
                    .build();
            staticPane.addItem(amountGuiItem, 4, 0);

            GuiItem gaussGuiItem = new ItemStackBuilder(Material.ENDER_PEARL)
                    .display(config.string("treasure.gauss")
                            .replace("%gauss%", gaussData == null ? "none" : gaussData.toString()))
                    .lore(config.stringList("treasure.gauss-lore")
                            .stream().map(s -> s.replace("%gauss%", gaussData == null ? "none" : gaussData.toString())).toList())
                    .toGuiItemBuilder()
                    .clickEvent(click -> {
                        click.setCancelled(true);
                        new TitleInput(player, this, 3);
                        click.getWhoClicked().closeInventory();
                    })
                    .build();
            staticPane.addItem(gaussGuiItem, 5, 0);
        }

        GuiItem setMessagesGuiItem = new ItemStackBuilder(Material.WRITABLE_BOOK)
                .display(config.string("treasure.messages", "Сообщение"))
                .lore(config.stringList("treasure.messages-lore").stream()
                        .flatMap(s -> {
                            if (s.contains("%message%")) {
                                List<String> strings = TextUtil.splitLoreString(
                                        treasure.message().orElse("Нет"),
                                        80, 2);
                                if (strings.isEmpty()) return Stream.of(s);
                                List<String> result = new ArrayList<>();
                                result.add(s.replace("%message%", strings.getFirst()));
                                result.addAll(strings.subList(1, strings.size()));
                                return result.stream();
                            } else {
                                return Stream.of(s);
                            }
                        })
                        .toList())
                .toGuiItemBuilder()
                .clickEvent(click -> {
                    click.setCancelled(true);
                    new TitleInput(player, this, 4);
                    click.getWhoClicked().closeInventory();
                })
                .build();
        staticPane.addItem(setMessagesGuiItem, 2, 0);

        GuiItem announceGuiItem = new ItemStackBuilder(Material.BELL)
                .display(config.string("treasure.announce", "<gold>Глобальное сообщение"))
                .lore(config.stringList("treasure.announce-lore", List.of("<green>Включено: %announce%"))
                        .stream().map(s -> s.replace("%announce%", String.valueOf(treasure.announce())))
                        .toList())
                .toGuiItemBuilder()
                .clickEvent(click -> {
                    click.setCancelled(true);
                    treasure.announce(!treasure.announce());
                    treasurePool.setDirty(true);
                    GuiUtils.constructAndShowAsync(() -> new TreasureGui(player, treasurePool, treasure), player);
                })
                .build();
        staticPane.addItem(announceGuiItem, 6, 0);

        GuiItem announceMessageGuiItem = new ItemStackBuilder(Material.BOOK)
                .display(config.string("treasure.global-message", "<gold>Глобальное сообщение"))
                .lore(config.stringList("treasure.global-message-lore")
                        .stream().flatMap(s -> {
                            if (s.contains("%message%")) {
                                List<String> strings = TextUtil.splitLoreString(
                                        treasure.globalMessage().orElse("Нет"),
                                        80, 2);
                                if (strings.isEmpty()) return Stream.of(s);
                                List<String> result = new ArrayList<>();
                                result.add(s.replace("%message%", strings.getFirst()));
                                result.addAll(strings.subList(1, strings.size()));
                                return result.stream();
                            } else {
                                return Stream.of(s);
                            }
                        })
                        .toList())
                .toGuiItemBuilder()
                .clickEvent(click -> {
                    click.setCancelled(true);
                    new TitleInput(player, this, 5);
                    click.getWhoClicked().closeInventory();
                })
                .build();
        staticPane.addItem(announceMessageGuiItem, 7, 0);

        GuiItem deleteGuiItem = new ItemStackBuilder(Material.BARRIER)
                .display(config.string("treasure.delete"))
                .lore(config.stringList("treasure.delete-lore"))
                .toGuiItemBuilder()
                .clickEvent(click -> {
                    click.setCancelled(true);
                    treasurePool.remove(treasure);
                    destroyed = true;
                    GuiUtils.constructAndShowAsync(() -> new PoolGui(player, treasurePool), player);
                })
                .build();
        staticPane.addItem(deleteGuiItem, 8, 0);

        this.addPane(staticPane);
    }


    private void setupNavigation() {
        StaticPane nav = new StaticPane(0, rows - 1, 9, 1);
        nav.addItem(back(), 0, 0);
        this.addPane(nav);
    }

    private GuiItem back() {
        return new ItemStackBuilder(Material.BLUE_STAINED_GLASS_PANE)
                .modelData(11013)
                .display(config.string("main.back"))
                .lore(config.stringList("main.back-lore"))
                .toGuiItemBuilder()
                .clickEvent(click -> {
                    click.setCancelled(true);
                    GuiUtils.constructAndShowAsync(() -> new PoolGui(player, treasurePool), player);
                }).build();
    }

    private void setupBackground() {
        OutlinePane pane = new OutlinePane(0, rows - 1, 9, 1);
        pane.addItem(GuiUtils.background());
        pane.setRepeat(true);
        pane.setPriority(Pane.Priority.LOWEST);
        this.addPane(pane);
    }

    @Override
    public void setParameter(int n, String s) {
        if (n == 0) {
            int newWeight = Integer.parseInt(s);
            treasure.setWeight(newWeight);
            treasurePool.regenerateWeights();
            treasurePool.setDirty(true);
        } else if (n == 1) {
            if (treasure instanceof TreasureCommand treasureCommand) {
                treasureCommand.setCommands(List.of(s));
                treasurePool.setDirty(true);
            } else {
                log.error("Treasure is not a command");
            }
        } else if (n == 2) {
            if (treasure instanceof TreasureItem treasureItem) {
                int newAmount = Integer.parseInt(s);
                treasureItem.setMinAmount(newAmount);
                treasureItem.setMaxAmount(newAmount);
                treasurePool.setDirty(true);
            } else {
                log.error("Treasure is not an item");
            }
        } else if (n == 3) {
            if (treasure instanceof TreasureItem treasureItem) {
                String[] split = s.split(",");
                GaussData gaussData = new GaussData();
                gaussData.setMin(Double.parseDouble(split[0]));
                gaussData.setMax(Double.parseDouble(split[1]));
                gaussData.setMean(Double.parseDouble(split[2]));
                gaussData.setStdDev(Double.parseDouble(split[3]));
                treasureItem.setGaussData(gaussData);
                treasurePool.setDirty(true);
            } else {
                log.error("Treasure is not an item");
            }
        } else if (n == 4) {
            if (s.isEmpty() || s.isBlank() || s.equalsIgnoreCase("нет")) treasure.message(null);
            else treasure.message(s);
            treasurePool.setDirty(true);
        } else if(n == 5) {
            if (s.isEmpty() || s.isBlank() || s.equalsIgnoreCase("нет")) treasure.globalMessage(null);
            else treasure.globalMessage(s);
            treasurePool.setDirty(true);
        }
    }

    @Override
    public void proceed() {
        GuiUtils.constructAndShowAsync(() -> new TreasureGui(player, treasurePool, treasure), player);
    }

    @Override
    public boolean satisfy(String input, int id) {
        if (id == 0) {
            try {
                Integer.parseInt(input);
                return true;
            } catch (NumberFormatException e) {
                return false;
            }
        } else if (id == 1) {
            return treasure instanceof TreasureCommand;
        } else if (id == 2) {
            try {
                Integer.parseInt(input);
                return true;
            } catch (NumberFormatException e) {
                return false;
            }
        } else if (id == 3) {
            String[] split = input.split(",");
            if (split.length != 4) {
                return false;
            }
            try {
                for (String s : split) {
                    Double.parseDouble(s);
                }
                return true;
            } catch (NumberFormatException e) {
                return false;
            }
        } else if (id == 4 || id == 5) {
            return true;
        }
        return false;
    }

    @Override
    public Component denyMessage(String input, int id) {
        return switch (id) {
            case 0 -> TextUtil.mm(config.string("treasure.invalid-weight", "<red>Неверный форма веса"));
            case 1 -> TextUtil.mm(config.string("treasure.invalid-command", "<red>Неверная команда"));
            case 2 -> TextUtil.mm(config.string("treasure.invalid-amount", "<red>Неверное количество"));
            case 3 -> TextUtil.mm(config.string("treasure.invalid-gauss", "<red>Неверные данные Гаусса"));
            case 4,5 -> TextUtil.mm(config.string("treasure.invalid-message", "<red>Неверное сообщение"));
            default -> TextUtil.mm(config.string("treasure.invalid-input", "<red>Неверный ввод"));
        };
    }

    @Override
    public Component startMessage(int id) {
        return switch (id) {
            case 0 -> TextUtil.mm(config.string("treasure.input-weight", "<green>Введите вес (целое число)"));
            case 1 -> TextUtil.mm(config.string("treasure.input-command", "<green>Введите команду (без /)"));
            case 2 -> TextUtil.mm(config.string("treasure.input-amount", "<green>Введите количество (целое число)"));
            case 3 ->
                    TextUtil.mm(config.string("treasure.input-gauss", "<green>Введите данные Гаусса (min,max,mean,stdDev - числа через запятую)"));
            case 4,5 ->
                    TextUtil.mm(config.string("treasure.input-message", "<green>Введите сообщение (\"нет\" - удалить)"));
            default -> TextUtil.mm(config.string("treasure.input-default", "<green>Введите значение"));
        };
    }
}
