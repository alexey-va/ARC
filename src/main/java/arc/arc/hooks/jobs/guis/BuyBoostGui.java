package arc.arc.hooks.jobs.guis;

import arc.arc.ARC;
import arc.arc.configs.Config;
import arc.arc.hooks.HookRegistry;
import arc.arc.hooks.jobs.JobsBoost;
import arc.arc.util.GuiUtils;
import arc.arc.util.ItemStackBuilder;
import com.gamingmesh.jobs.Jobs;
import com.gamingmesh.jobs.container.Job;
import com.github.stefvanschie.inventoryframework.adventuresupport.TextHolder;
import com.github.stefvanschie.inventoryframework.gui.GuiItem;
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui;
import com.github.stefvanschie.inventoryframework.pane.OutlinePane;
import com.github.stefvanschie.inventoryframework.pane.Pane;
import com.github.stefvanschie.inventoryframework.pane.StaticPane;
import lombok.extern.log4j.Log4j2;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static arc.arc.util.TextUtil.*;

@Log4j2
public class BuyBoostGui extends ChestGui {

    Player player;
    Job job;
    JobsBoost.Type type = JobsBoost.Type.MONEY;
    Config config;
    int rows = 3;
    GuiItem backItem, typeItem;
    StaticPane pane;

    Map<JobsBoost.Type, List<Boost>> boosts = new ConcurrentHashMap<>();

    record Boost(String display, List<String> lore, double price, double boostAmount, long seconds,
                 String permission, Material material, int modelData, BuyCurrency currency, String id,
                 List<String> jobs, List<JobsBoost.Type> types) {
    }

    public BuyBoostGui(Player player, Job job, Config config) {
        super(3, "");
        setTitle(TextHolder.deserialize(
                toLegacy(config.string("boostbuy-menu.title", "Магазин бустов"))
        ));

        this.player = player;
        this.job = job;
        this.config = config;

        for (JobsBoost.Type type : JobsBoost.Type.values()) {
            readBoosts(type);
        }
        var nonEmptyTypes = Arrays.stream(JobsBoost.Type.values()).filter(t -> !boosts.get(t).isEmpty()).toList();
        if(!nonEmptyTypes.isEmpty()) type = nonEmptyTypes.get(0);

        calculateRows();

        setupBackground();
        setupNav();
        setupBoosts();
    }

    private void calculateRows() {
        rows = Math.min(6, Math.max(2, (int) Math.ceil(boosts.get(type).size() / 7.0) + 2));
        setRows(rows);
    }

    private void setupBoosts() {
        if (pane == null) {
            pane = new StaticPane(1, 1, 7, rows - 2);

            this.addPane(pane);
        }
        pane.clear();

        List<GuiItem> items = new ArrayList<>();
        List<Boost> currentBoosts = boosts.get(type);
        if (currentBoosts != null) {
            for (Boost boost : currentBoosts) {
                GuiItem item = generateGuiItem(boost);
                if (item != null) items.add(item);
            }
        }
        int x = 0, y=0;
        for(var guiItem : items){
            if(x == 3) x++;
            pane.addItem(guiItem, x++,y);
            if(x == 7){
                x = 0;
                y++;
            }
        }
    }


    private void readBoosts(JobsBoost.Type type) {
        boosts.computeIfPresent(type, (t, list) -> {
            list.clear();
            return list;
        });
        boosts.putIfAbsent(type, new ArrayList<>());
        for (String key : config.keys("boosts." + type.name().toLowerCase())) {
            String display = config.string("boosts." + type.name().toLowerCase() + "." + key + ".display");
            List<String> lore = config.stringList("boosts." + type.name().toLowerCase() + "." + key + ".lore");
            double price = config.real("boosts." + type.name().toLowerCase() + "." + key + ".price", 1000);
            double boostAmount = config.real("boosts." + type.name().toLowerCase() + "." + key + ".boost-amount", 0.1);
            long seconds = config.integer("boosts." + type.name().toLowerCase() + "." + key + ".seconds", 3600);
            String permission = config.string("boosts." + type.name().toLowerCase() + "." + key + ".permission", "");
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
            boosts.get(type).add(data);
        }
    }

    GuiItem generateGuiItem(Boost boost) {
        boolean allJobs = boost.jobs().isEmpty() || boost.jobs().contains("all");
        boolean allTypes = boost.types().contains(JobsBoost.Type.ALL) || boost.types().isEmpty();
        if (job == null && !allJobs) return null;
        if (job != null && !allJobs && !boost.jobs().contains(job.getName().toLowerCase())) return null;

        EconomyCheck economyCheck = checkEconomy(boost.currency(), boost.price());
        List<String> lore;
        String currencyName = config.string("currency-names." + boost.currency().name().toLowerCase(), "Money");
        boolean hasBoost = HookRegistry.jobsHook.hasBoost(player, boost.id());

        if (hasBoost) {
            lore = config.stringList("boostbuy-menu.already-have-boost-lore");
        } else if (boost.permission() != null && !boost.permission.isEmpty() && !player.hasPermission(boost.permission())) {
            lore = config.stringList("boostbuy-menu.no-permission-lore");
        } else if (!economyCheck.hasEnough) {
            lore = config.stringList("boostbuy-menu.not-enough-money-lore");
        } else {
            lore = boost.lore() == null || boost.lore().isEmpty() ? config.stringList("boostbuy-menu.boost-lore") : boost.lore();
        }

        double playerCurrency = getCurrency().get(boost.currency());
        String boostAmount = boost.boostAmount() * 100 + "%";

        lore = lore.stream().map(s -> s
                .replace("<price>", formatAmount(boost.price()))
                .replace("<boost>", boostAmount)
                .replace("<currency>", currencyName)
                .replace("<permission>", boost.permission() != null ? boost.permission() : "Нет")
                .replace("<time>", boost.seconds() / 60 + " минут")
                .replace("<type>", allTypes ? "Все" : boost.types().stream().map(JobsBoost.Type::name).collect(Collectors.joining(", ")))
                .replace("<job>", allJobs ? "Все" : boost.jobs().stream().map(HookRegistry.jobsHook::jobDisplayMinimessage).collect(Collectors.joining(", ")))
                .replace("<player_currency>", formatAmount(playerCurrency))
                .replace("<currency_lack>", formatAmount(economyCheck.currencyNeeded))).toList();
        final GuiItem item = new ItemStackBuilder(boost.material())
                .display(boost.display())
                .enchant(hasBoost ? Enchantment.VANISHING_CURSE : null, 1, true)
                .flags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_ATTRIBUTES)
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

            EconomyCheck ec = checkEconomy(boost.currency(), boost.price());
            if (!ec.hasEnough()) {
                GuiUtils.temporaryChange(item.getItem(), mm(config.string("boostbuy-menu.not-enough-money"), true),
                        null, 60, this::update);
                this.update();
                return;
            } else takeCurrency(boost.currency(), boost.price());

            HookRegistry.jobsHook.addBoost(player.getUniqueId(), boost.jobs(), boost.boostAmount(),
                    System.currentTimeMillis() + boost.seconds() * 1000L, boost.id(), boost.types());
            GuiItem newItem = generateGuiItem(boost);
            item.setItem(newItem.getItem());
            update();
        });
        return item;
    }

    private Map<BuyCurrency, Double> getCurrency() {
        return Map.of(
                BuyCurrency.MONEY, ARC.getEcon().getBalance(player),
                BuyCurrency.POINTS, Jobs.getPlayerManager().getJobsPlayer(player).getPointsData().getCurrentPoints(),
                BuyCurrency.EXP, (double) player.getTotalExperience()
        );
    }

    private boolean takeCurrency(BuyCurrency currency, double price) {
        switch (currency) {
            case MONEY -> ARC.getEcon().withdrawPlayer(player, price);
            case POINTS -> Jobs.getPlayerManager()
                    .getJobsPlayer(player)
                    .getPointsData()
                    .setPoints(
                            Jobs.getPlayerManager().getJobsPlayer(player).getPointsData().getCurrentPoints() - price
                    );
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

        TagResolver resolver = resolver();

        backItem = new ItemStackBuilder(Material.BLUE_STAINED_GLASS_PANE)
                .display(config.string("boostbuy-menu.back-display"))
                .lore(config.stringList("boostbuy-menu.back-lore"))
                .tagResolver(resolver)
                .modelData(11013)
                .toGuiItemBuilder()
                .clickEvent(click -> {
                    click.setCancelled(true);
                    GuiUtils.constructAndShowAsync(() -> new JobsListGui(config, player), click.getWhoClicked());
                }).build();
        pane.addItem(backItem, 0, rows - 1);



        TypeStackData typeStackData = getTypeStackData(type);
        typeItem = new ItemStackBuilder(typeStackData.material)
                .modelData(typeStackData.modelData)
                .display(config.string("boostbuy-menu.type-display"))
                .lore(config.stringList("boostbuy-menu.type-lore"))
                .tagResolver(resolver)
                .toGuiItemBuilder()
                .clickEvent(click -> {
                    click.setCancelled(true);
                    var nonEmptyTypes = Arrays.stream(JobsBoost.Type.values()).filter(t -> !boosts.get(t).isEmpty()).toList();
                    if (nonEmptyTypes.size() <= 1) return;
                    type = nonEmptyTypes.get((nonEmptyTypes.indexOf(type) + 1) % nonEmptyTypes.size());

                    TypeStackData tsd = getTypeStackData(type);
                    var stack = new ItemStackBuilder(tsd.material)
                            .modelData(tsd.modelData)
                            .display(config.string("boostbuy-menu.type-display"))
                            .lore(config.stringList("boostbuy-menu.type-lore"))
                            .tagResolver(resolver())
                            .build();
                    typeItem.setItem(stack);
                    setupBoosts();
                    this.update();
                }).build();
        pane.addItem(typeItem, 4, 0);
    }

    TagResolver resolver() {
        return TagResolver.builder()
                .tag("type", Tag.inserting(mm(config.string("tpye-names." + type.name().toLowerCase(), "Money"), true)))
                .build();
    }

    private void setupBackground() {
        OutlinePane pane = new OutlinePane(0, 0, 9, rows, Pane.Priority.LOWEST);
        pane.addItem(GuiUtils.background());
        pane.setRepeat(true);
        this.addPane(pane);
    }

    record TypeStackData(Material material, int modelData){}
    private TypeStackData getTypeStackData(JobsBoost.Type type) {
        Material material = Material.GOLD_INGOT;
        int modelData = 0;
        if (type == JobsBoost.Type.EXP) material = Material.EXPERIENCE_BOTTLE;
        if (type == JobsBoost.Type.MONEY){
            material = Material.STICK;
            modelData = 11138;
        }
        if (type == JobsBoost.Type.POINTS) material = Material.NETHER_STAR;
        return new TypeStackData(material, modelData);
    }

    enum BuyCurrency {
        MONEY,
        POINTS,
        EXP
    }


}
