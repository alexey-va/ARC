package arc.arc.hooks.iris;

import com.volmit.iris.core.tools.IrisToolbelt;
import com.volmit.iris.engine.object.IrisBiome;
import com.volmit.iris.engine.object.IrisRegion;
import com.volmit.iris.engine.platform.PlatformChunkGenerator;
import org.bukkit.Chunk;
import org.bukkit.World;

public class IrisHook {

    public IrisHook() {

    }

    public String surfaceBiomeName(Chunk chunk) {
        World world = chunk.getWorld();
        if (!IrisToolbelt.isIrisWorld(world)) return null;
        PlatformChunkGenerator access = IrisToolbelt.access(world);
        IrisBiome surfaceBiome = access.getEngine().getSurfaceBiome(chunk);
        return surfaceBiome.getName();
    }

    public String regionName(Chunk chunk) {
        World world = chunk.getWorld();
        if (!IrisToolbelt.isIrisWorld(world)) return null;
        PlatformChunkGenerator access = IrisToolbelt.access(world);
        IrisRegion region = access.getEngine().getRegion(chunk);
        return region.getName();
    }

}
