package arc.arc.hooks.yamipa;

import io.josemmo.bukkit.plugin.YamipaPlugin;
import io.josemmo.bukkit.plugin.renderer.FakeImage;
import io.josemmo.bukkit.plugin.renderer.ImageRenderer;
import io.josemmo.bukkit.plugin.renderer.WorldAreaId;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class YamipaHook {

    public void updateImages(Location location, Player player){
        try {
            ImageRenderer imageRenderer = YamipaPlugin.getInstance().getRenderer();
            // Get images that should be spawned/destroyed
            WorldAreaId worldAreaId = WorldAreaId.fromLocation(location);
            Method method = ImageRenderer.class.getDeclaredMethod("getImagesInViewDistance", WorldAreaId.class);
            method.setAccessible(true);

            Field field = ImageRenderer.class.getDeclaredField("playersLocation");
            field.setAccessible(true);
            Map<Player, WorldAreaId> map = (Map<Player, WorldAreaId>) field.get(imageRenderer);
            WorldAreaId prevWorldAreaId = map.get(player);


            Set<FakeImage> desiredState = (Set<FakeImage>) method.invoke(imageRenderer, worldAreaId);
            Set<FakeImage> currentState = (prevWorldAreaId == null) ? new HashSet<>() : (Set<FakeImage>) method.invoke(imageRenderer,prevWorldAreaId);

            Set<FakeImage> imagesToLoad = new HashSet<>(desiredState);
            Set<FakeImage> imagesToUnload = new HashSet<>(currentState);
            // Spawn/destroy images
            for (FakeImage image : imagesToUnload) {
                image.destroy(player);
            }
            for (FakeImage image : imagesToLoad) {
                image.spawn(player);
            }
        } catch (Exception e){
            e.printStackTrace();
        }
    }

}
