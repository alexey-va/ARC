package arc.arc.hooks;

import arc.arc.ARC;
import arc.arc.configs.Config;
import arc.arc.configs.ConfigManager;
import arc.arc.hooks.auraskills.AuraSkillsHook;
import arc.arc.hooks.bank.BankHook;
import arc.arc.hooks.betterstructures.BsHook;
import arc.arc.hooks.citizens.CitizensHook;
import arc.arc.hooks.elitemobs.EMHook;
import arc.arc.hooks.jobs.JobsHook;
import arc.arc.hooks.lands.LandsHook;
import arc.arc.hooks.lootchest.LootChestHook;
import arc.arc.hooks.luckperms.LuckPermsHook;
import arc.arc.hooks.packetevents.PacketEventsHook;
import arc.arc.hooks.slimefun.SFHook;
import arc.arc.hooks.viaversion.ViaVersionHook;
import arc.arc.hooks.worldguard.WGHook;
import arc.arc.hooks.yamipa.YamipaHook;
import arc.arc.hooks.zauction.AuctionHook;
import arc.arc.hooks.ztranslator.TranslatorHook;
import arc.arc.listeners.*;
import lombok.extern.slf4j.Slf4j;
import org.bukkit.Bukkit;

import static org.bukkit.Bukkit.getServer;

@Slf4j
public class HookRegistry {


    public static LandsHook landsHook;
    public static HuskHomesHook huskHomesHook;
    public static PartiesHook partiesHook;
    public static PAPIHook papiHook;
    public static CMIHook cmiHook;
    public static ItemsAdderHook itemsAdderHook;
    public static CitizensHook citizensHook;
    public static ViaVersionHook viaVersionHook;
    public static WGHook wgHook;
    public static ShopHook shopHook;
    public static SFHook sfHook;
    public static EMHook emHook;
    public static YamipaHook yamipaHook;
    public static LuckPermsHook luckPermsHook;
    public static LootChestHook lootChestHook;
    public static AuctionHook auctionHook;
    public static TranslatorHook translatorHook;
    public static JobsHook jobsHook;
    public static BankHook bankHook;
    public static RedisEcoHook redisEcoHook;
    public static AuraSkillsHook auraSkillsHook;
    public static BsHook bsHook;
    public static PlayerWarpsHook playerWarpsHook;
    public static PacketEventsHook packetEventsHook;
    public AEHook aeHook;


    public ChatListener chatListener;
    public CommandListener commandListener;
    public SpawnerListener spawnerListener;
    public BlockListener blockListener;
    public JoinListener joinListener;
    public PickupListener pickupListener;
    public IAEvents iaEvents;
    public BetterRTPListener betterRTPListener;
    public RespawnListener respawnListener;

    public void setupHooks() {
        registerEvents();


        if (commandListener == null) {
            Config config = ConfigManager.of(ARC.plugin.getDataPath(), "portal.yml");
            commandListener = new CommandListener(config);
            Bukkit.getPluginManager().registerEvents(commandListener, ARC.plugin);
        }
    }

    public void reloadHooks() {
        if (emHook != null) emHook.reload();
    }

    public void cancelTasks() {
        if (emHook != null) emHook.cancel();
    }

    private void registerEvents() {
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            if (getServer().getPluginManager().getPlugin("PlaceholderAPI").isEnabled()) {
                log.info("Registering PlaceholderAPI hook");
                papiHook = new PAPIHook();
                papiHook.register();
            }
        }
        if (getServer().getPluginManager().getPlugin("WorldGuard") != null) {
            if (getServer().getPluginManager().getPlugin("WorldGuard").isEnabled()) {
                log.info("Registering WorldGuard hook");
                wgHook = new WGHook();
                Bukkit.getPluginManager().registerEvents(wgHook, ARC.plugin);
            }
        }
        if (Bukkit.getPluginManager().isPluginEnabled("Slimefun") && sfHook == null) {
            log.info("Registering Slimefun hook");
            sfHook = new SFHook();
            Bukkit.getPluginManager().registerEvents(sfHook, ARC.plugin);
        }
        if (Bukkit.getPluginManager().isPluginEnabled("AdvancedEnchantments") && aeHook == null) {
            log.info("Registering AdvancedEnchantments hook");
            aeHook = new AEHook();
            Bukkit.getPluginManager().registerEvents(aeHook, ARC.plugin);
        }
        if (getServer().getPluginManager().getPlugin("EliteMobs") != null) {
            if (getServer().getPluginManager().getPlugin("EliteMobs").isEnabled()) {
                emHook = new EMHook();
            }
        }
        if (Bukkit.getPluginManager().isPluginEnabled("HuskHomes") && huskHomesHook == null) {
            log.info("Registering HuskHomes hook");
            huskHomesHook = new HuskHomesHook();
            Bukkit.getPluginManager().registerEvents(huskHomesHook, ARC.plugin);
        }
        if (getServer().getPluginManager().getPlugin("Lands") != null) {
            if (getServer().getPluginManager().getPlugin("Lands").isEnabled()) {
                log.info("Registering Lands hook");
                landsHook = new LandsHook();
            }
        }
        if (getServer().getPluginManager().getPlugin("Jobs") != null) {
            if (getServer().getPluginManager().getPlugin("Jobs").isEnabled()) {
                log.info("Registering Jobs hook");
                jobsHook = new JobsHook();
            }
        }
        if (getServer().getPluginManager().getPlugin("zAuctionHouseV3") != null) {
            if (getServer().getPluginManager().getPlugin("zAuctionHouseV3").isEnabled()) {
                log.info("Registering AuctionHouse hook");
                auctionHook = new AuctionHook();
            }
        }
        if (getServer().getPluginManager().getPlugin("Bank") != null) {
            if (getServer().getPluginManager().getPlugin("Bank").isEnabled()) {
                log.info("Registering Bank hook");
                bankHook = new BankHook();
            }
        }

        if (getServer().getPluginManager().getPlugin("RedisEconomy") != null) {
            if (getServer().getPluginManager().getPlugin("RedisEconomy").isEnabled()) {
                log.info("Registering RedisEconomy hook");
                redisEcoHook = new RedisEcoHook();
            }
        }
        log.info("Registering Translator hook");
        translatorHook = new TranslatorHook();
        if (getServer().getPluginManager().getPlugin("LuckPerms") != null) {
            if (getServer().getPluginManager().getPlugin("LuckPerms").isEnabled()) {
                log.info("Registering LuckPerms hook");
                luckPermsHook = new LuckPermsHook();
            }
        }
        if (getServer().getPluginManager().getPlugin("AuraSkills") != null) {
            if (getServer().getPluginManager().getPlugin("AuraSkills").isEnabled()) {
                log.info("Registering AuraSkills hook");
                auraSkillsHook = new AuraSkillsHook();
            }
        }
        if (getServer().getPluginManager().getPlugin("CMI") != null) {
            if (getServer().getPluginManager().getPlugin("CMI").isEnabled()) {
                log.info("Registering CMI hook");
                cmiHook = new CMIHook();
                Config config = ConfigManager.of(ARC.plugin.getDataPath(), "portal.yml");
                CMIListener cmiListener = new CMIListener(config);
                Bukkit.getPluginManager().registerEvents(cmiListener, ARC.plugin);
            }
        }
        if (getServer().getPluginManager().getPlugin("ViaVersion") != null) {
            if (getServer().getPluginManager().getPlugin("ViaVersion").isEnabled()) {
                log.info("Registering ViaVersion hook");
                viaVersionHook = new ViaVersionHook();
            }
        }
        if (getServer().getPluginManager().getPlugin("packetevents") != null) {
            if (getServer().getPluginManager().getPlugin("packetevents").isEnabled()) {
                log.info("Registering packetevents hook");
                packetEventsHook = new PacketEventsHook();
            }
        }
        if (getServer().getPluginManager().getPlugin("PlayerWarps") != null) {
            if (getServer().getPluginManager().getPlugin("PlayerWarps").isEnabled()) {
                log.info("Registering PlayerWarps hook");
                playerWarpsHook = new PlayerWarpsHook();
            }
        }
        if (getServer().getPluginManager().getPlugin("LootChest") != null) {
            if (getServer().getPluginManager().getPlugin("LootChest").isEnabled()) {
                log.info("Registering LootChest hook");
                System.out.println("Registering LootChest hook");
                lootChestHook = new LootChestHook();
            }
        }
        if (getServer().getPluginManager().getPlugin("YamipaPlugin") != null) {
            if (getServer().getPluginManager().getPlugin("YamipaPlugin").isEnabled()) {
                log.info("Registering Yamipa hook");
                yamipaHook = new YamipaHook();
            }
        }
        if (getServer().getPluginManager().getPlugin("ItemsAdder") != null) {
            if (getServer().getPluginManager().getPlugin("ItemsAdder").isEnabled()) {
                log.info("Registering ItemsAdder hook");
                itemsAdderHook = new ItemsAdderHook();
            }
        }
        if (getServer().getPluginManager().getPlugin("Citizens") != null) {
            if (getServer().getPluginManager().getPlugin("Citizens").isEnabled()) {
                log.info("Registering Citizens hook");
                citizensHook = new CitizensHook();
                citizensHook.registerListeners();
            }
        }
        if (getServer().getPluginManager().getPlugin("BetterRTP") != null) {
            if (getServer().getPluginManager().getPlugin("BetterRTP").isEnabled()) {
                log.info("Registering BetterRTP hook");
                if(betterRTPListener == null) {
                    betterRTPListener = new BetterRTPListener();
                    Bukkit.getPluginManager().registerEvents(betterRTPListener, ARC.plugin);
                }
            }
        }
        if (chatListener == null) {
            chatListener = new ChatListener();
            Bukkit.getPluginManager().registerEvents(chatListener, ARC.plugin);
        }
        if(respawnListener == null) {
            respawnListener = new RespawnListener();
            Bukkit.getPluginManager().registerEvents(respawnListener, ARC.plugin);
        }
        if (spawnerListener == null) {
            spawnerListener = new SpawnerListener();
            Bukkit.getPluginManager().registerEvents(spawnerListener, ARC.plugin);
        }

        if (getServer().getPluginManager().getPlugin("Parties") != null) {
            if (getServer().getPluginManager().getPlugin("Parties").isEnabled()) {
                partiesHook = new PartiesHook();
            }
        }
        if (bsHook == null && getServer().getPluginManager().getPlugin("BetterStructures") != null) {
            if (getServer().getPluginManager().getPlugin("BetterStructures").isEnabled()) {
                log.info("Registering BetterStructures hook");
                bsHook = new BsHook();
            }
        }
        if ((Bukkit.getPluginManager().isPluginEnabled("EconomyShopGUI") || Bukkit.getPluginManager().isPluginEnabled("EconomyShopGUI-Premium")) && shopHook == null) {
            log.info("Registering Shop hook");
            shopHook = new ShopHook();
        }

        if (joinListener == null) {
            joinListener = new JoinListener();
            Bukkit.getPluginManager().registerEvents(joinListener, ARC.plugin);
        }

        if (blockListener == null) {
            blockListener = new BlockListener();
            Bukkit.getPluginManager().registerEvents(blockListener, ARC.plugin);
        }

        if (pickupListener == null) {
            pickupListener = new PickupListener();
            Bukkit.getPluginManager().registerEvents(pickupListener, ARC.plugin);
        }

        if (iaEvents == null) {
            iaEvents = new IAEvents();
            Bukkit.getPluginManager().registerEvents(iaEvents, ARC.plugin);
        }
    }

}
