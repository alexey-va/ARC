package ru.arc.chestpreview

import org.bukkit.Bukkit
import org.bukkit.block.Block
import ru.ruscrafting.ecia.api.CrateLocationService

/** The crate owner knows both its protected anchors and native ExcellentCrates locations. */
internal object ChestPreviewCrates {
    fun contains(block: Block): Boolean {
        val plugins = Bukkit.getPluginManager()
        val owner = plugins.getPlugin("ArcExcellentCrates")
            ?: return plugins.getPlugin("ExcellentCrates") != null
        if (!owner.isEnabled) return true
        val registration = Bukkit.getServicesManager().getRegistration(CrateLocationService::class.java)
            ?: return true
        if (registration.plugin !== owner) return true
        return registration.provider.isCrateLocation(block.location)
    }
}
