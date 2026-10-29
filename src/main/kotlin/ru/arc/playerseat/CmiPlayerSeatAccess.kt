package ru.arc.playerseat

import com.Zrips.CMI.CMI
import com.Zrips.CMI.Modules.PlayerOptions.PlayerOption
import org.bukkit.entity.Player

/** Loaded only for enabled CMI; its online-user lookup and preference read are cached. */
internal object CmiPlayerSeatAccess {
    fun allows(carrier: Player): Boolean {
        val cmi = CMI.getInstance()?.takeIf { it.isEnabled } ?: return true
        return cmi.playerManager.getUser(carrier.uniqueId)?.getOptionState(PlayerOption.rideMe) != false
    }
}
