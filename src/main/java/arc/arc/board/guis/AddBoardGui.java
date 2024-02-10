package arc.arc.board.guis;

import arc.arc.ARC;
import arc.arc.configs.BoardConfig;
import arc.arc.configs.Config;
import arc.arc.TitleInput;
import arc.arc.board.Board;
import arc.arc.board.BoardEntry;
import arc.arc.board.ItemIcon;
import arc.arc.util.HeadUtil;
import arc.arc.util.ItemStackBuilder;
import arc.arc.util.TextUtil;
import com.github.stefvanschie.inventoryframework.gui.GuiItem;
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui;
import com.github.stefvanschie.inventoryframework.pane.OutlinePane;
import com.github.stefvanschie.inventoryframework.pane.Pane;
import com.github.stefvanschie.inventoryframework.pane.StaticPane;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;
import java.util.UUID;

public class AddBoardGui extends ChestGui implements Inputable {

    public String shortName = null;
    public String description = null;
    ItemIcon icon;
    BoardEntry.Type type;


    Player player;
    GuiItem descriptionItem;
    GuiItem shortNameItem;
    GuiItem iconItem;

    public AddBoardGui(Player player) {
        super(2, "Создание публикации");
        this.player = player;
        icon = ItemIcon.of(player.getUniqueId());
        type = BoardEntry.Type.INFO;
        StaticPane pane = new StaticPane(0, 0, 9, 2);
        setupBackground();

        pane.addItem(shortNameItem(), 1, 0);
        pane.addItem(descriptionItem(), 3, 0);
        pane.addItem(iconItem(), 5, 0);
        pane.addItem(getType(), 7, 0);
        pane.addItem(publish(), 8, 1);
        pane.addItem(backItem(), 0, 1);
        this.addPane(pane);
    }

    public void proceed() {
        shortNameItem.setItem(shortNameItem().getItem());
        descriptionItem.setItem(shortNameItem().getItem());

        this.update();
        this.show(player);
    }

    @Override
    public boolean satisfy(String input, int id) {
        if (id == 0) return input.length() <= Config.tldLength;
        return true;
    }

    @Override
    public Component denyMessage(String input, int id) {
        if (id == 0)
            return TextUtil.strip(Component.text("Длина не может превышать" + Config.tldLength + "символов!", NamedTextColor.RED));
        return null;
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
                .flags(ItemFlag.HIDE_ITEM_SPECIFICS, ItemFlag.HIDE_ATTRIBUTES);

        if (shortName == null) {
            builder.display(BoardConfig.getString("add-menu.empty.short-name.display"))
                    .appendLore(BoardConfig.getStringList("add-menu.empty.short-name.lore"));
        } else {
            builder.display(BoardConfig.getString("add-menu.full.short-name.display")
                            .replace("<short_name>", shortName), ItemStackBuilder.Deserializer.LEGACY)
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
                .flags(ItemFlag.HIDE_ITEM_SPECIFICS, ItemFlag.HIDE_ATTRIBUTES);

        if (description == null) {
            builder.display(BoardConfig.getString("add-menu.empty.description.display"))
                    .appendLore(BoardConfig.getStringList("add-menu.empty.description.lore"));
        } else {
            builder.display(BoardConfig.getString("add-menu.full.description.display")
                            .replace("<short_name>", description), ItemStackBuilder.Deserializer.LEGACY)
                    .appendLore(BoardConfig.getStringList("add-menu.full.description.lore"));
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
        if(icon != null){
            builder = new ItemStackBuilder(icon.stack())
                    .display(BoardConfig.getString("add-menu.full.icon.display"))
                    .lore(BoardConfig.getStringList("add-menu.full.icon.lore"))
        }else{
            builder = new ItemStackBuilder(Material.PLAYER_HEAD)
                    .skull(player.getUniqueId())
                    .display(BoardConfig.getString("add-menu.empty.icon.display"))
                    .lore(BoardConfig.getStringList("add-menu.empty.icon.lore"));
        }

        return builder.toGuiItemBuilder()
                .clickEvent(click -> {
                    click.setCancelled(true);
                    ItemStack st = click.getCursor();
                    if (st.getType() == Material.AIR) icon = ItemIcon.of(player.getUniqueId());
                    else icon = ItemIcon.of(st.getType(), st.getItemMeta().getCustomModelData());
                    updateIcon();
                }).build();
    }

    private void updateIcon(){
        iconItem.setItem(iconItem().getItem());
        this.update();
    }

    private GuiItem getType() {
        ItemStack stack = new ItemStack(Material.ENDER_PEARL);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(TextUtil.strip(type.name));
        meta.lore(List.of(TextUtil.strip(Component.text("Нажмите, чтобы переключить", NamedTextColor.GRAY))));
        stack.setItemMeta(meta);

        return new GuiItem(stack, inventoryClickEvent -> {
            inventoryClickEvent.setCancelled(true);
            if (type == BoardEntry.Type.BUY) type = BoardEntry.Type.INFO;
            else if (type == BoardEntry.Type.INFO) type = BoardEntry.Type.LOOKING_FOR;
            else if (type == BoardEntry.Type.LOOKING_FOR) type = BoardEntry.Type.SELL;
            else if (type == BoardEntry.Type.SELL) type = BoardEntry.Type.BUY;

            meta.displayName(type.name);
            stack.setItemMeta(meta);

            this.update();
        });
    }

    private GuiItem publish() {
        ItemStack stack = new ItemStack(Material.GREEN_STAINED_GLASS_PANE);
        ItemMeta meta = stack.getItemMeta();
        meta.setCustomModelData(11007);
        meta.displayName(TextUtil.strip(Component.text("Опубликовать", NamedTextColor.GRAY)));
        stack.setItemMeta(meta);
        return new GuiItem(stack, inventoryClickEvent -> {
            inventoryClickEvent.setCancelled(true);

            if (!ARC.getEcon().has(player, Config.boardCost)) {
                inventoryClickEvent.getWhoClicked().closeInventory();
                TextUtil.noMoneyMessage(player, Config.boardCost);
            }

            if (type == null || icon == null || shortName == null) return;
            BoardEntry boardEntry = new BoardEntry(this.type, player.getName(), player.getUniqueId(), icon, description, shortName,
                    System.currentTimeMillis(), System.currentTimeMillis(), UUID.randomUUID());
            Board.instance().addBoardEntry(boardEntry);
            inventoryClickEvent.getWhoClicked().closeInventory();
        });
    }

    private void setupBackground() {
        OutlinePane pane = new OutlinePane(0, 0, 9, 2);
        ItemStack bgItem = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = bgItem.getItemMeta();
        meta.setCustomModelData(11000);
        meta.displayName(Component.text(" "));
        bgItem.setItemMeta(meta);
        pane.addItem(new GuiItem(bgItem, inventoryClickEvent -> {
            inventoryClickEvent.setCancelled(true);
        }));
        pane.setRepeat(true);
        pane.setPriority(Pane.Priority.LOWEST);
        this.addPane(pane);
    }
}
