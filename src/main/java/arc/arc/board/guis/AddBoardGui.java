package arc.arc.board.guis;

import arc.arc.ARC;
import arc.arc.configs.BoardConfig;
import arc.arc.configs.BuildingConfig;
import arc.arc.configs.Config;
import arc.arc.TitleInput;
import arc.arc.board.Board;
import arc.arc.board.BoardEntry;
import arc.arc.board.ItemIcon;
import arc.arc.util.GuiUtils;
import arc.arc.util.ItemStackBuilder;
import arc.arc.util.TextUtil;
import com.github.stefvanschie.inventoryframework.adventuresupport.TextHolder;
import com.github.stefvanschie.inventoryframework.gui.GuiItem;
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui;
import com.github.stefvanschie.inventoryframework.pane.OutlinePane;
import com.github.stefvanschie.inventoryframework.pane.Pane;
import com.github.stefvanschie.inventoryframework.pane.StaticPane;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitScheduler;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static arc.arc.util.TextUtil.strip;

public class AddBoardGui extends ChestGui implements Inputable {

    public String shortName = null;
    public String description = null;
    ItemIcon icon;
    BoardEntry.Type type;
    BoardEntry entry;


    Player player;
    GuiItem descriptionItem;
    GuiItem shortNameItem;
    GuiItem iconItem;
    GuiItem typeItem;
    GuiItem publishItem;
    GuiItem deleteItem;
    boolean confirmDelete = false;

    public AddBoardGui(Player player) {
        this(player, null);
    }

    public AddBoardGui(Player player, BoardEntry entry) {
        super(2, TextHolder.deserialize(entry == null ? BoardConfig.createEntryGuiName : BoardConfig.editEntryGuiName));

        this.player = player;
        icon = ItemIcon.of(player.getUniqueId());
        type = BoardEntry.Type.INFO;
        this.entry = entry;

        StaticPane pane = new StaticPane(0, 0, 9, 2);
        setupBackground();

        if (entry != null) {
            this.shortName = entry.getTitle();
            this.description = entry.getText();
            this.icon = entry.getIcon();
            this.type = entry.getType();
            this.publishItem = editItem();
            this.deleteItem = deleteItem();
        } else {
            this.publishItem = publishItem();
        }

        this.descriptionItem = descriptionItem();
        this.shortNameItem = shortNameItem();
        this.iconItem = iconItem();
        this.typeItem = typeItem();


        pane.addItem(shortNameItem, 1, 0);
        pane.addItem(descriptionItem, 3, 0);
        pane.addItem(iconItem, 5, 0);
        pane.addItem(typeItem, 7, 0);

        pane.addItem(publishItem, 8, 1);
        if (deleteItem != null) pane.addItem(deleteItem, 4, 1);
        pane.addItem(backItem(), 0, 1);

        this.addPane(pane);
    }

    public void proceed() {
        shortNameItem.setItem(shortNameItem().getItem());
        descriptionItem.setItem(descriptionItem().getItem());

        this.update();
        this.show(player);
    }

    @Override
    public boolean satisfy(String input, int id) {
        if (id == 0) return input.length() <= BoardConfig.shortNameLength;
        return true;
    }

    @Override
    public Component denyMessage(String input, int id) {
        //if (id == 0)
        return TextUtil.mm("<red>Длина не может превышать " + BoardConfig.shortNameLength + " символов!");
        //return null;
    }

    @Override
    public Component startMessage(int id) {
        if (id == 0) return TextUtil.mm("<gray>> <green>Введите короткое название");
        else return TextUtil.mm("<gray>> <green>Введите комментарий");
    }

    @Override
    public void setParameter(int n, String s) {
        if (n == 0) this.shortName = s;
        else if (n == 1) this.description = s;
    }

    private GuiItem backItem() {
        return new ItemStackBuilder(Material.BLUE_STAINED_GLASS_PANE)
                .display("<gray>Назад")
                .modelData(11013)
                .toGuiItemBuilder()
                .clickEvent(click -> {
                    click.setCancelled(true);
                    new BoardGui(player).show(click.getWhoClicked());
                }).build();
    }

    private GuiItem shortNameItem() {
        ItemStackBuilder builder = new ItemStackBuilder(Material.FLOWER_BANNER_PATTERN)
                .flags(ItemFlag.HIDE_ITEM_SPECIFICS, ItemFlag.HIDE_ATTRIBUTES)
                .tagResolver(TagResolver.builder()
                        .resolver(TagResolver.resolver("short_name", Tag.inserting(Component.text(shortName == null ? "Нету" : shortName))))
                        .resolver(TagResolver.resolver("short_name_length", Tag.inserting(Component.text(BoardConfig.shortNameLength + ""))))
                        .build());

        if (shortName == null) {
            builder.display(BoardConfig.getString("add-menu.empty.short-name.display"))
                    .appendLore(BoardConfig.getStringList("add-menu.empty.short-name.lore"));
        } else {
            builder.display(BoardConfig.getString("add-menu.full.short-name.display").replace("<short_name>", shortName),
                            ItemStackBuilder.Deserializer.LEGACY)
                    .appendLore(BoardConfig.getStringList("add-menu.full.short-name.lore"));
        }
        return builder.toGuiItemBuilder()
                .clickEvent(click -> {
                    click.setCancelled(true);
                    new TitleInput(player, this, 0);
                    click.getWhoClicked().closeInventory();
                }).build();
    }

    private GuiItem descriptionItem() {
        ItemStackBuilder builder = new ItemStackBuilder(Material.PAPER)
                .flags(ItemFlag.HIDE_ITEM_SPECIFICS, ItemFlag.HIDE_ATTRIBUTES)
                .tagResolver(TagResolver.resolver("description", Tag.inserting(Component.text(description == null ? "Нету" : description))));

        if (description == null) {
            builder.display(BoardConfig.getString("add-menu.empty.description.display"))
                    .appendLore(BoardConfig.getStringList("add-menu.empty.description.lore"));
        } else {
            List<Component> lore = new ArrayList<>();
            for (String s : BoardConfig.getStringList("add-menu.full.description.lore")) {
                if (s.contains("<description>")) {
                    lore.addAll(BoardEntry.description(TagResolver.standard(), description));
                    continue;
                }
                lore.add(MiniMessage.miniMessage().deserialize(s));
            }
            builder.display(BoardConfig.getString("add-menu.full.description.display"))
                    .componentLore(lore);
        }
        return builder.toGuiItemBuilder()
                .clickEvent(click -> {
                    click.setCancelled(true);
                    new TitleInput(player, this, 1);
                    click.getWhoClicked().closeInventory();
                }).build();
    }

    private GuiItem iconItem() {

        ItemStackBuilder builder;
        if (icon != null) {
            builder = new ItemStackBuilder(icon.stack())
                    .display(BoardConfig.getString("add-menu.full.icon.display"))
                    .lore(BoardConfig.getStringList("add-menu.full.icon.lore"));
        } else {
            builder = new ItemStackBuilder(Material.PLAYER_HEAD)
                    .skull(player.getUniqueId())
                    .display(BoardConfig.getString("add-menu.empty.icon.display"))
                    .lore(BoardConfig.getStringList("add-menu.empty.icon.lore"));
        }

        return builder.toGuiItemBuilder()
                .clickEvent(click -> {
                    click.setCancelled(true);
                    ItemStack st = click.getCursor();
                    //System.out.println("Hand item: " + st.getType());
                    if (st.getType() == Material.AIR) icon = ItemIcon.of(player.getUniqueId());
                    else icon = ItemIcon.of(st);
                    updateIcon();
                }).build();
    }

    private void updateIcon() {
        if (iconItem == null) return;
        iconItem.setItem(iconItem().getItem());
        this.update();
    }

    private GuiItem typeItem() {
        return new ItemStackBuilder(type.icon)
                .tagResolver(TagResolver.resolver("type", Tag.inserting(type.name)))
                .display(BoardConfig.getString("add-menu.full.type.display"))
                .lore(BoardConfig.getStringList("add-menu.full.type.lore"))
                .toGuiItemBuilder()
                .clickEvent(click -> {
                    click.setCancelled(true);
                    if (type == BoardEntry.Type.BUY) type = BoardEntry.Type.INFO;
                    else if (type == BoardEntry.Type.INFO) type = BoardEntry.Type.LOOKING_FOR;
                    else if (type == BoardEntry.Type.LOOKING_FOR) type = BoardEntry.Type.SELL;
                    else if (type == BoardEntry.Type.SELL) type = BoardEntry.Type.BUY;

                    updateType();
                }).build();
    }

    private void updateType() {
        if (typeItem == null) return;
        typeItem.setItem(typeItem().getItem());
        this.update();
    }

    private GuiItem publishItem() {
        return new ItemStackBuilder(Material.GREEN_STAINED_GLASS_PANE)
                .modelData(11007)
                .display(BoardConfig.getString("add-menu.publish-display"))
                .lore(BoardConfig.getStringList("add-menu.publish-lore"))
                .toGuiItemBuilder()
                .clickEvent(click -> {
                    click.setCancelled(true);
                    try {
                        if (!ARC.getEcon().has(player, BoardConfig.editCost)) {
                            notEnoughMoneyDisplay(publishItem);
                            return;
                        }

                        if (shortName == null) {
                            List<Component> lore = new ArrayList<>();
                            if (type == null) lore.add(Component.text("Тип не установлен", NamedTextColor.GRAY));
                            if (icon == null) lore.add(Component.text("Иконка не установлена", NamedTextColor.GRAY));
                            if (shortName == null)
                                lore.add(Component.text("Короткое название не установлено", NamedTextColor.GRAY));

                            GuiUtils.temporaryChange(publishItem.getItem(),
                                    Component.text("Остались незаполненные поля", NamedTextColor.RED),
                                    lore, 60, this::update);

                            update();
                            return;
                        }

                        if (!takeMoney(BoardConfig.publishCost)) return;

                        BoardEntry boardEntry = new BoardEntry(this.type, player.getName(), player.getUniqueId(), icon, description, shortName,
                                System.currentTimeMillis(), System.currentTimeMillis(), UUID.randomUUID());
                        Board.instance().addBoardEntry(boardEntry, true);
                        //System.out.println("Adding board entry!");
                        player.sendMessage(TextUtil.mm(BoardConfig.getString("add-menu.published-successfully")));

                    } catch (Exception e) {
                        e.printStackTrace();
                        player.sendMessage(TextUtil.error());
                        click.getWhoClicked().closeInventory();
                    }
                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            new BoardGui(player).show(player);
                        }
                    }.runTaskLater(ARC.plugin, 1L);
                }).build();
    }

    private GuiItem editItem() {
        return new ItemStackBuilder(Material.GREEN_STAINED_GLASS_PANE)
                .modelData(11007)
                .display(BoardConfig.getString("add-menu.edit-display"))
                .lore(BoardConfig.getStringList("add-menu.edit-lore"))
                .tagResolver(TagResolver.resolver("cost",
                        Tag.inserting(strip(Component.text(TextUtil.formatAmount(BoardConfig.editCost))))))
                .toGuiItemBuilder()
                .clickEvent(click -> {
                    click.setCancelled(true);
                    try {
                        if (!ARC.getEcon().has(player, BoardConfig.editCost)) {
                            notEnoughMoneyDisplay(publishItem);
                            return;
                        }

                        if (shortName == null) {
                            List<Component> lore = new ArrayList<>();
                            if (type == null) lore.add(Component.text("Тип не установлен", NamedTextColor.GRAY));
                            if (icon == null) lore.add(Component.text("Иконка не установлена", NamedTextColor.GRAY));
                            if (shortName == null)
                                lore.add(Component.text("Короткое название не установлено", NamedTextColor.GRAY));

                            GuiUtils.temporaryChange(publishItem.getItem(),
                                    Component.text("Остались незаполненные поля", NamedTextColor.RED),
                                    lore, 60, this::update);

                            update();
                            return;
                        }

                        if (!takeMoney(BoardConfig.editCost)) return;

                        entry.setText(description);
                        entry.setTitle(shortName);
                        entry.setIcon(icon);
                        entry.setType(type);

                        Board.instance().updateCache(entry.entryUuid);
                        Board.instance().saveBoardEntry(entry.entryUuid);
                        player.sendMessage(TextUtil.mm(BoardConfig.getString("add-menu.edited-successfully")));

                        Bukkit.getScheduler().runTaskLater(ARC.plugin, () -> new BoardGui(player).show(player), 1L);
                    } catch (Exception e) {
                        e.printStackTrace();
                        player.sendMessage(TextUtil.error());
                        click.getWhoClicked().closeInventory();
                    }
                }).build();
    }

    private boolean takeMoney(double cost) {
        EconomyResponse response = ARC.getEcon().withdrawPlayer(player, cost);
        return response.transactionSuccess();
    }

    private void notEnoughMoneyDisplay(GuiItem guiItem) {
        GuiUtils.temporaryChange(guiItem.getItem(),
                MiniMessage.miniMessage().deserialize(BoardConfig.getString("not-enough-money")),
                null, 60L, this::update);
        update();
    }

    private GuiItem deleteItem() {
        return new ItemStackBuilder(Material.RED_STAINED_GLASS_PANE)
                .modelData(11002)
                .display(BoardConfig.getString("add-menu.delete-display"))
                .lore(BoardConfig.getStringList("add-menu.delete-lore"))
                .toGuiItemBuilder()
                .clickEvent(click -> {
                    click.setCancelled(true);

                    if (confirmDelete) {
                        //.out.println("Deleting board entry from gui");
                        Board.instance().deleteBoard(entry.entryUuid, true);
                        new BoardGui(player).show(player);
                    } else {
                        confirmDelete = true;
                        GuiUtils.temporaryChange(deleteItem.getItem(),
                                MiniMessage.miniMessage().deserialize(BoardConfig.getString("add-menu.confirm-delete")),
                                null, 100L, () -> {
                                    this.update();
                                    this.confirmDelete = false;
                                });
                        update();
                    }
                }).build();
    }

    private void setupBackground() {
        OutlinePane pane = new OutlinePane(0, 0, 9, 2);
        pane.addItem(GuiUtils.background());
        pane.setRepeat(true);
        pane.setPriority(Pane.Priority.LOWEST);
        this.addPane(pane);
    }
}
