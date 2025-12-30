package ru.arc.util

import org.bukkit.block.BlockFace
import org.bukkit.block.data.BlockData
import org.bukkit.block.data.Directional
import org.bukkit.block.data.type.Fence
import org.bukkit.block.data.type.Stairs
import org.bukkit.block.data.type.Wall
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

    private fun rotateFenceCounterClockwise(fence: Fence) {
        val faces = fence.faces
        val result = mutableSetOf<BlockFace>()

        if (BlockFace.NORTH in faces) result.add(BlockFace.WEST)
        if (BlockFace.EAST in faces) result.add(BlockFace.NORTH)
        if (BlockFace.SOUTH in faces) result.add(BlockFace.EAST)
        if (BlockFace.WEST in faces) result.add(BlockFace.SOUTH)

        faces.forEach { f -> fence.setFace(f, false) }
        result.forEach { f -> fence.setFace(f, true) }
    }

    private fun rotateFenceClockwise(fence: Fence) {
        val faces = fence.faces
        val result = mutableSetOf<BlockFace>()

        if (BlockFace.NORTH in faces) result.add(BlockFace.EAST)
        if (BlockFace.EAST in faces) result.add(BlockFace.SOUTH)
        if (BlockFace.SOUTH in faces) result.add(BlockFace.WEST)
        if (BlockFace.WEST in faces) result.add(BlockFace.NORTH)

        faces.forEach { f -> fence.setFace(f, false) }
        result.forEach { f -> fence.setFace(f, true) }
    }

    private fun rotateWallCounterClockwise(wall: Wall) {
        val res = mutableMapOf<BlockFace, Wall.Height>()

        res[BlockFace.EAST] = wall.getHeight(BlockFace.SOUTH)
        res[BlockFace.SOUTH] = wall.getHeight(BlockFace.WEST)
        res[BlockFace.WEST] = wall.getHeight(BlockFace.NORTH)
        res[BlockFace.NORTH] = wall.getHeight(BlockFace.EAST)

        res.forEach { (face, height) -> wall.setHeight(face, height) }
    }

    private fun rotateWallClockwise(wall: Wall) {
        val res = mutableMapOf<BlockFace, Wall.Height>()

        res[BlockFace.EAST] = wall.getHeight(BlockFace.NORTH)
        res[BlockFace.SOUTH] = wall.getHeight(BlockFace.EAST)
        res[BlockFace.WEST] = wall.getHeight(BlockFace.SOUTH)
        res[BlockFace.NORTH] = wall.getHeight(BlockFace.WEST)

        res.forEach { (face, height) -> wall.setHeight(face, height) }
    }

    @JvmStatic
    fun rotateBlockData(data: BlockData, rotation: Int): BlockData {
        when (data) {
            is Stairs -> {
                when (rotation) {
                    90 -> data.facing = rotateFacingClockwise(data.facing)
                    180 -> data.facing = rotateFacing180(data.facing)
                    270 -> data.facing = rotateFacingCounterClockwise(data.facing)
                }
            }

            is Fence -> {
                when (rotation) {
                    90 -> rotateFenceClockwise(data)
                    180 -> {
                        rotateFenceCounterClockwise(data)
                        rotateFenceCounterClockwise(data)
                    }

                    270 -> rotateFenceCounterClockwise(data)
                }
            }

            is Wall -> {
                when (rotation) {
                    90 -> rotateWallCounterClockwise(data)
                    180 -> {
                        rotateWallClockwise(data)
                        rotateWallClockwise(data)
                    }

                    270 -> rotateWallClockwise(data)
                }
            }

            is Directional -> {
                when (rotation) {
                    90 -> data.facing = rotateFacingClockwise(data.facing)
                    180 -> data.facing = rotateFacing180(data.facing)
                    270 -> data.facing = rotateFacingCounterClockwise(data.facing)
                }
            }

            else -> {
                when (rotation) {
                    90 -> data.rotate(StructureRotation.COUNTERCLOCKWISE_90)
                    180 -> data.rotate(StructureRotation.CLOCKWISE_180)
                    270 -> data.rotate(StructureRotation.CLOCKWISE_90)
                }
            }
        }
        return data
    }
}

