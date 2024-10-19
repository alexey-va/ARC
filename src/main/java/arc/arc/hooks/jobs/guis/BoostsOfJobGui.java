package arc.arc.hooks.jobs.guis;

import arc.arc.configs.Config;
import arc.arc.hooks.HookRegistry;
import arc.arc.hooks.jobs.BoostData;
import arc.arc.hooks.jobs.JobsBoost;
import arc.arc.hooks.jobs.JobsHook;
import arc.arc.network.repos.RedisRepo;
import arc.arc.util.GuiUtils;
import arc.arc.util.ItemStackBuilder;
import arc.arc.util.TextUtil;
import com.gamingmesh.jobs.container.Job;
import com.github.stefvanschie.inventoryframework.adventuresupport.TextHolder;
import com.github.stefvanschie.inventoryframework.gui.GuiItem;
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui;
import com.github.stefvanschie.inventoryframework.pane.OutlinePane;
import com.github.stefvanschie.inventoryframework.pane.PaginatedPane;
import com.github.stefvanschie.inventoryframework.pane.Pane;
import com.github.stefvanschie.inventoryframework.pane.StaticPane;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static arc.arc.util.TextUtil.*;

public class BoostsOfJobGui extends ChestGui {
    int rows;
    Player player;
    Config config;
    Job job;
    GuiItem back;
    Collection<JobsBoost> boosts;

    public BoostsOfJobGui(Job job, Player player, Config config) {
        super(4, "");
        String jobDisplay = job.getDisplayName().replace("§", "&");
        int index = jobDisplay.indexOf("&");
        if (index != -1) {
            jobDisplay = jobDisplay.substring(0, index + 2) + "&l" + jobDisplay.substring(index + 2);
        }
        setTitle(TextHolder.deserialize(
                toLegacy(config.string("job-menu.title", "Boosts")
                        .replace("<job>", jobDisplay)))
        );

        var playerData = JobsHook.getRepo().getNow(player.getUniqueId().toString());
        if (playerData == null) boosts = new ArrayList<>();
        else boosts = playerData.boosts(job);

        this.rows = Math.max(2, Math.min(6, (int) Math.ceil(boosts.size() / 9.0)));
        setRows(rows);

        this.player = player;
        this.config = config;
        this.job = job;

        setupBackground();
        setupNav();
        setupBoosts();
    }

    private void setupBoosts() {
        PaginatedPane pane = new PaginatedPane(0, 0, 9, rows - 1);
        this.addPane(pane);


        RedisRepo<BoostData> repo = JobsHook.getRepo();
        BoostData data = repo.getNow(player.getUniqueId().toString());
        if (data == null) return;
        List<GuiItem> items = new ArrayList<>();

        double moneyBaseBoost = HookRegistry.jobsHook.getBoost(player, job.getName(), JobsBoost.Type.MONEY) * 100;
        double pointsBaseBoost = HookRegistry.jobsHook.getBoost(player, job.getName(), JobsBoost.Type.POINTS) * 100;
        double expBaseBoost = HookRegistry.jobsHook.getBoost(player, job.getName(), JobsBoost.Type.EXP) * 100;

        if (moneyBaseBoost > 1 || pointsBaseBoost > 1 || expBaseBoost > 1) {
            if (Math.abs(moneyBaseBoost - pointsBaseBoost) < 1.0 && Math.abs(moneyBaseBoost - expBaseBoost) < 1.0) {
                GuiItem all = new ItemStackBuilder(Material.DIAMOND)
                        .display(config.string("job-menu.all-base-boost-display"))
                        .lore(config.stringList("job-menu.all-base-boost-lore"))
                        .tagResolver(TagResolver.builder()
                                .tag("job", Tag.inserting(mm(job.getName(), true)))
                                .tag("boost", Tag.inserting(mm(formatAmount(moneyBaseBoost, 3), true)))
                                .build())
                        .toGuiItemBuilder()
                        .clickEvent(click -> click.setCancelled(true))
                        .build();
                items.add(all);
            } else {
                if (moneyBaseBoost > 1) {
                    GuiItem money = new ItemStackBuilder(Material.GOLD_INGOT)
                            .display(config.string("job-menu.money-base-boost-display"))
                            .lore(config.stringList("job-menu.money-base-boost-lore"))
                            .tagResolver(TagResolver.builder()
                                    .tag("job", Tag.inserting(mm(job.getName(), true)))
                                    .tag("boost", Tag.inserting(mm(formatAmount(moneyBaseBoost, 3), true)))
                                    .build())
                            .toGuiItemBuilder()
                            .clickEvent(click -> click.setCancelled(true))
                            .build();
                    items.add(money);
                }
                if (pointsBaseBoost > 1) {
                    GuiItem points = new ItemStackBuilder(Material.NETHER_STAR)
                            .display(config.string("job-menu.points-base-boost-display"))
                            .lore(config.stringList("job-menu.points-base-boost-lore"))
                            .tagResolver(TagResolver.builder()
                                    .tag("job", Tag.inserting(mm(job.getName(), true)))
                                    .tag("boost", Tag.inserting(mm(formatAmount(pointsBaseBoost, 3), true)))
                                    .build())
                            .toGuiItemBuilder()
                            .clickEvent(click -> click.setCancelled(true))
                            .build();
                    items.add(points);
                }
                if (expBaseBoost > 1) {
                    GuiItem exp = new ItemStackBuilder(Material.EXPERIENCE_BOTTLE)
                            .display(config.string("job-menu.exp-base-boost-display"))
                            .lore(config.stringList("job-menu.exp-base-boost-lore"))
                            .tagResolver(TagResolver.builder()
                                    .tag("job", Tag.inserting(mm(job.getName(), true)))
                                    .tag("boost", Tag.inserting(mm(formatAmount(expBaseBoost, 3), true)))
                                    .build())
                            .toGuiItemBuilder()
                            .clickEvent(click -> click.setCancelled(true))
                            .build();
                    items.add(exp);
                }
            }
        }

        for (JobsBoost boost : data.boosts(job)) {
            TagResolver resolver = resolver(job, boost);
            Material mat = Material.DIAMOND;

            if (boost.getType() == JobsBoost.Type.EXP) mat = Material.EXPERIENCE_BOTTLE;
            if (boost.getType() == JobsBoost.Type.MONEY) mat = Material.GOLD_INGOT;
            if (boost.getType() == JobsBoost.Type.POINTS) mat = Material.NETHER_STAR;

            ItemStackBuilder builder = new ItemStackBuilder(mat)
                    .display(config.string("job-menu.boost-display"))
                    .lore(config.stringList("job-menu.boost-lore"))
                    .tagResolver(resolver);
            items.add(builder.toGuiItemBuilder()
                    .clickEvent(click -> click.setCancelled(true))
                    .build());
        }


        pane.populateWithGuiItems(items);
    }

    TagResolver resolver(Job job, JobsBoost jobsBoost) {
        long expiresIn = jobsBoost.expiresInMillis();
        String expire = TextUtil.time(expiresIn, TimeUnit.MILLISECONDS);
        return TagResolver.builder()
                .tag("job", Tag.inserting(mm(job.getName(), true)))
                .tag("id", Tag.inserting(mm(jobsBoost.getId(), true)))
                .tag("type", Tag.inserting(mm(jobsBoost.getType().getDisplay(), true)))
                .tag("amount", Tag.inserting(mm(formatAmount(jobsBoost.getBoost() * 100, 3), true)))
                .tag("expire", Tag.inserting(mm(expire, true)))
                .build();
    }

    private void setupNav() {
        StaticPane pane = new StaticPane(0, 0, 9, rows);
        this.addPane(pane);


        back = new ItemStackBuilder(Material.BLUE_STAINED_GLASS_PANE)
                .display(config.string("job-menu.back-display"))
                .lore(config.stringList("job-menu.back-lore"))
                .modelData(11013)
                .toGuiItemBuilder()
                .clickEvent(click -> {
                    click.setCancelled(true);
                    GuiUtils.constructAndShowAsync(() -> new JobsListGui(config, player), click.getWhoClicked());
                }).build();
        pane.addItem(back, 0, rows - 1);

        GuiItem buy = new ItemStackBuilder(Material.GREEN_STAINED_GLASS_PANE)
                .display(config.string("job-menu.buy-display"))
                .lore(config.stringList("job-menu.buy-lore"))
                .toGuiItemBuilder()
                .clickEvent(click -> {
                    click.setCancelled(true);
                    GuiUtils.constructAndShowAsync(() -> new BuyBoostGui(player, job, config), click.getWhoClicked());
                }).build();
        pane.addItem(buy, 4, rows - 1);
    }

    private void setupBackground() {
        OutlinePane pane = new OutlinePane(0, rows - 1, 9, 1, Pane.Priority.LOWEST);
        pane.addItem(GuiUtils.background());
        pane.setRepeat(true);
        this.addPane(pane);
    }
}
