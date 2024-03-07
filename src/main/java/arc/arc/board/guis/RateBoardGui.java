package arc.arc.board.guis;

import arc.arc.board.Board;
import arc.arc.board.BoardEntry;
import arc.arc.configs.BoardConfig;
import arc.arc.util.GuiUtils;
import arc.arc.util.ItemStackBuilder;
import com.github.stefvanschie.inventoryframework.adventuresupport.TextHolder;
import com.github.stefvanschie.inventoryframework.gui.GuiItem;
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui;
import com.github.stefvanschie.inventoryframework.pane.OutlinePane;
import com.github.stefvanschie.inventoryframework.pane.Pane;
import com.github.stefvanschie.inventoryframework.pane.StaticPane;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

public class RateBoardGui extends ChestGui {
    Player player;
    BoardEntry entry;
    GuiItem downItem, upItem, reportItem, backItem;



    public RateBoardGui(Player player, BoardEntry entry) {
        super(2, TextHolder.deserialize(BoardConfig.rateGuiName));
        setupBackground();
        this.player = player;
        this.entry = entry;

        StaticPane pane = new StaticPane(0, 0, 9, 2);
        downItem = downItem();
        upItem = upItem();
        reportItem = reportItem();
        backItem = backItem();
        pane.addItem(backItem, 0, 1);
        pane.addItem(upItem, 1, 0);
        pane.addItem(downItem, 3, 0);
        pane.addItem(reportItem, 7, 0);

        this.addPane(pane);
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

    private GuiItem upItem(){
        return new ItemStackBuilder(Material.GREEN_STAINED_GLASS_PANE)
                .display(entry.hasRated(player) == 1 ?
                        BoardConfig.getString("rate-menu.already-rate") :
                        BoardConfig.getString("rate-menu.up-display"))
                .lore(BoardConfig.getStringList("rate-menu.up-lore"))
                .toGuiItemBuilder()
                .clickEvent(click -> {
                    click.setCancelled(true);
                    if(!entry.canRate(player)){
                        cantRateDisplay(upItem);
                        return;
                    }

                    if(entry.hasRated(player) == 1){
                        alreadyRated(upItem);
                        return;
                    }

                    entry.rate(player.getName(), 1);
                    //Board.instance().saveBoardEntry(entry.entryUuid);
                    ratedSuccessfully(upItem);
                }).build();
    }

    private GuiItem downItem(){
        return new ItemStackBuilder(Material.RED_STAINED_GLASS_PANE)
                .display(entry.hasRated(player) == -1 ?
                        BoardConfig.getString("rate-menu.already-rate") :
                        BoardConfig.getString("rate-menu.down-display"))
                .lore(BoardConfig.getStringList("rate-menu.down-lore"))
                .toGuiItemBuilder()
                .clickEvent(click -> {
                    click.setCancelled(true);
                    if(!entry.canRate(player)){
                        //System.out.println("Cant rate!");
                        cantRateDisplay(downItem);
                        return;
                    }

                    if(entry.hasRated(player) == -1){
                        //System.out.println("Already rated!");
                        alreadyRated(downItem);
                        return;
                    }

                    entry.rate(player.getName(), -1);
                    //Board.instance().saveBoardEntry(entry.entryUuid);
                    ratedSuccessfully(downItem);
                }).build();
    }

    private GuiItem reportItem(){
        return new ItemStackBuilder(Material.PURPLE_STAINED_GLASS_PANE)
                .display(entry.hasReported(player) ?
                        BoardConfig.getString("rate-menu.already-report") :
                        BoardConfig.getString("rate-menu.report-display"))
                .lore(BoardConfig.getStringList("rate-menu.report-lore"))
                .toGuiItemBuilder()
                .clickEvent(click -> {
                    click.setCancelled(true);
                    if(!entry.canRate(player)){
                        cantRateDisplay(reportItem);
                        return;
                    }

                    if(entry.hasReported(player)){
                        alreadyReported(reportItem);
                        return;
                    }

                    entry.report(player.getName());
                    Board.instance().updateCache(entry.entryUuid);
                    //Board.instance().saveBoardEntry(entry.entryUuid);
                    reportedSuccessfully(reportItem);
                }).build();
    }

    private void cantRateDisplay(GuiItem guiItem){
        GuiUtils.temporaryChange(guiItem.getItem(),
                MiniMessage.miniMessage().deserialize(BoardConfig.getString("rate-menu.cant-rate")),
                null, 60L, this::update);
        update();
    }

    private void alreadyRated(GuiItem guiItem){
        GuiUtils.temporaryChange(guiItem.getItem(),
                MiniMessage.miniMessage().deserialize(BoardConfig.getString("rate-menu.already-rate")),
                null, 60L, this::update);
        update();
    }

    private void ratedSuccessfully(GuiItem guiItem){
        //System.out.println("Rated success: "+guiItem.getItem());
        GuiUtils.temporaryChange(guiItem.getItem(),
                MiniMessage.miniMessage().deserialize(BoardConfig.getString("rate-menu.success-rate")),
                null, -1L, this::update);
        update();
    }

    private void alreadyReported(GuiItem guiItem){
        GuiUtils.temporaryChange(guiItem.getItem(),
                MiniMessage.miniMessage().deserialize(BoardConfig.getString("rate-menu.already-report")),
                null, 100L, this::update);
        update();
    }

    private void reportedSuccessfully(GuiItem guiItem){
        GuiUtils.temporaryChange(guiItem.getItem(),
                MiniMessage.miniMessage().deserialize(BoardConfig.getString("rate-menu.success-report")),
                null, 100L, this::update);
        update();
    }

    private void setupBackground() {
        OutlinePane pane = new OutlinePane(0, 0, 9, 2);
        pane.addItem(GuiUtils.background());
        pane.setRepeat(true);
        pane.setPriority(Pane.Priority.LOWEST);
        this.addPane(pane);
    }
}
