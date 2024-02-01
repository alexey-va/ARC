package arc.arc.util;

import org.bukkit.Location;
import org.bukkit.block.Block;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class Utils {

    private static Map<Block, List<Location>> outlineCache = new ConcurrentHashMap<>();

    public static List<Location> getBlockOutline(){
        return null;
    }

}
