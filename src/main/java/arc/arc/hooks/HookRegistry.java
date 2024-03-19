package arc.arc.hooks;

import arc.arc.ARC;
import arc.arc.configs.MainConfig;
import arc.arc.farm.*;
import arc.arc.hooks.citizens.CitizensHook;
import arc.arc.hooks.elitemobs.EMHook;
import arc.arc.hooks.jobs.JobsHook;
import arc.arc.hooks.lands.LandsHook;
import arc.arc.hooks.lootchest.LootChestHook;
import arc.arc.hooks.luckperms.LuckPermsHook;
import arc.arc.hooks.viaversion.ViaVersionHook;
import arc.arc.hooks.worldguard.WGHook;
import arc.arc.hooks.yamipa.YamipaHook;
import arc.arc.hooks.zauction.AuctionHook;
import arc.arc.hooks.ztranslator.TranslatorHook;
import arc.arc.listeners.*;
import org.bukkit.Bukkit;



import static org.bukkit.Bukkit.getServer;

public class HookRegistry {


    public static PSHook psHook;
    public static LandsHook landsHook;
    public static HuskHomesHook huskHomesHook;
    public static PartiesHook partiesHook;
    public static PAPIHook papiHook;
    public static CMIHook cmiHook;
    public static FarmManager farmManager;
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
    public AEHook aeHook;


    public ChatListener chatListener;
    public CommandListener commandListener;
    public SpawnerListener spawnerListener;
    public BlockListener blockListener;
    public JoinListener joinListener;

    public void setupHooks() {
        registerEvents();

        if (psHook != null) psHook.init();
        papiHook = new PAPIHook();
        papiHook.register();

        if (MainConfig.enablePortals && commandListener == null) {
            commandListener = new CommandListener();
            Bukkit.getPluginManager().registerEvents(commandListener, ARC.plugin);
        }
    }

    public void reloadHooks(){
        if(emHook != null) emHook.reload();
    }

    public void cancelTasks() {
        if (emHook != null) emHook.cancel();
        if (psHook != null) psHook.cancel();
        if (farmManager != null) farmManager.clear();
    }

    private void registerEvents() {
        if (Bukkit.getPluginManager().isPluginEnabled("ProtectionStones") && psHook == null) {
            psHook = new PSHook();
            Bukkit.getPluginManager().registerEvents(psHook, ARC.plugin);
        }
        if (Bukkit.getPluginManager().isPluginEnabled("WorldGuard") && wgHook == null) {
            wgHook = new WGHook();
            farmManager = new FarmManager();
            Bukkit.getPluginManager().registerEvents(wgHook, ARC.plugin);
        }
        if (Bukkit.getPluginManager().isPluginEnabled("Slimefun") && sfHook == null) {
            sfHook = new SFHook();
            Bukkit.getPluginManager().registerEvents(sfHook, ARC.plugin);
        }
        if (Bukkit.getPluginManager().isPluginEnabled("AdvancedEnchantments") && aeHook == null) {
            aeHook = new AEHook();
            Bukkit.getPluginManager().registerEvents(aeHook, ARC.plugin);
        }
        if (getServer().getPluginManager().getPlugin("EliteMobs") != null) {
            if (getServer().getPluginManager().getPlugin("EliteMobs").isEnabled()) {
                emHook = new EMHook();
            }
        }
        if (Bukkit.getPluginManager().isPluginEnabled("HuskHomes") && huskHomesHook == null) {
            huskHomesHook = new HuskHomesHook();
            Bukkit.getPluginManager().registerEvents(huskHomesHook, ARC.plugin);
        }
        if (getServer().getPluginManager().getPlugin("Lands") != null) {
            if (getServer().getPluginManager().getPlugin("Lands").isEnabled()) {
                landsHook = new LandsHook();
            }
        }
        if (getServer().getPluginManager().getPlugin("Jobs") != null) {
            if (getServer().getPluginManager().getPlugin("Jobs").isEnabled()) {
                jobsHook = new JobsHook();
            }
        }
        if (getServer().getPluginManager().getPlugin("zAuctionHouseV3") != null) {
            if (getServer().getPluginManager().getPlugin("zAuctionHouseV3").isEnabled()) {
                auctionHook = new AuctionHook();
            }
        }
        if (getServer().getPluginManager().getPlugin("zTranslator") != null) {
            if (getServer().getPluginManager().getPlugin("zTranslator").isEnabled()) {
                translatorHook = new TranslatorHook();
            }
        }
        if (getServer().getPluginManager().getPlugin("LuckPerms") != null) {
            if (getServer().getPluginManager().getPlugin("LuckPerms").isEnabled()) {
                luckPermsHook = new LuckPermsHook();
            }
        }
        if (getServer().getPluginManager().getPlugin("CMI") != null) {
            if (getServer().getPluginManager().getPlugin("CMI").isEnabled()) {
                cmiHook = new CMIHook();
            }
        }
        if (getServer().getPluginManager().getPlugin("ViaVersion") != null) {
            if (getServer().getPluginManager().getPlugin("ViaVersion").isEnabled()) {
                viaVersionHook = new ViaVersionHook();
            }
        }
        if (getServer().getPluginManager().getPlugin("LootChest") != null) {
            if (getServer().getPluginManager().getPlugin("LootChest").isEnabled()) {
                System.out.println("Registering LootChest hook");
                lootChestHook = new LootChestHook();
            }
        }
        if (getServer().getPluginManager().getPlugin("YamipaPlugin") != null) {
            if (getServer().getPluginManager().getPlugin("YamipaPlugin").isEnabled()) {
                yamipaHook = new YamipaHook();
            }
        }
        if (getServer().getPluginManager().getPlugin("ItemsAdder") != null) {
            if (getServer().getPluginManager().getPlugin("ItemsAdder").isEnabled()) {
                itemsAdderHook = new ItemsAdderHook();
            }
        }
        if (getServer().getPluginManager().getPlugin("Citizens") != null) {
            if (getServer().getPluginManager().getPlugin("Citizens").isEnabled()) {
                citizensHook = new CitizensHook();
                citizensHook.registerListeners();
            }
        }
        if (getServer().getPluginManager().getPlugin("WorldGuard") != null) {
            if (getServer().getPluginManager().getPlugin("WorldGuard").isEnabled()) {
                try {
                    farmManager = new FarmManager();
                    farmManager.init();
                } catch (Exception e){
                    e.printStackTrace();
                }
            }
        }
        if (chatListener == null) {
            chatListener = new ChatListener();
            Bukkit.getPluginManager().registerEvents(chatListener, ARC.plugin);
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
        if ((Bukkit.getPluginManager().isPluginEnabled("EconomyShopGUI") || Bukkit.getPluginManager().isPluginEnabled("EconomyShopGUI-Premium")) && shopHook == null) {
            shopHook = new ShopHook();
            Bukkit.getPluginManager().registerEvents(shopHook, ARC.plugin);
        }

        if(joinListener == null){
            joinListener = new JoinListener();
            Bukkit.getPluginManager().registerEvents(joinListener, ARC.plugin);
        }

        if(blockListener == null){
            blockListener = new BlockListener();
            Bukkit.getPluginManager().registerEvents(blockListener, ARC.plugin);
        }

    }

}
