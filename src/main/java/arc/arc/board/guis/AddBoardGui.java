package arc.arc.board.guis;

import arc.arc.ARC;
import arc.arc.Config;
import arc.arc.TitleInput;
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
import java.util.UUID;

public class AddBoardGui extends ChestGui implements Inputable {

    public String tldr = null;
    public String text = null;
    ItemIcon icon;
    BoardEntry.Type type;
    Player player;
    GuiItem textItem;
    GuiItem tldrItem;

    public AddBoardGui(Player player) {
        super(2, "Создание публикации");
        this.player = player;
        icon = new ItemIcon(player.getUniqueId());
        type = BoardEntry.Type.INFO;
        StaticPane pane = new StaticPane(0, 0, 9, 2);
        setupBackground();
        tldrItem = getTldr();
        textItem = getText();
        pane.addItem(tldrItem, 1, 0);
        pane.addItem(textItem, 3, 0);
        pane.addItem(getIcon(), 5, 0);
        pane.addItem(getType(), 7, 0);
        pane.addItem(publish(), 8, 1);
        pane.addItem(getBack(), 0, 1);
        this.addPane(pane);
    }

    public void proceed() {
        tldrItem.setItem(getTldrStack());
        textItem.setItem(getTextStack());
        this.update();
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

    @Override
    public void setParameter(int n, String s) {
        if (n == 0) this.tldr = s;
        else if (n == 1) this.text = s;
    }

    private GuiItem getBack() {
        ItemStack backItem = new ItemStack(Material.BLUE_STAINED_GLASS_PANE);
        ItemMeta meta2 = backItem.getItemMeta();
        meta2.displayName(Component.text("Назад", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        meta2.setCustomModelData(11013);
        backItem.setItemMeta(meta2);
        return new GuiItem(backItem, inventoryClickEvent -> {
            inventoryClickEvent.setCancelled(true);
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
        if (tldr == null) {
            meta.displayName(TextUtil.strip(Component.text("Короткое название", NamedTextColor.GREEN).append(
                    Component.text(" (макс. длина 20)", NamedTextColor.GRAY)
            )));
            meta.lore(List.of(TextUtil.strip(Component.text("Нажмите, чтобы установить", NamedTextColor.GRAY))));
        } else {
            meta.displayName(TextUtil.strip(LegacyComponentSerializer.legacyAmpersand().deserialize(tldr)));
            meta.lore(List.of(TextUtil.strip(Component.text("Нажмите, чтобы поменять", NamedTextColor.GRAY))));
        }
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
        if (text == null) {
            meta.displayName(TextUtil.strip(Component.text("Комментарий", NamedTextColor.GREEN)));
            meta.lore(List.of(TextUtil.strip(Component.text("Нажмите, чтобы установить", NamedTextColor.GRAY))));
        } else {
            meta.displayName(TextUtil.strip(Component.text("Комментарий", NamedTextColor.GOLD)));
            meta.lore(BoardEntry.getTextLore(text));
        }
        stack.setItemMeta(meta);
        return stack;
    }

    private GuiItem getIcon() {
        ItemStack stack = HeadUtil.getSkull(player.getUniqueId());
        ItemMeta meta = stack.getItemMeta();
        if (icon == null) {
            meta.displayName(TextUtil.strip(Component.text("Иконка", NamedTextColor.GREEN)));
            meta.lore(List.of(TextUtil.strip(Component.text("Перетащите, чтобы установить", NamedTextColor.GRAY))));
        } else {
            meta.displayName(TextUtil.strip(Component.text("Иконка", NamedTextColor.GREEN)));
            meta.lore(List.of(TextUtil.strip(Component.text("Перетащите, чтобы поменять", NamedTextColor.GRAY))));
        }
        stack.setItemMeta(meta);
        return new GuiItem(stack, inventoryClickEvent -> {
            inventoryClickEvent.setCancelled(true);
            ItemStack st = inventoryClickEvent.getCursor();
            if (st.getType() == Material.AIR) {
                stack.setType(Material.PLAYER_HEAD);
                icon = new ItemIcon(player.getUniqueId());
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
                icon = new ItemIcon(st.getType(), model);
            }
            this.update();
        });
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

            if(!ARC.getEcon().has(player, Config.boardCost)){
                inventoryClickEvent.getWhoClicked().closeInventory();
                TextUtil.noMoneyMessage(player, Config.boardCost);
            }

            if (type == null || icon == null || tldr == null) return;
            BoardEntry boardEntry = new BoardEntry(this.type, player.getName(), player.getUniqueId(), icon, text, tldr, System.currentTimeMillis(), UUID.randomUUID());
            ARC.plugin.board.addBoard(boardEntry);
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
