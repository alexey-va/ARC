package ru.arc.hooks.jobs.guis;

import java.util.ArrayList;
import java.util.List;

import com.gamingmesh.jobs.Jobs;
import com.gamingmesh.jobs.container.Job;
import com.github.stefvanschie.inventoryframework.adventuresupport.TextHolder;
import com.github.stefvanschie.inventoryframework.gui.GuiItem;
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui;
import com.github.stefvanschie.inventoryframework.pane.OutlinePane;
import com.github.stefvanschie.inventoryframework.pane.PaginatedPane;
import com.github.stefvanschie.inventoryframework.pane.Pane;
import com.github.stefvanschie.inventoryframework.pane.StaticPane;
import lombok.SneakyThrows;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import ru.arc.configs.Config;
import ru.arc.hooks.HookRegistry;
import ru.arc.hooks.jobs.BoostData;
import ru.arc.hooks.jobs.JobsBoost;
import ru.arc.hooks.jobs.JobsHook;
import ru.arc.network.repos.RedisRepo;
import ru.arc.util.GuiUtils;
import ru.arc.util.ItemStackBuilder;

import static ru.arc.util.TextUtil.formatAmount;
import static ru.arc.util.TextUtil.mm;
import static ru.arc.util.TextUtil.toLegacy;

public class JobsListGui extends ChestGui {
    private final Config config;
    Player player;
    GuiItem back, global;
    BoostData data;

    public JobsListGui(Config config, Player player) {
        super(3, TextHolder.deserialize(
                toLegacy(config.string("boost-menu.title", "Boosts"))
        ));
        this.player = player;
        this.config = config;


        RedisRepo<BoostData> repo = JobsHook.getRepo();
        data = repo.getOrCreate(player.getUniqueId().toString(), () -> new BoostData(player.getUniqueId())).join();

        setupBackground();
        setupNav();
        setupJobs();
    }

    private void setupJobs() {
        PaginatedPane pane = new PaginatedPane(0, 0, 9, 2);
        this.addPane(pane);
        List<GuiItem> items = new ArrayList<>();

        for (Job job : Jobs.getJobs()) {
            CalculatedBoost boost = calculateBoosts(job);
            ItemStackBuilder item = new ItemStackBuilder(job.getGuiItem().getType())
                    .display(config.string("boost-menu.job-display"))
                    .lore(config.stringList("boost-menu.job-lore"))
                    .tagResolver(resolver(job, boost))
                    .flags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_ATTRIBUTES);
            if (boost.money != 0.0 || boost.exp != 0.0 || boost.points != 0.0)
                item.enchant(Enchantment.VANISHING_CURSE, 1, true);
            items.add(item
                    .toGuiItemBuilder()
                    .clickEvent(click -> {
                        click.setCancelled(true);
                        GuiUtils.constructAndShowAsync(() -> new BoostsOfJobGui(job, player, config), click.getWhoClicked());
                    }).build());
        }
        pane.populateWithGuiItems(items);
    }

    record CalculatedBoost(Job job, double money, double exp, double points) {
    }

    @SneakyThrows
    private CalculatedBoost calculateBoosts(Job job) {
        double boostMoney = data.getBoost(job, JobsBoost.Type.MONEY) * 100 - 100;
        double boostPoints = data.getBoost(job, JobsBoost.Type.POINTS)* 100 - 100;
        double boostExp =  data.getBoost(job, JobsBoost.Type.EXP) * 100 - 100;
        return new CalculatedBoost(job, boostMoney, boostExp, boostPoints);
    }

    TagResolver resolver(Job job, CalculatedBoost boost) {
        String prefixUp = config.string("boost-menu.high-prefix", "<green>+ ");
        String prefixLow = config.string("boost-menu.low-prefix", "<red>- ");

        double moneyBaseBoost = HookRegistry.jobsHook.getBoost(player, job.getName(), JobsBoost.Type.MONEY)*100;
        double pointsBaseBoost = HookRegistry.jobsHook.getBoost(player, job.getName(), JobsBoost.Type.POINTS)*100;
        double expBaseBoost = HookRegistry.jobsHook.getBoost(player, job.getName(), JobsBoost.Type.EXP)*100;

        double moneyBoost = boost.money + moneyBaseBoost;
        double pointsBoost = boost.points + pointsBaseBoost;
        double expBoost = boost.exp + expBaseBoost;

        String prefixMoney = "";
        if (moneyBoost > 0) prefixMoney = prefixUp;
        else if (moneyBoost < 0) prefixMoney = prefixLow;

        String prefixPoints = "";
        if (pointsBoost > 0) prefixPoints = prefixUp;
        else if (pointsBoost < 0) prefixPoints = prefixLow;

        String prefixExp = "";
        if (expBoost > 0) prefixExp = prefixUp;
        else if (expBoost < 0) prefixExp = prefixLow;

        Component name = LegacyComponentSerializer.legacyAmpersand().deserialize(job.getDisplayName().replace("§", "&"))
                .decoration(TextDecoration.ITALIC, false);

        return TagResolver.builder()
                .tag("player", Tag.inserting(mm(player.getName(), true)))
                .tag("job", Tag.inserting(name))
                .tag("money_boost", Tag.inserting(mm(prefixMoney + formatAmount(Math.abs(moneyBoost), 4), true)))
                .tag("exp_boost", Tag.inserting(mm(prefixExp + formatAmount(Math.abs(expBoost), 4), true)))
                .tag("points_boost", Tag.inserting(mm(prefixPoints + formatAmount(Math.abs(pointsBoost), 4), true)))
                .build();
    }

    private void setupNav() {
        StaticPane pane = new StaticPane(0, 2, 9, 1);
        this.addPane(pane);


        back = new ItemStackBuilder(Material.BLUE_STAINED_GLASS_PANE)
                .display(config.string("boost-menu.back-display"))
                .lore(config.stringList("boost-menu.back-lore"))
                .modelData(11013)
                .toGuiItemBuilder()
                .clickEvent(click -> {
                    click.setCancelled(true);
                    ((Player) click.getWhoClicked()).performCommand(config.string("boost-menu.back-command", "menu"));
                }).build();
        pane.addItem(back, 0, 0);

        GuiItem buy = new ItemStackBuilder(Material.GREEN_STAINED_GLASS_PANE)
                .display(config.string("boost-menu.buy-display"))
                .lore(config.stringList("boost-menu.buy-lore"))
                .toGuiItemBuilder()
                .clickEvent(click -> {
                    click.setCancelled(true);
                    GuiUtils.constructAndShowAsync(() -> new BuyBoostGui(player, null, config), click.getWhoClicked());
                }).build();
        pane.addItem(buy, 4, 0);
    }

    private void setupBackground() {
        OutlinePane pane = new OutlinePane(0, 0, 9, 3, Pane.Priority.LOWEST);
        pane.addItem(GuiUtils.background());
        pane.setRepeat(true);
        this.addPane(pane);
    }
}
