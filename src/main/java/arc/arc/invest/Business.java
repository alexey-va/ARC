package arc.arc.invest;

import arc.arc.ARC;
import arc.arc.hooks.HookRegistry;
import arc.arc.invest.goods.Inventory;
import arc.arc.invest.goods.ProductionEntry;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.configuration.serialization.ConfigurationSerializable;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class Business implements ConfigurationSerializable {

    String id;
    String displayName;
    double balance;
    long shares;
    Map<InvestPlayer, Integer> investors;
    BusinessLocation location;
    Production production;
    Inventory inventory;
    BusinessCore core;
    int level = 1;

    File invFile;
    File file;
    File coreFile;

    public Business(String name){
        this.id = name;
    }

    public void load(){
        invFile = new File(ARC.plugin.getDataFolder()+File.separator+"investing"+File.separator+"inventories"+File.separator+id+".yml");
        file = new File(ARC.plugin.getDataFolder()+File.separator+"investing"+File.separator+"businesses"+File.separator+id+".yml");
        coreFile = new File(ARC.plugin.getDataFolder()+File.separator+"investing"+File.separator+"cores"+File.separator+id+".yml");
        loadInventory();
        loadProduction();
        loadRegion();
        loadCore();
    }


    public void loadRegion(){
        if(HookRegistry.wgHook == null){
            System.out.println("WorldGuard in sot loaded. Businesses wont have location!");
            return;
        }
        YamlConfiguration configuration = YamlConfiguration.loadConfiguration(file);
        String world = configuration.getString("world");
        String region = configuration.getString("region");
        location = new BusinessLocation(world, region);
    }

    public void loadCore(){
        if(!coreFile.exists()){
            invFile.getParentFile().mkdirs();
            try {
                invFile.createNewFile();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        YamlConfiguration configuration = YamlConfiguration.loadConfiguration(coreFile);
        //this.core = BusinessCore.deserialize(configuration.getList("core"));
    }



    public void loadInventory(){
        if(!invFile.exists()){
            invFile.getParentFile().mkdirs();
            try {
                invFile.createNewFile();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        YamlConfiguration configuration = YamlConfiguration.loadConfiguration(invFile);
        this.inventory = Inventory.deserialize(configuration.getList("storage"));
    }

    public void saveInventory(){
        if(!invFile.exists()){
            invFile.getParentFile().mkdirs();
            try {
                invFile.createNewFile();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        YamlConfiguration configuration = YamlConfiguration.loadConfiguration(invFile);
        configuration.set("storage", inventory.serialize().get("storage"));
        try {
            configuration.save(invFile);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void loadProduction(){
        if(!file.exists()){
            file.getParentFile().mkdirs();
            try {
                file.createNewFile();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        YamlConfiguration configuration = YamlConfiguration.loadConfiguration(file);
        System.out.println(configuration);
        System.out.println(configuration.get("production"));
        this.production = Production.deserialize(configuration.getConfigurationSection("production"));
    }


    public void printInventory(){
        System.out.println(inventory.getItems());
    }

    public void produce(){
        new BukkitRunnable() {
            @Override
            public void run() {
                if(inventory.isInUse()){
                    //System.out.println("Inventory in use! Please wait");
                    return;
                }
                inventory.setInUse(true);

                Map<String, List<ItemStack>> reqs = production.reqs(level);
                //System.out.println("Reqs: "+reqs);
                Set<String> success = new HashSet<>();


                for(var entry : reqs.entrySet()){
                    if(!inventory.contains(entry.getValue())) continue;
                    inventory.remove(entry.getValue());
                    success.add(entry.getKey());
                    //System.out.println("Satisfy: "+entry.getKey());
                }

                List<ItemStack> produce = production.produce(success, level);
                //System.out.println("Produce: "+produce);
                inventory.add(produce);
                inventory.trim();

                inventory.setInUse(false);
            }
        }.runTaskAsynchronously(ARC.plugin);
    }

    public void addItem(ItemStack stack){
        inventory.add(stack);
    }

    @Override
    public @NotNull Map<String, Object> serialize() {
        return null;
    }
}
