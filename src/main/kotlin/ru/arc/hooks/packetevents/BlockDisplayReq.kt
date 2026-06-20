package ru.arc.hooks.packetevents

import org.bukkit.Location
import org.bukkit.block.data.BlockData

data class BlockDisplayReq(val location: Location, val data: BlockData)
