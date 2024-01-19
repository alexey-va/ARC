package arc.arc.chests;

import arc.arc.ARC;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class LocationPool {

    private static final Map<String, LocationPool> locationMap = new HashMap<>();
    private static YamlConfiguration config = null;
    private static File file;

    public static LocationPool getLocationPool(String id){
        return locationMap.get(id);
    }

    public static void loadConfig(){
        file = new File(ARC.plugin.getDataFolder() + File.separator + "location_pools.yml");
        if(!file.exists()){
            try {
                file.createNewFile();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        try {
            config.load(file);
        } catch (IOException | InvalidConfigurationException e) {
            throw new RuntimeException(e);
        }
    }




    private final List<Location> locationList = new ArrayList<>();
    String id;

    public LocationPool(String id){
        locationMap.put(id, this);
        this.id = id;
    }

    public boolean addLocation(Location location){
        Location loc = location.toBlockLocation();
        if(locationList.contains(loc)) return false;
        locationList.add(loc);
        return true;
    }

    public boolean removeLocation(Location location){
        Location loc = location.toBlockLocation();
        if(!locationList.contains(loc)) return false;
        locationList.remove(loc);
        return true;
    }

    public List<Location> getRandomLocations(int n){
        List<Location> locations = new ArrayList<>(locationList);
        Collections.shuffle(locations);
        return locations.subList(0, Math.min(n, locations.size()));
    }

    public void saveData(){
        if(config == null) loadConfig();
        List<String> serialised = new ArrayList<>();
        for(Location location : locationList){
            String s = location.getWorld().getName()+";"+location.getX()+";"+location.getY()+";"+location.getZ();
            serialised.add(s);
        }
        config.set(id, serialised);
        try {
            config.save(file);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void loadData(){
        if(config == null) loadConfig();
        if(config.get(id) == null){
            System.out.print("No such location pool: "+id);
            return;
        }

        for(String s : config.getStringList(id)){
            String[] strings = s.split(";");
            if(strings.length<4){
                System.out.print(id+" has a broken location string: "+s);
                continue;
            }
            World world = Bukkit.getWorld(strings[0]);
            if(world == null){
                System.out.print(strings[0]+" is not loaded");
                continue;
            }
            try{
                Location location = new Location(world, Double.parseDouble(strings[1]), Double.parseDouble(strings[2]), Double.parseDouble(strings[3]));
                locationList.add(location);
            } catch (Exception e){
                e.printStackTrace();
            }
        }
    }


}
