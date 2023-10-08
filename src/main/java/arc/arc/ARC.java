package arc.arc;

import arc.arc.board.Board;
import arc.arc.farm.*;
import arc.arc.hooks.*;
import arc.arc.listeners.ChatListener;
import arc.arc.listeners.PortalListener;
import arc.arc.listeners.SpawnerListener;
import arc.arc.util.CooldownManager;
import arc.arc.util.ParticleUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

public final class ARC extends JavaPlugin {

    public static ARC plugin;
    public static Config config;
    private static Economy econ = null;
    public WGHook wgHook;
    public ShopHook shopHook;
    public PSHook psHook;
    public SFHook sfHook;
    public EMHook emHook;
    public AEHook aeHook;
    public LandsHook landsHook;
    public HuskHomesHook huskHomesHook;
    public MineListener mineHook;
    public FarmListener farmHook;
    public SpawnerListener spawnerListener;
    public Mine mine;
    public Farm farm;
    public LumberListener lumberListener;
    public PortalListener portalListener;
    public RedisManager redisManager;
    public Board board;
    public ChatListener chatListener;
    List<ArcModule> arcModuleList = new ArrayList<>();

    public static void noPermissionMessage(Player player) {
        player.sendMessage(
                Component.text("Эй! ", NamedTextColor.RED).decoration(TextDecoration.BOLD, true)
                        .append(Component.text("Ты не можешь здесь делать это.", NamedTextColor.GRAY).decoration(TextDecoration.BOLD, false))
        );
    }

    public static Economy getEcon() {
        return econ;
    }

    @Override
    public void onEnable() {
        load();
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
        if (emHook != null) emHook.cancel();
        if (psHook != null) psHook.cancel();
        if (mine != null) mine.cancel();
    }

    public void load() {
        plugin = this;
        config = new Config();

        System.out.print("Registering events");
        registerEvents();
        System.out.print("Registering commands");
        registerCommands();
        System.out.print("Registering eco");
        setupEconomy();
        setupRedis();
        ParticleUtil.setupParticleManager();

        if (emHook != null) emHook.boot();
        if (psHook != null) psHook.boot();

        if (Config.enablePortals && portalListener == null) {
            portalListener = new PortalListener();
            Bukkit.getPluginManager().registerEvents(portalListener, this);
        }

        if (mineHook != null) {
            mineHook.setupMines();
        }
        if (farmHook != null) {
            farm = new Farm();
            farmHook.farm = farm;
        }
        if(lumberListener != null){
            lumberListener = new LumberListener();
        }

        CooldownManager.setupTask(5);

        this.board = new Board();
    }

    private void registerCommands() {
        getCommand("myarc").setExecutor(new Command());
    }

    private void registerEvents() {
        if (Bukkit.getPluginManager().isPluginEnabled("ProtectionStones") && psHook == null) {
            psHook = new PSHook();
            Bukkit.getPluginManager().registerEvents(psHook, this);
            arcModuleList.add(psHook);
            new PAPHook().register();
        }
        if (Bukkit.getPluginManager().isPluginEnabled("WorldGuard") && wgHook == null) {
            wgHook = new WGHook();
            arcModuleList.add(wgHook);
            Bukkit.getPluginManager().registerEvents(wgHook, this);
        }
        if (Bukkit.getPluginManager().isPluginEnabled("Slimefun") && sfHook == null) {
            sfHook = new SFHook();
            arcModuleList.add(sfHook);
            Bukkit.getPluginManager().registerEvents(sfHook, this);
        }
        if (Bukkit.getPluginManager().isPluginEnabled("AdvancedEnchantments") && aeHook == null) {
            aeHook = new AEHook();
            arcModuleList.add(aeHook);
            Bukkit.getPluginManager().registerEvents(aeHook, this);
        }
        if (Bukkit.getPluginManager().isPluginEnabled("EliteMobs") && emHook == null) {
            emHook = new EMHook();
            arcModuleList.add(emHook);
            Bukkit.getPluginManager().registerEvents(emHook, this);
        }
        if (Bukkit.getPluginManager().isPluginEnabled("HuskHomes") && huskHomesHook == null) {
            huskHomesHook = new HuskHomesHook();
            arcModuleList.add(huskHomesHook);
            Bukkit.getPluginManager().registerEvents(huskHomesHook, this);
        }
        if (Bukkit.getPluginManager().isPluginEnabled("Lands") && landsHook == null) {
            landsHook = new LandsHook();
            arcModuleList.add(landsHook);
        }
        if (Bukkit.getPluginManager().isPluginEnabled("WorldGuard") && mineHook == null) {
            mineHook = new MineListener();
            arcModuleList.add(mineHook);
            Bukkit.getPluginManager().registerEvents(mineHook, this);
        }
        if (Bukkit.getPluginManager().isPluginEnabled("WorldGuard") && farmHook == null) {
            farmHook = new FarmListener();
            arcModuleList.add(farmHook);
            Bukkit.getPluginManager().registerEvents(farmHook, this);
        }
        if(chatListener == null){
            chatListener = new ChatListener();
            Bukkit.getPluginManager().registerEvents(chatListener, this);
        }
        if(spawnerListener == null){
            spawnerListener = new SpawnerListener();
            Bukkit.getPluginManager().registerEvents(spawnerListener, this);
        }
        if (Bukkit.getPluginManager().isPluginEnabled("WorldGuard") && lumberListener == null) {
            lumberListener = new LumberListener();
            arcModuleList.add(lumberListener);
            Bukkit.getPluginManager().registerEvents(lumberListener, this);
        }
        if ((Bukkit.getPluginManager().isPluginEnabled("EconomyShopGUI") || Bukkit.getPluginManager().isPluginEnabled("EconomyShopGUI-Premium")) && shopHook == null) {
            shopHook = new ShopHook();
            arcModuleList.add(shopHook);
            Bukkit.getPluginManager().registerEvents(shopHook, this);
        }

    }

    private boolean setupEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) {
            return false;
        }
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            return false;
        }
        econ = rsp.getProvider();
        return true;
    }

    private void setupRedis() {
        try {
            redisManager = new RedisManager(getConfig().getString("redis.ip", "localhost"),
                    getConfig().getInt("redis.port", 3306), getConfig().getString("redis.username", "default"),
                    getConfig().getString("redis.password", ""));
            System.out.println("Redis setup.");
        } catch (Exception e) {
            e.printStackTrace();
        }

    }


}
