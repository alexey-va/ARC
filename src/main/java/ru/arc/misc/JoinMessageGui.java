package ru.arc.misc;

import ru.arc.ARC;
import ru.arc.configs.Config;
import ru.arc.configs.ConfigManager;
import ru.arc.hooks.HookRegistry;
import ru.arc.util.GuiUtils;
import ru.arc.util.ItemStackBuilder;
import ru.arc.util.TextUtil;
import com.github.stefvanschie.inventoryframework.adventuresupport.TextHolder;
import com.github.stefvanschie.inventoryframework.gui.GuiItem;
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui;
import com.github.stefvanschie.inventoryframework.pane.OutlinePane;
import com.github.stefvanschie.inventoryframework.pane.PaginatedPane;
import com.github.stefvanschie.inventoryframework.pane.Pane;
import com.github.stefvanschie.inventoryframework.pane.StaticPane;
import lombok.extern.slf4j.Slf4j;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
public class JoinMessageGui extends ChestGui {

    private static final Config config = ConfigManager.of(ARC.plugin.getDataFolder().toPath(), "misc.yml");

    PaginatedPane pane;
    Player player;
    boolean isJoin;
    String prefix;

    public JoinMessageGui(Player player, boolean isJoin, int startPage) {
        super(2, "Join Message GUI");

        this.player = player;
        this.isJoin = isJoin;
        if (isJoin) {
            this.prefix = "join-message-gui.";
        } else {
            this.prefix = "leave-message-gui.";
        }

        int rows = config.integer("join-message-gui.rows", 6);
        TextHolder title = isJoin ?
                TextHolder.deserialize(config.string(prefix + "title", "&8Сообщения при входе")) :
                TextHolder.deserialize(config.string(prefix + "title", "&8Сообщения при выходе"));

        setRows(rows);
        setTitle(title);

        setupBackground();
        setupMessages();
        setupButtons();

        pane.setPage(startPage);
    }

    private void setupButtons() {
        StaticPane pane = new StaticPane(0, this.getRows() - 1, 9, 1);
        this.addPane(pane);

        GuiItem backButton = new ItemStackBuilder(config.material("join-message-gui.back-button", Material.BLUE_STAINED_GLASS_PANE))
                .modelData(config.integer("join-message-gui.back-button-model-data", 11013))
                .display(config.string("join-message-gui.back-button-display", "<gold>Назад"))
                .lore(config.list("join-message-gui.back-button-lore", List.of(
                        "<gray>Вернуться в главное меню"
                )))
                .toGuiItemBuilder()
                .clickEvent((click) -> {
                    click.setCancelled(true);
                    player.performCommand(config.string("join-message-gui.back-command", "menu"));
                }).build();
        pane.addItem(backButton, 0, 0);

        GuiItem switchButton = new ItemStackBuilder(config.material(prefix + "switch-button", Material.LIME_STAINED_GLASS_PANE))
                .modelData(config.integer(prefix + "switch-button-model-data", 0))
                .display(config.string(prefix + "switch-button-display", "<gold>Сменить режим"))
                .lore(config.list(prefix + "switch-button-lore"))
                .toGuiItemBuilder()
                .clickEvent((click) -> {
                    click.setCancelled(true);
                    GuiUtils.constructAndShowAsync(() -> new JoinMessageGui(player, !isJoin, 0), player);
                }).build();
        pane.addItem(switchButton, 4, 0);

        GuiItem nextButton = new ItemStackBuilder(config.material("join-message-gui.next-button", Material.BLUE_STAINED_GLASS_PANE))
                .modelData(config.integer("join-message-gui.next-button-model-data", 11013))
                .display(config.string("join-message-gui.next-button-display", "<gold>Далее"))
                .lore(config.list("join-message-gui.next-button-lore", List.of(
                        "<gray>Перейти к следующей странице"
                )))
                .toGuiItemBuilder()
                .clickEvent((click) -> {
                    click.setCancelled(true);
                    if (this.pane.getPage() < this.pane.getPages() - 1) {
                        this.pane.setPage(this.pane.getPage() + 1);
                    }
                    update();
                }).build();
        pane.addItem(nextButton, 5, 0);

        GuiItem prevButton = new ItemStackBuilder(config.material("join-message-gui.prev-button", Material.BLUE_STAINED_GLASS_PANE))
                .modelData(config.integer("join-message-gui.prev-button-model-data", 11013))
                .display(config.string("join-message-gui.prev-button-display", "<gold>Назад"))
                .lore(config.list("join-message-gui.prev-button-lore", List.of(
                        "<gray>Перейти к предыдущей странице"
                )))
                .toGuiItemBuilder()
                .clickEvent((click) -> {
                    click.setCancelled(true);
                    if (this.pane.getPage() > 0) {
                        this.pane.setPage(this.pane.getPage() - 1);
                    }
                    update();
                }).build();
        pane.addItem(prevButton, 3, 0);

    }

    private void setupMessages() {
        pane = new PaginatedPane(0, 0, 9, this.getRows() - 1);
        this.addPane(pane);

        List<GuiItem> items = new ArrayList<>();

        List<Map<String, Object>> messages = config.list(prefix + "messages");
        List<String> lore = config.list(prefix + "default-lore", List.of(
                "<dark_gray> > <gray>Привелегия: <gold>%rank%",
                "<white>%prefix%%message%"
        ));

        List<String> forbiddenLore = config.list(prefix + "forbidden-lore", List.of(
                "<red>Это вам пока недоступно!",
                ""
        ));

        List<String> currentLore = config.list(prefix + "current-lore", List.of(
                "<green>Это ваше текущее сообщение",
                ""
        ));

        Material selectedMaterial = config.material(prefix + "selected-material", Material.ENDER_PEARL);

        JoinMessages join = JoinMessages.repo.getOrCreate(player.getName(), () -> new JoinMessages(player.getName())).join();
        Set<String> currentMessages = isJoin ? join.joinMessages : join.leaveMessages;
        Set<String> unseenMessages = new HashSet<>(currentMessages);

        int id = 1;
        for (var map : messages) {
            try {
                String displayName = (String) map.getOrDefault("display-name",
                        config.string(prefix + "default-display-name", "<gold>Сообщение %id%"));
                String message = (String) map.get("message");
                String permission = (String) map.get("permission");
                String rank = (String) map.getOrDefault("rank", config.string(prefix + "common-rank", "<green>Для всех"));
                Material material = Material.valueOf(((String) map.getOrDefault("material", "PAPER")).toUpperCase());
                displayName = displayName.replace("%id%", String.valueOf(id));

                if (!config.bool(prefix + "show-all", true) && permission != null && !player.hasPermission(permission)) {
                    continue;
                }

                boolean isCurrent = currentMessages.contains(message);
                unseenMessages.remove(message);

                String parsedMessage = HookRegistry.papiHook != null ? HookRegistry.papiHook.parse(message, player) : message;

                Stream<String> stream = lore.stream();
                if (permission != null && !player.hasPermission(permission)) {
                    stream = Stream.concat(forbiddenLore.stream(), stream);
                } else if (isCurrent) {
                    stream = Stream.concat(currentLore.stream(), stream);
                }

                List<String> resLore = stream
                        .map(line -> line.replace("%message%", parsedMessage))
                        .map(line -> line.replace("%rank%", rank))
                        .map(line -> line.replace("%prefix%", config.string(prefix + "prefix", "<dark_green>❖ ")))
                        .collect(Collectors.toList());

                String last = resLore.getLast();
                resLore.removeLast();
                resLore.addAll(TextUtil.splitLoreString(last, config.integer(prefix + "max-len", 60), config.integer(prefix + "spaces-padding", 3)));

                GuiItem guiItem = new ItemStackBuilder(isCurrent ? selectedMaterial : material)
                        .display(displayName)
                        .lore(resLore)
                        .hideAll()
                        .toGuiItemBuilder()
                        .clickEventWithStack((click, stack) -> {
                            click.setCancelled(true);
                            if (permission != null && !player.hasPermission(permission)) {
                                GuiUtils.temporaryChange(stack,
                                        config.componentDef(prefix + "forbidden-temp-display",
                                                "<dark_red>Вы не можете использовать это сообщение!"),
                                        null, 60, JoinMessageGui.this::update);
                                return;
                            }
                            if (HookRegistry.luckPermsHook == null) {
                                log.error("LuckPerms hook is not available");
                                return;
                            }
                            if (isCurrent) {
                                currentMessages.remove(message);
                                if(isJoin) {
                                    join.removeJoinMessage(message);
                                } else {
                                    join.removeLeaveMessage(message);
                                }
                                GuiUtils.constructAndShowAsync(() -> new JoinMessageGui(player, isJoin, this.pane.getPage()), player);
                            } else {
                                if (isJoin){
                                    join.addJoinMessage(message);
                                } else {
                                    join.addLeaveMessage(message);
                                }
                                GuiUtils.constructAndShowAsync(() -> new JoinMessageGui(player, isJoin, this.pane.getPage()), player);
                            }
                        }).build();
                items.add(guiItem);
            } catch (Exception e) {
                log.error("Error while parsing message: {}", map, e);
            } finally {
                id++;
            }
        }
        if (!unseenMessages.isEmpty()) {
            log.info("Player {} has unseen messages: {}", player.getName(), unseenMessages);
            if(isJoin) {
                unseenMessages.forEach(join::removeJoinMessage);
            } else {
                unseenMessages.forEach(join::removeLeaveMessage);
            }
        }
        pane.populateWithGuiItems(items);
    }

    private void setupBackground() {
        OutlinePane pane = new OutlinePane(0, this.getRows() - 1, 9, 1);
        pane.addItem(GuiUtils.background());
        pane.setRepeat(true);
        pane.setPriority(Pane.Priority.LOWEST);
        this.addPane(pane);
    }
}
