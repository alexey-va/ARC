package arc.arc.generic.treasure;

import arc.arc.TitleInput;
import arc.arc.board.guis.Inputable;
import arc.arc.configs.Config;
import arc.arc.util.GuiUtils;
import arc.arc.util.ItemStackBuilder;
import arc.arc.util.TextUtil;
import com.github.stefvanschie.inventoryframework.gui.GuiItem;
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui;
import com.github.stefvanschie.inventoryframework.pane.OutlinePane;
import com.github.stefvanschie.inventoryframework.pane.Pane;
import com.github.stefvanschie.inventoryframework.pane.StaticPane;
import lombok.extern.slf4j.Slf4j;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

@Slf4j
public class TreasureGui extends ChestGui implements Inputable {

    private static int rows = 2;
    Player player;
    TreasurePool treasurePool;
    Treasure treasure;
    Config config;
    boolean destroyed = false;

    public TreasureGui(Config config, Player player, TreasurePool treasurePool, Treasure treasure) {
        super(rows, TextUtil.toLegacy(
                config.string("treasure.title").replace("%pool%", treasurePool.getId())
        ));

        this.config = config;
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
                        .replace("%weight%", String.valueOf(treasure.weight())))
                .lore(config.stringList("treasure.weight-lore")
                        .stream().map(s -> s.replace("%weight%", String.valueOf(treasure.weight()))).toList())
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
                            .replace("%command%", treasureCommand.getCommand()))
                    .lore(config.stringList("treasure.command-lore")
                            .stream().map(s -> s.replace("%command%", treasureCommand.getCommand())).toList())
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
            int amount = treasureItem.getQuantity();
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

        GuiItem deleteGuiItem = new ItemStackBuilder(Material.BARRIER)
                .display(config.string("treasure.delete"))
                .lore(config.stringList("treasure.delete-lore"))
                .toGuiItemBuilder()
                .clickEvent(click -> {
                    click.setCancelled(true);
                    treasurePool.remove(treasure);
                    destroyed = true;
                    GuiUtils.constructAndShowAsync(() -> new PoolGui(config, player, treasurePool), player);
                })
                .build();
        staticPane.addItem(deleteGuiItem, 7, 0);

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
                    player.closeInventory();
                    player.performCommand(config.string("main.back-command"));
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
            treasurePool.setDirty(true);
        } else if (n == 1) {
            if (treasure instanceof TreasureCommand treasureCommand) {
                treasureCommand.setCommand(s);
                treasurePool.setDirty(true);
            } else {
                log.error("Treasure is not a command");
            }
        } else if (n == 2) {
            if (treasure instanceof TreasureItem treasureItem) {
                int newAmount = Integer.parseInt(s);
                treasureItem.setQuantity(newAmount);
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
        }
    }

    @Override
    public void proceed() {
        this.update();
        this.show(player);
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
        }
        return false;
    }

    @Override
    public Component denyMessage(String input, int id) {
        return TextUtil.mm(config.string("treasure.invalid-input"));
    }

    @Override
    public Component startMessage(int id) {
        return TextUtil.mm(config.string("treasure.input"));
    }
}
