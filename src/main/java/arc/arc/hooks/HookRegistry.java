package arc.arc.hooks;

import arc.arc.ARC;
import arc.arc.configs.Config;
import arc.arc.farm.*;
import arc.arc.hooks.lands.LandsHook;
import arc.arc.listeners.*;
import org.bukkit.Bukkit;

import java.util.ArrayList;
import java.util.List;

import static org.bukkit.Bukkit.getServer;

public class HookRegistry {


    public static PSHook psHook;
    public static LandsHook landsHook;
    public static HuskHomesHook huskHomesHook;
    public static PartiesHook partiesHook;
    public static PAPIHook papiHook;
    public static CMIHook cmiHook;
    public static FarmManager farmManager;
    public WGHook wgHook;
    public ShopHook shopHook;
    public SFHook sfHook;
    public EMHook emHook;
    public AEHook aeHook;

    public ChatListener chatListener;
    public CommandListener commandListener;
    public SpawnerListener spawnerListener;
    public BlockListener blockListener;
    public JoinListener joinListener;

    List<ArcModule> arcModuleList = new ArrayList<>();

    public void setupHooks() {
        registerEvents();

        if (emHook != null) emHook.init();
        if (psHook != null) psHook.init();
        papiHook = new PAPIHook();
        papiHook.register();

        if (Config.enablePortals && commandListener == null) {
            commandListener = new CommandListener();
            Bukkit.getPluginManager().registerEvents(commandListener, ARC.plugin);
        }
    }

    public void cleanHooks() {
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
        if (Bukkit.getPluginManager().isPluginEnabled("EliteMobs") && emHook == null) {
            emHook = new EMHook();
            Bukkit.getPluginManager().registerEvents(emHook, ARC.plugin);
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
        if (getServer().getPluginManager().getPlugin("CMI") != null) {
            if (getServer().getPluginManager().getPlugin("CMI").isEnabled()) {
                cmiHook = new CMIHook();
            }
        }
        if (getServer().getPluginManager().getPlugin("WorldGuard") != null) {
            if (getServer().getPluginManager().getPlugin("WorldGuard").isEnabled()) {
                farmManager = new FarmManager();
                farmManager.init();
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
                arcModuleList.add(partiesHook);
            }
        }
        if ((Bukkit.getPluginManager().isPluginEnabled("EconomyShopGUI") || Bukkit.getPluginManager().isPluginEnabled("EconomyShopGUI-Premium")) && shopHook == null) {
            shopHook = new ShopHook();
            arcModuleList.add(shopHook);
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
