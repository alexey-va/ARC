package ru.arc.invest;

import ru.arc.ARC;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class BusinessManager {

    private static Map<String, Business> businessMap = new ConcurrentHashMap<>();

    public static Business byName(String name){
        return businessMap.get(name);
    }

    public static void load(){

        Path path1 = Paths.get(ARC.plugin.getDataFolder().toString(), "investing", "businesses");
        if(!Files.exists(path1)){
            try {
                Files.createDirectories(path1);
                ARC.plugin.saveResource("investing/businesses/farm.yml", false);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        Path path2 = Paths.get(ARC.plugin.getDataFolder().toString(), "investing", "inventories");
        if(!Files.exists(path2)){
            try {
                Files.createDirectories(path2);
                ARC.plugin.saveResource("investing/inventories/farm.yml", false);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        try(var stream = Files.walk(Paths.get(ARC.plugin.getDataFolder().toString(), "investing", "businesses"), 3)){
            stream.filter(p -> p.toString().endsWith(".yml"))
                    .map(Path::getFileName)
                    .map(p -> p.toString().replace(".yml", ""))
                    .forEach(BusinessManager::crateFromName);
        } catch (Exception e){
            e.printStackTrace();
        }
    }


    private static void crateFromName(String name){
        Business business = new Business(name);
        business.load();
        businessMap.put(name, business);
        System.out.println("Business "+name+" loaded!");
    }


}
