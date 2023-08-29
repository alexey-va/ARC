package arc.arc;

import arc.arc.hooks.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class ARC extends JavaPlugin {

    public static ARC plugin;
    public static Config config;
    public WGHook wgHook;
    public ShopHook shopHook;
    public PSHook psHook;
    public SFHook sfHook;
    public EMHook emHook;
    public AEHook aeHook;

    @Override
    public void onEnable() {
        load();
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
        if (emHook != null) emHook.cancel();
        if(psHook != null) psHook.cancel();
    }

    public void load() {
        plugin = this;
        config = new Config();

        registerEvents();
        registerCommands();

        if(emHook != null) emHook.boot();
        if(psHook != null) psHook.boot();
    }

    private void registerCommands() {
        getCommand("myarc").setExecutor(new Command());
    }

    private void registerEvents() {
        if (Bukkit.getPluginManager().isPluginEnabled("ProtectionStones") && psHook != null) {
            psHook = new PSHook();
            Bukkit.getPluginManager().registerEvents(psHook, this);
            new PAPHook().register();
        }
        if (Bukkit.getPluginManager().isPluginEnabled("WorldGuard") && wgHook != null) {
            wgHook = new WGHook();
            Bukkit.getPluginManager().registerEvents(wgHook, this);
        }
        if (Bukkit.getPluginManager().isPluginEnabled("Slimefun") && sfHook != null) {
            sfHook = new SFHook();
            Bukkit.getPluginManager().registerEvents(sfHook, this);
        }
        if (Bukkit.getPluginManager().isPluginEnabled("AdvancedEnchantments") && sfHook != null) {
            aeHook = new AEHook();
            Bukkit.getPluginManager().registerEvents(aeHook, this);
        }
        if (Bukkit.getPluginManager().isPluginEnabled("EliteMobs") && emHook != null) {
            emHook = new EMHook();
            Bukkit.getPluginManager().registerEvents(emHook, this);
        }
        if ((Bukkit.getPluginManager().isPluginEnabled("EconomyShopGUI") || Bukkit.getPluginManager().isPluginEnabled("EconomyShopGUI-Premium")) && wgHook != null) {
            wgHook = new WGHook();
            Bukkit.getPluginManager().registerEvents(wgHook, this);
        }
    }

    public static void noPermissionMessage(Player player) {
        player.sendMessage(
                Component.text("Эй! ", NamedTextColor.RED).decoration(TextDecoration.BOLD, true)
                        .append(Component.text("Ты не можешь здесь делать это.", NamedTextColor.GRAY).decoration(TextDecoration.BOLD, false))
        );
    }


}
