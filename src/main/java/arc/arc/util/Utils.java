package arc.arc.util;


import dev.lone.itemsadder.api.ItemsAdder;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.type.Fence;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.block.data.type.Wall;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;

public class Utils {

    static ExecutorService service = Executors.newSingleThreadExecutor();

    public static List<Location> getLine(Location l1, Location l2, double density, boolean skipFirst) {
        List<Location> locations = new ArrayList<>();

        double distance = l1.distance(l2);
        int count = (int) Math.round(distance * density);
        Vector vector = new Vector(l2.getX() - l1.getX(), l2.getY() - l1.getY(), l2.getZ() - l1.getZ());
        vector.multiply(1.0 / count);

        if (!skipFirst) locations.add(l1);
        Location l = l1.clone();
        for (int i = 0; i < count; i++) {
            l = l.add(vector);
            locations.add(l.clone());
        }

        return locations;
    }

    public static List<ItemStack> split(ItemStack stack, int count){
        List<ItemStack> stacks = new ArrayList<>();
        if(stack == null){
            System.out.println("Stack in split is null!");
            return stacks;
        }
        int maxStack = stack.getMaxStackSize();

        while (count>0){
            int qty = Math.min(count, maxStack);
            ItemStack i = stack.asQuantity(qty);
            stacks.add(i);
            count-=qty;
        }

        return stacks;
    }

    public static List<LocationData> getLineWithCornerData(Location l1, Location l2, double density, boolean skipFirst, int cornerDistance) {
        List<LocationData> locations = new ArrayList<>();

        double distance = l1.distance(l2);
        int count = (int) Math.round(distance * density);
        Vector vector = new Vector(l2.getX() - l1.getX(), l2.getY() - l1.getY(), l2.getZ() - l1.getZ());
        vector.multiply(1.0 / count);

        if (!skipFirst) locations.add(new LocationData(l1, true));
        Location l = l1.clone();
        for (int i = 0; i < count; i++) {
            l = l.add(vector);
            locations.add(new LocationData(l.clone(),
                    (l.distanceSquared(l2) <= cornerDistance * cornerDistance ||
                            l.distanceSquared(l1) <= cornerDistance * cornerDistance)));
        }

        return locations;
    }

    public static List<Location> getBorderLocations(Location corner1, Location corner2, int density) {
        double minX = Math.min(corner1.getX(), corner2.getX());
        double minY = Math.min(corner1.getY(), corner2.getY());
        double minZ = Math.min(corner1.getZ(), corner2.getZ());
        double maxX = Math.max(corner1.getX(), corner2.getX());
        double maxY = Math.max(corner1.getY(), corner2.getY());
        double maxZ = Math.max(corner1.getZ(), corner2.getZ());
        World w = corner1.getWorld();

        List<Location> locations = new ArrayList<>();
        locations.addAll(getLine(new Location(w, minX, minY, minZ), new Location(w, maxX, minY, minZ), density, false));
        locations.addAll(getLine(new Location(w, minX, minY, minZ), new Location(w, minX, minY, maxZ), density, true));
        locations.addAll(getLine(new Location(w, maxX, minY, minZ), new Location(w, maxX, minY, maxZ), density, true));
        locations.addAll(getLine(new Location(w, minX, minY, maxZ), new Location(w, maxX, minY, maxZ), density, true));

        locations.addAll(getLine(new Location(w, minX, maxY, minZ), new Location(w, maxX, maxY, minZ), density, true));
        locations.addAll(getLine(new Location(w, minX, maxY, minZ), new Location(w, minX, maxY, maxZ), density, true));
        locations.addAll(getLine(new Location(w, maxX, maxY, minZ), new Location(w, maxX, maxY, maxZ), density, true));
        locations.addAll(getLine(new Location(w, minX, maxY, maxZ), new Location(w, maxX, maxY, maxZ), density, true));

        locations.addAll(getLine(new Location(w, minX, minY, minZ), new Location(w, minX, maxY, minZ), density, false));
        locations.addAll(getLine(new Location(w, maxX, minY, minZ), new Location(w, maxX, maxY, minZ), density, true));
        locations.addAll(getLine(new Location(w, maxX, minY, maxZ), new Location(w, maxX, maxY, maxZ), density, true));
        locations.addAll(getLine(new Location(w, minX, minY, maxZ), new Location(w, minX, maxY, maxZ), density, true));
        return locations;
    }

    public record LocationData(Location location, boolean corner) {
    }

    public static List<LocationData> getBorderLocationsWithCornerData(Location corner1, Location corner2, int density, int cornerDistance) {
        double minX = Math.min(corner1.getX(), corner2.getX());
        double minY = Math.min(corner1.getY(), corner2.getY());
        double minZ = Math.min(corner1.getZ(), corner2.getZ());
        double maxX = Math.max(corner1.getX(), corner2.getX());
        double maxY = Math.max(corner1.getY(), corner2.getY());
        double maxZ = Math.max(corner1.getZ(), corner2.getZ());
        World w = corner1.getWorld();

        List<LocationData> locations = new ArrayList<>();
        locations.addAll(getLineWithCornerData(new Location(w, minX, minY, minZ), new Location(w, maxX, minY, minZ), density, false, cornerDistance));
        locations.addAll(getLineWithCornerData(new Location(w, minX, minY, minZ), new Location(w, minX, minY, maxZ), density, true, cornerDistance));
        locations.addAll(getLineWithCornerData(new Location(w, maxX, minY, minZ), new Location(w, maxX, minY, maxZ), density, true, cornerDistance));
        locations.addAll(getLineWithCornerData(new Location(w, minX, minY, maxZ), new Location(w, maxX, minY, maxZ), density, true, cornerDistance));

        locations.addAll(getLineWithCornerData(new Location(w, minX, maxY, minZ), new Location(w, maxX, maxY, minZ), density, true, cornerDistance));
        locations.addAll(getLineWithCornerData(new Location(w, minX, maxY, minZ), new Location(w, minX, maxY, maxZ), density, true, cornerDistance));
        locations.addAll(getLineWithCornerData(new Location(w, maxX, maxY, minZ), new Location(w, maxX, maxY, maxZ), density, true, cornerDistance));
        locations.addAll(getLineWithCornerData(new Location(w, minX, maxY, maxZ), new Location(w, maxX, maxY, maxZ), density, true, cornerDistance));

        locations.addAll(getLineWithCornerData(new Location(w, minX, minY, minZ), new Location(w, minX, maxY, minZ), density, true, cornerDistance));
        locations.addAll(getLineWithCornerData(new Location(w, maxX, minY, minZ), new Location(w, maxX, maxY, minZ), density, true, cornerDistance));
        locations.addAll(getLineWithCornerData(new Location(w, maxX, minY, maxZ), new Location(w, maxX, maxY, maxZ), density, true, cornerDistance));
        locations.addAll(getLineWithCornerData(new Location(w, minX, minY, maxZ), new Location(w, minX, maxY, maxZ), density, true, cornerDistance));
        return locations;
    }

    public static BlockFace rotateFacingClockwise(BlockFace facing) {
        return switch (facing) {
            case NORTH -> BlockFace.EAST;
            case EAST -> BlockFace.SOUTH;
            case SOUTH -> BlockFace.WEST;
            case WEST -> BlockFace.NORTH;
            default -> facing;
        };
    }

    public static BlockFace rotateFacingCounterClockwise(BlockFace facing) {
        return switch (facing) {
            case NORTH -> BlockFace.WEST;
            case WEST -> BlockFace.SOUTH;
            case SOUTH -> BlockFace.EAST;
            case EAST -> BlockFace.NORTH;
            default -> facing;
        };
    }

    public static BlockFace rotateFacing180(BlockFace facing) {
        return rotateFacingClockwise(rotateFacingClockwise(facing));
    }

    private static void rotateFenceCounterClockwise(Fence fence) {
        // Get the current faces
        Set<BlockFace> faces = fence.getFaces();
        Set<BlockFace> result = new HashSet<>();

        // Add the rotated faces
        if (faces.contains(BlockFace.NORTH)) result.add(BlockFace.WEST);
        if (faces.contains(BlockFace.EAST)) result.add(BlockFace.NORTH);
        if (faces.contains(BlockFace.SOUTH)) result.add(BlockFace.EAST);
        if (faces.contains(BlockFace.WEST)) result.add(BlockFace.SOUTH);

        //System.out.println("result:" + result);

        // Update the fence block data with the new faces
        faces.forEach(f -> fence.setFace(f, false));
        result.forEach(f -> fence.setFace(f, true));
    }

    private static void rotateFenceClockwise(Fence fence) {
        // Get the current faces
        Set<BlockFace> faces = fence.getFaces();
        Set<BlockFace> result = new HashSet<>();

        // Add the rotated faces
        if (faces.contains(BlockFace.NORTH)) result.add(BlockFace.EAST);
        if (faces.contains(BlockFace.EAST)) result.add(BlockFace.SOUTH);
        if (faces.contains(BlockFace.SOUTH)) result.add(BlockFace.WEST);
        if (faces.contains(BlockFace.WEST)) result.add(BlockFace.NORTH);

        //System.out.println("result:" + result);

        // Update the fence block data with the new faces
        faces.forEach(f -> fence.setFace(f, false));
        result.forEach(f -> fence.setFace(f, true));
    }

    private static void rotateWallCounterClockwise(Wall wall) {
        // Get the current faces
        Map<BlockFace, Wall.Height> res = new HashMap<>();

        // Add the rotated faces
        res.put(BlockFace.EAST, wall.getHeight(BlockFace.SOUTH));
        res.put(BlockFace.SOUTH, wall.getHeight(BlockFace.WEST));
        res.put(BlockFace.WEST, wall.getHeight(BlockFace.NORTH));
        res.put(BlockFace.NORTH, wall.getHeight(BlockFace.EAST));

        // Update the wall block data with the new faces
        res.forEach(wall::setHeight);
    }

    private static void rotateWallClockwise(Wall wall) {
        // Get the current faces
        Map<BlockFace, Wall.Height> res = new HashMap<>();

        // Add the rotated faces
        res.put(BlockFace.EAST, wall.getHeight(BlockFace.NORTH));
        res.put(BlockFace.SOUTH, wall.getHeight(BlockFace.EAST));
        res.put(BlockFace.WEST, wall.getHeight(BlockFace.SOUTH));
        res.put(BlockFace.NORTH, wall.getHeight(BlockFace.WEST));

        // Update the wall block data with the new faces
        res.forEach(wall::setHeight);
    }

    public static BlockData rotateBlockData(BlockData data, int rotation) {
        if (data instanceof Stairs stairs) {
            switch (rotation) {
                case 90 -> stairs.setFacing(rotateFacingClockwise(stairs.getFacing()));
                case 180 -> stairs.setFacing(rotateFacing180(stairs.getFacing()));
                case 270 -> stairs.setFacing(rotateFacingCounterClockwise(stairs.getFacing()));
            }
        } else if (data instanceof Fence fence) {
            //System.out.println("Fence: "+fence.getFaces());
            switch (rotation) {
                case 90 -> rotateFenceCounterClockwise(fence);
                case 180 -> {
                    rotateFenceClockwise(fence);
                    rotateFenceClockwise(fence);
                }
                case 270 -> rotateFenceClockwise(fence);
            }
            //System.out.println("Fence after: "+fence.getFaces());
        } else if (data instanceof Wall wall) {
            switch (rotation) {
                case 90 -> rotateWallCounterClockwise(wall);
                case 180 -> {
                    rotateWallClockwise(wall);
                    rotateWallClockwise(wall);
                }
                case 270 -> rotateWallClockwise(wall);
            }
        } else if (data instanceof Directional directional) {
            switch (rotation) {
                case 90 -> directional.setFacing(rotateFacingClockwise(directional.getFacing()));
                case 180 -> directional.setFacing(rotateFacing180(directional.getFacing()));
                case 270 -> directional.setFacing(rotateFacingCounterClockwise(directional.getFacing()));
            }
        } else {
            switch (rotation) {
                case 90 -> data.rotate(StructureRotation.COUNTERCLOCKWISE_90);
                case 180 -> data.rotate(StructureRotation.CLOCKWISE_180);
                case 270 -> data.rotate(StructureRotation.CLOCKWISE_90);
            }
        }
        return data;
    }

    public static void sendResourcePack(Player player, String url) {
        ItemsAdder.applyResourcepack(player);
    }

    public static <T> T random(T[] array) {
        return array[ThreadLocalRandom.current().nextInt(array.length)];
    }

    public static <T> T[] random(T[] array, int amount) {
        if (amount >= array.length) return array;
        T[] copyArray = array.clone();

        // Shuffle the copied array
        for (int i = copyArray.length - 1; i > 0; i--) {
            int index = ThreadLocalRandom.current().nextInt(i + 1);
            T temp = copyArray[index];
            copyArray[index] = copyArray[i];
            copyArray[i] = temp;
        }

        return Arrays.copyOfRange(copyArray, 0, amount);
    }

    public static <T> T random(Collection<T> collection) {
        if (collection == null || collection.isEmpty()) {
            throw new IllegalArgumentException("Collection must not be null or empty");
        }

        if (collection instanceof List<T> list) {
            // If the collection is a list, directly use the get method
            int randomIndex = new Random().nextInt(list.size());
            return list.get(randomIndex);
        } else {
            // If it's not a list, convert to a list and proceed as before
            List<T> list = List.copyOf(collection);
            int randomIndex = new Random().nextInt(list.size());
            return list.get(randomIndex);
        }
    }

    public static <K, V> Map.Entry<K, V> random(Map<K, V> skinLinks) {
        int rng = ThreadLocalRandom.current().nextInt(skinLinks.size());
        return skinLinks.entrySet().stream().skip(rng).findFirst().get();
    }

    private Map<String, Object> convertConfigToMap(ConfigurationSection section) {
        Map<String, Object> map = new HashMap<>();

        for (String key : section.getKeys(false)) {
            if (section.isConfigurationSection(key)) {
                // Recursively convert nested sections
                map.put(key, convertConfigToMap(section.getConfigurationSection(key)));
            } else {
                // Put the values into the map
                map.put(key, section.get(key));
            }
        }

        return map;
    }
}
