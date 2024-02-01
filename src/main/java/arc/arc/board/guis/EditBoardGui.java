package arc.arc.board.guis;

import arc.arc.configs.Config;
import arc.arc.TitleInput;
import arc.arc.board.Board;
import arc.arc.board.BoardEntry;
import arc.arc.board.ItemIcon;
import arc.arc.util.HeadUtil;
import arc.arc.util.TextUtil;
import com.github.stefvanschie.inventoryframework.gui.GuiItem;
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui;
import com.github.stefvanschie.inventoryframework.pane.OutlinePane;
import com.github.stefvanschie.inventoryframework.pane.Pane;
import com.github.stefvanschie.inventoryframework.pane.StaticPane;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

public class EditBoardGui extends ChestGui implements Inputable {
    Player player;
    BoardEntry boardEntry;
    GuiItem textItem;
    GuiItem tldrItem;
    boolean delete = false;
    boolean finalized = false;

    public EditBoardGui(Player player, BoardEntry boardEntry) {
        super(2, "Редактирование публикации");
        this.player = player;
        this.boardEntry = boardEntry;

        this.setOnClose(inventoryCloseEvent -> {
            if(finalized) return;
            finalized = true;
            if(delete) Board.instance().deleteBoard(boardEntry.entryUuid, true);
            else Board.instance().saveBoard(boardEntry);
        });

        StaticPane pane = new StaticPane(0, 0, 9, 2);
        setupBackground();
        tldrItem = getTldr();
        textItem = getText();
        pane.addItem(tldrItem, 1, 0);
        pane.addItem(textItem, 3, 0);
        pane.addItem(getIcon(), 5, 0);
        pane.addItem(getType(), 7, 0);
        pane.addItem(getBack(), 0, 1);
        pane.addItem(deleteItem(), 8, 1);
        this.addPane(pane);
    }

    private void onDelete(){
        if(finalized) return;
        finalized = true;
        if(delete) Board.instance().deleteBoard(boardEntry.entryUuid, true);
        else Board.instance().saveBoard(boardEntry);
    }

    public void proceed() {
        tldrItem.setItem(getTldrStack());
        textItem.setItem(getTextStack());
        update();
        this.show(player);
    }

    @Override
    public boolean ifSatisfy(String input, int id) {
        if(id == 0) return input.length() <= Config.tldLength;
        return true;
    }

    @Override
    public Component denyMessage(String input, int id) {
        if(id == 0) return TextUtil.strip(Component.text("Длина не может превышать" + Config.tldLength + "символов!", NamedTextColor.RED));
        return null;
    }

    @Override
    public Component startMessage(int id) {
        Component text = Component.text("Э бля...");
        if (id == 0) {
            text = TextUtil.strip(Component.text("> ", NamedTextColor.GRAY)
                    .append(Component.text("Введите короткое название", NamedTextColor.GREEN)));
        } else if (id == 1) {
            text = TextUtil.strip(Component.text("> ", NamedTextColor.GRAY)
                    .append(Component.text("Введите комментарий", NamedTextColor.GREEN)));
        }
        return text;
    }

    public void setParameter(int n, String s) {
        if (n == 0) boardEntry.title = s;
        else if (n == 1) boardEntry.text = s;
    }

    private GuiItem deleteItem(){
        ItemStack backItem = new ItemStack(Material.RED_STAINED_GLASS_PANE);
        ItemMeta meta2 = backItem.getItemMeta();
        meta2.setCustomModelData(11002);
        meta2.displayName(Component.text("Удалить", NamedTextColor.RED).decoration(TextDecoration.ITALIC, false));
        backItem.setItemMeta(meta2);
        return new GuiItem(backItem, inventoryClickEvent -> {
            inventoryClickEvent.setCancelled(true);
            delete = true;
            onDelete();
            new BoardGui(player).show(inventoryClickEvent.getWhoClicked());
        });
    }

    private GuiItem getBack() {
        ItemStack backItem = new ItemStack(Material.BLUE_STAINED_GLASS_PANE);
        ItemMeta meta2 = backItem.getItemMeta();
        meta2.displayName(Component.text("Назад", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        meta2.setCustomModelData(11013);
        backItem.setItemMeta(meta2);
        return new GuiItem(backItem, inventoryClickEvent -> {
            inventoryClickEvent.setCancelled(true);
            onDelete();
            new BoardGui(player).show(inventoryClickEvent.getWhoClicked());
        });
    }

    private GuiItem getTldr() {
        ItemStack stack = getTldrStack();
        return new GuiItem(stack, inventoryClickEvent -> {
            inventoryClickEvent.setCancelled(true);
            new TitleInput(player, this, 0);
            inventoryClickEvent.getWhoClicked().closeInventory();
        });
    }

    private ItemStack getTldrStack() {
        ItemStack stack = new ItemStack(Material.FLOWER_BANNER_PATTERN);
        ItemMeta meta = stack.getItemMeta();
        meta.addItemFlags(ItemFlag.HIDE_ITEM_SPECIFICS);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        meta.displayName(TextUtil.strip(LegacyComponentSerializer.legacyAmpersand().deserialize(boardEntry.title)));
        meta.lore(List.of(TextUtil.strip(Component.text("Нажмите, чтобы поменять", NamedTextColor.GRAY))));

        stack.setItemMeta(meta);
        return stack;
    }

    private GuiItem getText() {
        ItemStack stack = getTextStack();
        return new GuiItem(stack, inventoryClickEvent -> {
            inventoryClickEvent.setCancelled(true);
            new TitleInput(player, this, 1);
            inventoryClickEvent.getWhoClicked().closeInventory();
        });
    }

    private ItemStack getTextStack() {
        ItemStack stack = new ItemStack(Material.PAPER);
        ItemMeta meta = stack.getItemMeta();

        meta.displayName(TextUtil.strip(Component.text("Комментарий", NamedTextColor.GOLD)));
        if(boardEntry.text != null)
            meta.lore(BoardEntry.textLore(boardEntry.text));
        else
            meta.lore(List.of(TextUtil.strip(Component.text("Нажмите, чтобы установить", NamedTextColor.GRAY))));

        stack.setItemMeta(meta);
        return stack;
    }

    private GuiItem getIcon() {
        ItemStack stack = HeadUtil.getSkull(player.getUniqueId());
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(TextUtil.strip(Component.text("Иконка", NamedTextColor.GREEN)));
        meta.lore(List.of(TextUtil.strip(Component.text("Перетащите, чтобы поменять", NamedTextColor.GRAY))));

        stack.setItemMeta(meta);
        return new GuiItem(stack, inventoryClickEvent -> {
            inventoryClickEvent.setCancelled(true);
            ItemStack st = inventoryClickEvent.getCursor();
            if (st.getType() == Material.AIR) {
                stack.setType(Material.PLAYER_HEAD);
                boardEntry.icon = ItemIcon.of(player.getUniqueId());
            } else {
                stack.setType(st.getType());
                ItemMeta met = st.getItemMeta();
                ItemMeta met2 = stack.getItemMeta();
                int model = 0;
                if (met.hasCustomModelData()) {
                    met2.setCustomModelData(met.getCustomModelData());
                    model = met.getCustomModelData();
                }
                stack.setItemMeta(met2);
                boardEntry.icon = ItemIcon.of(st.getType(), model);
            }
            this.update();
        });
    }

    private GuiItem getType() {
        ItemStack stack = new ItemStack(Material.ENDER_PEARL);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(TextUtil.strip(boardEntry.type.name));
        meta.lore(List.of(TextUtil.strip(Component.text("Нажмите, чтобы переключить", NamedTextColor.GRAY))));
        stack.setItemMeta(meta);

        return new GuiItem(stack, inventoryClickEvent -> {
            inventoryClickEvent.setCancelled(true);
            if (boardEntry.type == BoardEntry.Type.BUY) boardEntry.type = BoardEntry.Type.INFO;
            else if (boardEntry.type == BoardEntry.Type.INFO) boardEntry.type = BoardEntry.Type.LOOKING_FOR;
            else if (boardEntry.type == BoardEntry.Type.LOOKING_FOR) boardEntry.type = BoardEntry.Type.SELL;
            else if (boardEntry.type == BoardEntry.Type.SELL) boardEntry.type = BoardEntry.Type.BUY;

            meta.displayName(boardEntry.type.name);
            stack.setItemMeta(meta);

            this.update();
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
