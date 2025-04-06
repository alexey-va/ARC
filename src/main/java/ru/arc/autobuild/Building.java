package ru.arc.autobuild;

import ru.arc.ARC;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardReader;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.block.BaseBlock;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.io.File;
import java.io.FileInputStream;

@RequiredArgsConstructor
public class Building {

    @Getter
    private final String fileName;

    Clipboard clipboard;

    public void loadClipboard() {
        File file = new File(ARC.plugin.getDataFolder() + File.separator + "schematics" + File.separator + fileName);
        if (!file.getParentFile().exists()) {
            file.getParentFile().mkdir();
        }

        if (!file.exists()) {
            System.out.println("No file with name " + fileName + " found in schematics folder!");
            throw new IllegalArgumentException("File does not exist");
        }

        ClipboardFormat format = ClipboardFormats.findByFile(file);
        try (ClipboardReader reader = format.getReader(new FileInputStream(file))) {
            clipboard = reader.read();
        } catch (Exception e) {
            e.printStackTrace();
            throw new IllegalArgumentException("File does not exist", e);
        }
    }

    public BaseBlock getBlock(BlockVector3 coords, int rotated) {
        if (clipboard == null) loadClipboard();
        rotated = rotated % 360;
        BlockVector3 vec = rotate(coords, 0, 0, -rotated);
        return clipboard.getFullBlock(vec.add(clipboard.getOrigin()));
    }


    public BlockVector3 getCorner1(int rotation) {
        if (clipboard == null) loadClipboard();
        BlockVector3 center = clipboard.getOrigin();
        return rotate(clipboard.getMinimumPoint().subtract(center), 0, 0, rotation);
    }

    public BlockVector3 getCorner2(int rotation) {
        if (clipboard == null) loadClipboard();
        BlockVector3 center = clipboard.getOrigin();
        return rotate(clipboard.getMaximumPoint().subtract(center), 0, 0, rotation);
    }

    public long volume() {
        return clipboard.getRegion().getVolume();
    }


    private BlockVector3 rotate(BlockVector3 vector, int x, int z, int degs) {
        int xOffset = vector.x() - x;
        int zOffset = vector.z() - z;

        return switch (degs) {
            case 90, -270 -> BlockVector3.at(x - zOffset, vector.y(), z + xOffset);
            case 180, -180 -> BlockVector3.at(x - xOffset, vector.y(), z - zOffset);
            case 270, -90 -> BlockVector3.at(x + zOffset, vector.y(), z - xOffset);
            default -> vector;
        };
    }

}
