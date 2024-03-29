package arc.arc.hooks.jobs.guis;

import arc.arc.ARC;
import arc.arc.configs.Config;
import arc.arc.configs.ConfigManager;
import arc.arc.hooks.HookRegistry;
import arc.arc.hooks.jobs.BoostData;
import arc.arc.hooks.jobs.JobsBoost;
import arc.arc.util.GuiUtils;
import arc.arc.util.ItemStackBuilder;
import com.gamingmesh.jobs.Jobs;
import com.gamingmesh.jobs.commands.list.log;
import com.gamingmesh.jobs.container.Boost;
import com.gamingmesh.jobs.container.Job;
import com.github.stefvanschie.inventoryframework.gui.GuiItem;
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui;
import com.github.stefvanschie.inventoryframework.pane.OutlinePane;
import com.github.stefvanschie.inventoryframework.pane.PaginatedPane;
import com.github.stefvanschie.inventoryframework.pane.Pane;
import com.github.stefvanschie.inventoryframework.pane.StaticPane;
import lombok.extern.log4j.Log4j2;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static arc.arc.util.TextUtil.formatAmount;
import static arc.arc.util.TextUtil.mm;

@Log4j2
public class BuyBoostGui extends ChestGui {

    Player player;
    Job job;
    JobsBoost.Type type = JobsBoost.Type.MONEY;
    Config config;
    int rows = 3;

    List<Boost> boosts = new ArrayList<>();

    record Boost(String display, List<String> lore, double price, double boostAmount, long seconds,
                 String permission, Material material, int modelData, BuyCurrency currency, String id,
                 List<String> jobs, List<JobsBoost.Type> types) {
    }

    public BuyBoostGui(Player player, Job job, Config config) {
        super(3, "");
        setRows(rows);
        this.player = player;
        this.job = job;
        this.config = config;

        setupBackground();
        setupNav();
        setupButtons();
    }

    private void setupButtons() {
        PaginatedPane pane = new PaginatedPane(0, 0, 9, rows - 1);
        this.addPane(pane);

        List<GuiItem> items = generateItems();
        pane.populateWithGuiItems(items);
    }

    List<GuiItem> generateItems() {
        boosts.clear();
        for (String key : config.keys("boosts." + type.name().toLowerCase())) {
            String display = config.string("boosts." + type.name().toLowerCase() + "." + key + ".display");
            List<String> lore = config.stringList("boosts." + type.name().toLowerCase() + "." + key + ".lore");
            double price = config.realNumber("boosts." + type.name().toLowerCase() + "." + key + ".price", 1000);
            double boostAmount = config.realNumber("boosts." + type.name().toLowerCase() + "." + key + ".boost-amount", 0.1);
            long seconds = config.integer("boosts." + type.name().toLowerCase() + "." + key + ".seconds", 3600);
            String permission = config.string("boosts." + type.name().toLowerCase() + "." + key + ".permission", null);
            Material material = Material.valueOf(config.string("boosts." + type.name().toLowerCase() + "." + key + ".material", "GOLD_INGOT").toUpperCase());
            int modelData = config.integer("boosts." + type.name().toLowerCase() + "." + key + ".model-data", 0);
            BuyCurrency currency = BuyCurrency.valueOf(config.string("boosts." + type.name().toLowerCase() + "." + key + ".currency", "MONEY").toUpperCase());
            String id = config.string("boosts." + type.name().toLowerCase() + "." + key + ".id", "none");
            List<String> jobs = config.stringList("boosts." + type.name().toLowerCase() + "." + key + ".jobs")
                    .stream().map(String::toLowerCase).map(String::intern).toList();
            List<JobsBoost.Type> types = new ArrayList<>();
            for (String s : config.stringList("boosts." + type.name().toLowerCase() + "." + key + ".types")) {
                types.add(JobsBoost.Type.valueOf(s.toUpperCase()));
            }
            Boost data = new Boost(display, lore, price, boostAmount, seconds, permission, material, modelData, currency, id, jobs, types);
            boosts.add(data);
        }

        List<GuiItem> items = new ArrayList<>();
        for (Boost boost : boosts) {
            GuiItem item = generateGuiItem(boost);
            if(item != null) items.add(item);
        }
        return items;
    }

    GuiItem generateGuiItem(Boost boost){
        boolean allJobs = boost.jobs().isEmpty() || boost.jobs().contains("all");
        boolean allTypes = boost.types().contains(JobsBoost.Type.ALL) || boost.types().isEmpty();
        if (job == null && !allJobs) return null;
        if (job != null && !allJobs && !boost.jobs().contains(job.getName().toLowerCase())) return null;

        EconomyCheck economyCheck = checkEconomy(boost.currency(), boost.price());
        List<String> lore;
        String currencyName = config.string("currency-names." + boost.currency().name().toLowerCase(), "Money");
        boolean hasBoost = HookRegistry.jobsHook.hasBoost(player, boost.id());

        if (!economyCheck.hasEnough) {
            lore = config.stringList("boostbuy-menu.not-enough-money");
        } else if (boost.permission() != null && !player.hasPermission(boost.permission())) {
            lore = config.stringList("boostbuy-menu.no-permission");
        } else if (hasBoost) {
            lore = config.stringList("boostbuy-menu.already-have-boost-lore");
        } else {
            lore = boost.lore() == null || boost.lore().isEmpty() ? config.stringList("boostbuy-menu.boost-lore") : boost.lore();
        }

        lore = lore.stream().map(s -> s
                .replace("<price>", formatAmount(boost.price()))
                .replace("<boost>", formatAmount(boost.boostAmount()))
                .replace("<currency>", currencyName)
                .replace("<permission>", boost.permission() != null ? boost.permission() : "Нет")
                .replace("<time>", boost.seconds() / 60 + " минут")
                .replace("<type>", allTypes ? "Все" : boost.types().stream().map(JobsBoost.Type::name).collect(Collectors.joining(", ")))
                .replace("<job>", allJobs ? "Все" : boost.jobs().stream().map(HookRegistry.jobsHook::jobDisplayMinimessage).collect(Collectors.joining(", ")))
                .replace("<currency_lack>", formatAmount(economyCheck.currencyNeeded))).toList();
        final GuiItem item = new ItemStackBuilder(boost.material())
                .display(boost.display())
                .lore(boost.lore())
                .modelData(boost.modelData())
                .lore(lore)
                .toGuiItemBuilder()
                .build();
        item.setAction(click -> {
            click.setCancelled(true);

            if (HookRegistry.jobsHook.hasBoost(player, boost.id())) {
                GuiUtils.temporaryChange(item.getItem(), mm(config.string("boostbuy-menu.already-have-boost-display"), true),
                        null, 60, this::update);
                this.update();
                return;
            }

            if (!economyCheck.hasEnough()) {
                GuiUtils.temporaryChange(item.getItem(), mm(config.string("boostbuy-menu.not-enough-money"), true),
                        null, 60, this::update);
                this.update();
                return;
            } else {
                takeCurrency(boost.currency(), boost.price());
            }
            HookRegistry.jobsHook.addBoost(player.getUniqueId(), boost.jobs(), boost.boostAmount(),
                    System.currentTimeMillis() + boost.seconds() * 1000L, boost.id(), boost.types());
            GuiItem newItem = generateGuiItem(boost);
            item.setItem(newItem.getItem());
            update();
        });
        return item;
    }

    private boolean takeCurrency(BuyCurrency currency, double price) {
        switch (currency) {
            case MONEY -> ARC.getEcon().withdrawPlayer(player, price);
            case POINTS ->
                    Jobs.getPlayerManager().getJobsPlayer(player).getPointsData().setPoints(Jobs.getPlayerManager().getJobsPlayer(player).getPointsData().getCurrentPoints() - price);
            case EXP -> player.setTotalExperience((int) (player.getTotalExperience() - price));
        }
        return true;
    }


    record EconomyCheck(boolean hasEnough, double currencyNeeded) {
    }

    private EconomyCheck checkEconomy(BuyCurrency currency, double price) {
        double currencyNeeded = 0;
        switch (currency) {
            case MONEY -> currencyNeeded = ARC.getEcon().getBalance(player) - price;
            case POINTS ->
                    currencyNeeded = Jobs.getPlayerManager().getJobsPlayer(player).getPointsData().getCurrentPoints() - price;
            case EXP -> currencyNeeded = player.getTotalExperience() - price;
        }
        return new EconomyCheck(currencyNeeded >= 0, -currencyNeeded);
    }


    private void setupNav() {
        StaticPane pane = new StaticPane(0, 0, 9, rows);
        this.addPane(pane);


        GuiItem back = new ItemStackBuilder(Material.BLUE_STAINED_GLASS_PANE)
                .display(config.string("boostbuy-menu.back-display"))
                .lore(config.stringList("boostbuy-menu.back-lore"))
                .modelData(11013)
                .toGuiItemBuilder()
                .clickEvent(click -> {
                    click.setCancelled(true);
                    GuiUtils.constructAndShowAsync(() -> new JobsListGui(config, player), click.getWhoClicked());
                }).build();
        pane.addItem(back, 0, rows - 1);
    }

    private void setupBackground() {
        OutlinePane pane = new OutlinePane(0, rows - 1, 9, 1, Pane.Priority.LOWEST);
        pane.addItem(GuiUtils.background());
        pane.setRepeat(true);
        this.addPane(pane);
    }

    enum BuyCurrency {
        MONEY,
        POINTS,
        EXP
    }


}
