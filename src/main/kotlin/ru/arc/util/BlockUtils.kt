package ru.arc.util

import org.bukkit.block.BlockFace
import org.bukkit.block.data.BlockData
import org.bukkit.block.structure.StructureRotation

object BlockUtils {

    @JvmStatic
    fun rotateFacingClockwise(facing: BlockFace): BlockFace {
        return when (facing) {
            BlockFace.NORTH -> BlockFace.EAST
            BlockFace.EAST -> BlockFace.SOUTH
            BlockFace.SOUTH -> BlockFace.WEST
            BlockFace.WEST -> BlockFace.NORTH
            else -> facing
        }
    }

    @JvmStatic
    fun rotateFacingCounterClockwise(facing: BlockFace): BlockFace {
        return when (facing) {
            BlockFace.NORTH -> BlockFace.WEST
            BlockFace.WEST -> BlockFace.SOUTH
            BlockFace.SOUTH -> BlockFace.EAST
            BlockFace.EAST -> BlockFace.NORTH
            else -> facing
        }
    }

    @JvmStatic
    fun rotateFacing180(facing: BlockFace): BlockFace {
        return rotateFacingClockwise(rotateFacingClockwise(facing))
    }

    @JvmStatic
    fun rotateBlockData(data: BlockData, rotation: Int): BlockData {
        when (((rotation % 360) + 360) % 360) {
            90 -> data.rotate(StructureRotation.CLOCKWISE_90)
            180 -> data.rotate(StructureRotation.CLOCKWISE_180)
            270 -> data.rotate(StructureRotation.COUNTERCLOCKWISE_90)
        }
        return data
    }
}
