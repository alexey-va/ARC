package arc.arc.hooks;

import arc.arc.ARC;
import arc.arc.Config;
import arc.arc.farm.*;
import arc.arc.hooks.lands.LandsHook;
import arc.arc.listeners.ChatListener;
import arc.arc.listeners.CommandListener;
import arc.arc.listeners.JoinListener;
import arc.arc.listeners.SpawnerListener;
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
    public WGHook wgHook;
    public ShopHook shopHook;
    public SFHook sfHook;
    public EMHook emHook;
    public AEHook aeHook;
    public ChatListener chatListener;
    public LumberListener lumberListener;
    public CommandListener commandListener;
    public MineListener mineHook;
    public FarmListener farmHook;
    public SpawnerListener spawnerListener;
    public Mine mine;
    public Farm farm;
    public JoinListener joinListener;
    List<ArcModule> arcModuleList = new ArrayList<>();

    public void setupHooks() {
        registerEvents();

        if (emHook != null) emHook.boot();
        if (psHook != null) psHook.boot();
        papiHook = new PAPIHook();
        papiHook.register();
        if (Config.enablePortals && commandListener == null) {
            commandListener = new CommandListener();
            Bukkit.getPluginManager().registerEvents(commandListener, ARC.plugin);
        }

        if (mineHook != null) {
            mineHook.setupMines();
        }
        if (farmHook != null) {
            farm = new Farm();
            farmHook.farm = farm;
        }
        if (lumberListener != null) {
            lumberListener = new LumberListener();
        }
    }

    public void cleanHooks() {
        if (emHook != null) emHook.cancel();
        if (psHook != null) psHook.cancel();
        if (mine != null) mine.cancel();
    }

    private void registerEvents() {
        if (Bukkit.getPluginManager().isPluginEnabled("ProtectionStones") && psHook == null) {
            psHook = new PSHook();
            Bukkit.getPluginManager().registerEvents(psHook, ARC.plugin);
            arcModuleList.add(psHook);
        }
        if (Bukkit.getPluginManager().isPluginEnabled("WorldGuard") && wgHook == null) {
            wgHook = new WGHook();
            arcModuleList.add(wgHook);
            Bukkit.getPluginManager().registerEvents(wgHook, ARC.plugin);
        }
        if (Bukkit.getPluginManager().isPluginEnabled("Slimefun") && sfHook == null) {
            sfHook = new SFHook();
            arcModuleList.add(sfHook);
            Bukkit.getPluginManager().registerEvents(sfHook, ARC.plugin);
        }
        if (Bukkit.getPluginManager().isPluginEnabled("AdvancedEnchantments") && aeHook == null) {
            aeHook = new AEHook();
            arcModuleList.add(aeHook);
            Bukkit.getPluginManager().registerEvents(aeHook, ARC.plugin);
        }
        if (Bukkit.getPluginManager().isPluginEnabled("EliteMobs") && emHook == null) {
            emHook = new EMHook();
            arcModuleList.add(emHook);
            Bukkit.getPluginManager().registerEvents(emHook, ARC.plugin);
        }
        if (Bukkit.getPluginManager().isPluginEnabled("HuskHomes") && huskHomesHook == null) {
            huskHomesHook = new HuskHomesHook();
            arcModuleList.add(huskHomesHook);
            Bukkit.getPluginManager().registerEvents(huskHomesHook, ARC.plugin);
        }
        if (getServer().getPluginManager().getPlugin("Lands") != null) {
            if (getServer().getPluginManager().getPlugin("Lands").isEnabled()) {
                landsHook = new LandsHook();
                arcModuleList.add(landsHook);
            }
        }
        if (Bukkit.getPluginManager().isPluginEnabled("WorldGuard") && mineHook == null) {
            mineHook = new MineListener();
            arcModuleList.add(mineHook);
            Bukkit.getPluginManager().registerEvents(mineHook, ARC.plugin);
        }
        if (Bukkit.getPluginManager().isPluginEnabled("WorldGuard") && farmHook == null) {
            farmHook = new FarmListener();
            arcModuleList.add(farmHook);
            Bukkit.getPluginManager().registerEvents(farmHook, ARC.plugin);
        }
        if (chatListener == null) {
            chatListener = new ChatListener();
            Bukkit.getPluginManager().registerEvents(chatListener, ARC.plugin);
        }
        if (spawnerListener == null) {
            spawnerListener = new SpawnerListener();
            Bukkit.getPluginManager().registerEvents(spawnerListener, ARC.plugin);
        }
        if (Bukkit.getPluginManager().isPluginEnabled("WorldGuard") && lumberListener == null) {
            lumberListener = new LumberListener();
            arcModuleList.add(lumberListener);
            Bukkit.getPluginManager().registerEvents(lumberListener, ARC.plugin);
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

    }

}
