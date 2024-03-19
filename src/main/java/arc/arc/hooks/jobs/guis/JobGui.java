package arc.arc.hooks.jobs.guis;

import arc.arc.configs.Config;
import arc.arc.hooks.jobs.BoostData;
import arc.arc.hooks.jobs.JobsBoost;
import arc.arc.hooks.jobs.JobsHook;
import arc.arc.network.repos.RedisRepo;
import arc.arc.util.GuiUtils;
import arc.arc.util.ItemStackBuilder;
import arc.arc.util.TextUtil;
import com.gamingmesh.jobs.container.Boost;
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
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

import static arc.arc.util.TextUtil.formatAmount;
import static arc.arc.util.TextUtil.mm;

public class JobGui extends ChestGui {
    int rows;
    Player player;
    Config config;
    Job job;
    GuiItem back;

    public JobGui(Job job, Player player, Config config) {
        super(config.integer("job-menu.rows", 4),
                TextHolder.deserialize(config.string("job-menu.title", "Boosts")
                        .replace("<job>", job.getDisplayName())));
        this.rows = config.integer("job-menu.rows", 4);
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
        String expire = TextUtil.formatTime(expiresIn);
        return TagResolver.builder()
                .tag("job", Tag.inserting(mm(job.getName(), true)))
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
                    GuiUtils.constructAndShowAsync(() -> new BoostGui(config, player), click.getWhoClicked());
                }).build();
        pane.addItem(back, 0, rows - 1);

    }

    private void setupBackground() {
        OutlinePane pane = new OutlinePane(0, rows - 1, 9, 1, Pane.Priority.LOWEST);
        pane.addItem(GuiUtils.background());
        pane.setRepeat(true);
        this.addPane(pane);
    }
}
