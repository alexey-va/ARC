package ru.arc.hooks;

import java.util.HashSet;
import java.util.Set;

import org.bukkit.Bukkit;
import ru.arc.ARC;
import ru.arc.hooks.auraskills.AuraSkillsHook;
import ru.arc.hooks.bank.BankHook;
import ru.arc.hooks.betterstructures.BSListener;
import ru.arc.hooks.citizens.CitizensHook;
import ru.arc.hooks.elitemobs.EMHook;
import ru.arc.hooks.elitemobs.EMListener;
import ru.arc.hooks.lands.LandsHook;
import ru.arc.hooks.lootchest.LootChestHook;
import ru.arc.hooks.luckperms.LuckPermsHook;
import ru.arc.hooks.packetevents.PacketEventsHook;
import ru.arc.hooks.slimefun.BackpackBlockListener;
import ru.arc.hooks.slimefun.SFHook;
import ru.arc.hooks.viaversion.ViaVersionHook;
import ru.arc.hooks.worldguard.WGHook;
import ru.arc.hooks.yamipa.YamipaHook;
import ru.arc.hooks.zauction.AuctionHook;
import ru.arc.hooks.ztranslator.TranslatorHook;
import ru.arc.jobs.JobsModule;
import ru.arc.listeners.BlockListener;
import ru.arc.listeners.CMIListener;
import ru.arc.listeners.ChatListener;
import ru.arc.listeners.CommandListener;
import ru.arc.listeners.IAEvents;
import ru.arc.listeners.JoinListener;
import ru.arc.listeners.PickupListener;
import ru.arc.listeners.RespawnListener;
import ru.arc.listeners.SpawnerListener;

import static org.bukkit.Bukkit.getServer;
import static ru.arc.util.Logging.debug;
import static ru.arc.util.Logging.error;
import static ru.arc.util.Logging.info;

public class HookRegistry {

    public static LandsHook landsHook;
    public static HuskHomesHook huskHomesHook;
    public static PAPIHook papiHook;
    public static CMIHook cmiHook;
    public static ItemsAdderHook itemsAdderHook;
    public static CitizensHook citizensHook;
    public static ViaVersionHook viaVersionHook;
    public static WGHook wgHook;
    public static SFHook sfHook;
    public static EMHook emHook;
    public static YamipaHook yamipaHook;
    public static LuckPermsHook luckPermsHook;
    public static LootChestHook lootChestHook;
    public static AuctionHook auctionHook;
    public static TranslatorHook translatorHook;
    public static boolean jobsEnabled = false;
    public static BankHook bankHook;
    public static RedisEcoHook redisEcoHook;
    public static AuraSkillsHook auraSkillsHook;
    public static PlayerWarpsHook playerWarpsHook;
    public static PacketEventsHook packetEventsHook;
    public static AEHook aeHook;


    public ChatListener chatListener;
    public CommandListener commandListener;
    public SpawnerListener spawnerListener;
    public BlockListener blockListener;
    public JoinListener joinListener;
    public PickupListener pickupListener;
    public IAEvents iaEvents;
    public BetterRTPListener betterRTPListener;
    public RespawnListener respawnListener;
    public BSListener bsListener;
    public ShopListener shopListener;
    public EMListener emListener;

    public void setupHooks() {
        registerVanillaEvents();
        registerHooks();
    }

    public void cancelTasks() {
        if (emHook != null) emHook.cancel();
    }

    private static final Set<String> registeredHooks = new HashSet<>();

    private static void register(String pluginName, boolean single, Runnable runnable) {
        if (registeredHooks.contains(pluginName)) {
            info("Plugin {} already registered", pluginName);
            return;
        }
        if (getServer().getPluginManager().getPlugin(pluginName) != null) {
            info("Registering {} hook", pluginName);
            try {
                runnable.run();
                if (single) {
                    registeredHooks.add(pluginName);
                }
            } catch (Throwable e) {
                error("Error registering {} hook", pluginName, e);
                debug("Hook {} registration failed: {}", pluginName, e.getMessage());
            }
        } else {
            debug("Plugin {} not installed — hook skipped", pluginName);
        }
    }

    /** Registers a hook when any of the given plugin names is present (V3/V4 rename, etc.). */
    private static void registerFirstAvailable(boolean single, Runnable runnable, String... pluginNames) {
        for (String pluginName : pluginNames) {
            if (getServer().getPluginManager().getPlugin(pluginName) != null) {
                register(pluginName, single, runnable);
                return;
            }
        }
        info("Unable to find plugin '{}'", String.join("' or '", pluginNames));
    }

    private void registerHooks() {
        register("PlaceholderAPI", true, () -> {
            papiHook = new PAPIHook();
            papiHook.register();
        });
        register("WorldGuard", true, () -> {
            wgHook = new WGHook();
            Bukkit.getPluginManager().registerEvents(wgHook, ARC.getInstance());
        });

        register("Slimefun", true, () -> {
            sfHook = new SFHook();
            Bukkit.getPluginManager().registerEvents(sfHook, ARC.getInstance());
            Bukkit.getPluginManager().registerEvents(BackpackBlockListener.INSTANCE, ARC.getInstance());
        });

        register("AdvancedEnchantments", true, () -> {
            aeHook = new AEHook();
            Bukkit.getPluginManager().registerEvents(aeHook, ARC.getInstance());
        });

        register("EliteMobs", false, () -> {
            if (emHook == null) emHook = new EMHook();
            emHook.reload();
            if (emListener == null) {
                emListener = new EMListener();
                Bukkit.getPluginManager().registerEvents(emListener, ARC.getInstance());
            }
        });

        register("HuskHomes", true, () -> {
            huskHomesHook = new HuskHomesHook();
            Bukkit.getPluginManager().registerEvents(huskHomesHook, ARC.getInstance());
        });

        register("Lands", true, () -> landsHook = new LandsHook());

        register("Jobs", true, () -> {
            JobsModule.init();
            jobsEnabled = true;
        });

        registerFirstAvailable(true, () -> auctionHook = new AuctionHook(), "zAuctionHouse", "zAuctionHouseV3");

        register("Bank", true, () -> bankHook = new BankHook());

        register("RedisEconomy", true, () -> {
            redisEcoHook = new RedisEcoHook();
            RedisEcoListener redisEcoListener = new RedisEcoListener();
            Bukkit.getPluginManager().registerEvents(redisEcoListener, ARC.getInstance());
        });

        translatorHook = new TranslatorHook();

        register("LuckPerms", true, () -> luckPermsHook = new LuckPermsHook());

        register("AuraSkills", true, () -> auraSkillsHook = new AuraSkillsHook());

        register("CMI", true, () -> {
            cmiHook = new CMIHook();
            CMIListener cmiListener = new CMIListener();
            Bukkit.getPluginManager().registerEvents(cmiListener, ARC.getInstance());
        });

        register("ViaVersion", true, () -> viaVersionHook = new ViaVersionHook());

        register("packetevents", true, () -> packetEventsHook = new PacketEventsHook());

        register("PlayerWarps", true, () -> playerWarpsHook = new PlayerWarpsHook());

        register("LootChest", true, () -> lootChestHook = new LootChestHook());

        register("YamipaPlugin", true, () -> yamipaHook = new YamipaHook());

        register("ItemsAdder", true, () -> {
            itemsAdderHook = new ItemsAdderHook();
            iaEvents = new IAEvents();
            Bukkit.getPluginManager().registerEvents(iaEvents, ARC.getInstance());
        });

        register("Citizens", true, () -> {
            citizensHook = new CitizensHook();
            citizensHook.registerListeners();
        });

        register("BetterRTP", true, () -> {
            betterRTPListener = new BetterRTPListener();
            Bukkit.getPluginManager().registerEvents(betterRTPListener, ARC.getInstance());
        });

        register("BetterStructures", true, () -> {
            bsListener = new BSListener();
            Bukkit.getPluginManager().registerEvents(bsListener, ARC.getInstance());
        });

        register("EconomyShopGUI-Premium", true, () -> {
            shopListener = new ShopListener();
            Bukkit.getPluginManager().registerEvents(shopListener, ARC.getInstance());
        });


    }

    private void registerVanillaEvents() {
        if (chatListener == null) {
            chatListener = new ChatListener();
            Bukkit.getPluginManager().registerEvents(chatListener, ARC.getInstance());
        }

        if (respawnListener == null) {
            respawnListener = new RespawnListener();
            Bukkit.getPluginManager().registerEvents(respawnListener, ARC.getInstance());
        }

        if (spawnerListener == null) {
            spawnerListener = new SpawnerListener();
            Bukkit.getPluginManager().registerEvents(spawnerListener, ARC.getInstance());
        }

        if (joinListener == null) {
            joinListener = new JoinListener();
            Bukkit.getPluginManager().registerEvents(joinListener, ARC.getInstance());
        }

        if (blockListener == null) {
            blockListener = new BlockListener();
            Bukkit.getPluginManager().registerEvents(blockListener, ARC.getInstance());
        }

        if (pickupListener == null) {
            pickupListener = new PickupListener();
            Bukkit.getPluginManager().registerEvents(pickupListener, ARC.getInstance());
        }

        if (commandListener == null) {
            commandListener = new CommandListener();
            Bukkit.getPluginManager().registerEvents(commandListener, ARC.getInstance());
        }
    }

}
