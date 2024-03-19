package arc.arc.hooks.jobs.guis;

import arc.arc.configs.Config;
import arc.arc.hooks.jobs.BoostData;
import arc.arc.hooks.jobs.JobsBoost;
import arc.arc.hooks.jobs.JobsHook;
import arc.arc.network.repos.RedisRepo;
import arc.arc.util.GuiUtils;
import arc.arc.util.ItemStackBuilder;
import com.gamingmesh.jobs.Jobs;
import com.gamingmesh.jobs.commands.list.boost;
import com.gamingmesh.jobs.container.Job;
import com.github.stefvanschie.inventoryframework.adventuresupport.TextHolder;
import com.github.stefvanschie.inventoryframework.gui.GuiItem;
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui;
import com.github.stefvanschie.inventoryframework.pane.OutlinePane;
import com.github.stefvanschie.inventoryframework.pane.PaginatedPane;
import com.github.stefvanschie.inventoryframework.pane.Pane;
import com.github.stefvanschie.inventoryframework.pane.StaticPane;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;

import java.util.ArrayList;
import java.util.List;

import static arc.arc.util.TextUtil.formatAmount;
import static arc.arc.util.TextUtil.mm;

public class BoostGui extends ChestGui {
    private final Config config;
    Player player;
    GuiItem back, global;

    public BoostGui(Config config, Player player) {
        super(config.integer("boost-menu.rows", 6), TextHolder.deserialize(config.string("boost-menu.title", "Boosts")));
        this.player = player;
        this.config = config;

        setupBackground();
        setupNav();
        setupJobs();
    }

    private void setupJobs() {
        PaginatedPane pane = new PaginatedPane(0, 1, 9, config.integer("boost-menu.rows", 6) - 2);
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
                item.enchant(Enchantment.LUCK, 1, true);
            items.add(item
                    .toGuiItemBuilder()
                    .clickEvent(click -> {
                        click.setCancelled(true);
                        GuiUtils.constructAndShowAsync(() -> new JobGui(job, player, config), click.getWhoClicked());
                    })
                    .build());
        }
        pane.populateWithGuiItems(items);
    }

    record CalculatedBoost(Job job, double money, double exp, double points) {
    }

    private CalculatedBoost calculateBoosts(Job job) {
        RedisRepo<BoostData> repo = JobsHook.getRepo();
        double boostMoney = repo.getOrNull(player.getUniqueId().toString())
                .thenApply(data -> data.getBoost(job, JobsBoost.Type.MONEY))
                .join() * 100 - 100;
        double boostPoints = repo.getOrNull(player.getUniqueId().toString())
                .thenApply(data -> data.getBoost(job, JobsBoost.Type.POINTS))
                .join() * 100 - 100;
        double boostExp = repo.getOrNull(player.getUniqueId().toString())
                .thenApply(data -> data.getBoost(job, JobsBoost.Type.EXP))
                .join() * 100 - 100;
        return new CalculatedBoost(job, boostMoney, boostExp, boostPoints);
    }

    TagResolver resolver(Job job, CalculatedBoost boost) {
        String prefixUp = config.string("boost-menu.high-prefix", "<green>+ ");
        String prefixLow = config.string("boost-menu.low-prefix", "<red>- ");

        String prefixMoney = "";
        if (boost.money > 0) prefixMoney = prefixUp;
        else if (boost.money < 0) prefixMoney = prefixLow;

        String prefixPoints = "";
        if (boost.points > 0) prefixPoints = prefixUp;
        else if (boost.points < 0) prefixPoints = prefixLow;

        String prefixExp = "";
        if (boost.exp > 0) prefixExp = prefixUp;
        else if (boost.exp < 0) prefixExp = prefixLow;

        Component name = LegacyComponentSerializer.legacyAmpersand().deserialize(job.getDisplayName().replace("§", "&"))
                .decoration(TextDecoration.ITALIC, false);

        return TagResolver.builder()
                .resolver(TagResolver.resolver("player", Tag.inserting(mm(player.getName(), true))))
                .resolver(TagResolver.resolver("job", Tag.inserting(name)))
                .resolver(TagResolver.resolver("money_boost", Tag.inserting(mm(
                        prefixMoney + formatAmount(boost.money, 4),
                        true))))
                .resolver(TagResolver.resolver("exp_boost", Tag.inserting(mm(
                        prefixExp + formatAmount(boost.exp, 4),
                        true))))
                .resolver(TagResolver.resolver("points_boost", Tag.inserting(mm(
                        prefixPoints + formatAmount(boost.points, 4),
                        true))))
                .build();
    }

    private void setupNav() {
        StaticPane pane = new StaticPane(0, 0, 9, config.integer("boost-menu.rows", 6));
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
        pane.addItem(back, 0, config.integer("boost-menu.rows", 6) - 1);

    }

    private void setupBackground() {
        OutlinePane pane = new OutlinePane(0, 0, 9, config.integer("boost-menu.rows", 6), Pane.Priority.LOWEST);
        pane.addItem(GuiUtils.background());
        pane.setRepeat(true);
        this.addPane(pane);
    }
}
