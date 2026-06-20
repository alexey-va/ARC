package ru.arc.hooks

import com.Zrips.CMI.CMI
import net.Zrips.CMILib.ActionBar.CMIActionBar
import net.Zrips.CMILib.Advancements.CMIAdvancement
import net.Zrips.CMILib.BossBar.BossBarInfo
import net.Zrips.CMILib.CMILib
import org.bukkit.Material
import org.bukkit.boss.BarColor
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

class CMIHook {

    fun sendBossbar(bossBarName: String?, message: String?, player: Player, barColor: BarColor?, seconds: Int, keepFor: Int) {
        val info = BossBarInfo(player, bossBarName ?: "")
        info.setKeepForTicks(0)
        barColor?.let { info.setColor(it) }
        info.setAuto(1)
        info.setMakeVisible(true)
        info.setTranslateColors(true)
        info.setTitleOfBar(message ?: "")
        info.setKeepForTicks(keepFor)
        info.setAdjustPerc(1.0 / (seconds * 20))
        CMILib.getInstance().bossBarManager.Show(info)
    }

    fun sendActionbar(message: String, players: List<Player>, seconds: Int) {
        CMIActionBar.send(players, message, seconds * 1000)
    }

    fun sendToast(desc: String?, title: String?, model: Int, material: Material?, vararg players: Player) {
        val advancement = CMIAdvancement()
        advancement.isAnnounce = true
        if (model != 0) advancement.setCustomModelData(model)
        if (material != null) advancement.setItem(ItemStack.of(material))
        if (title != null) advancement.setTitle(title)
        if (desc != null) advancement.setDescription(desc)
        advancement.isHidden = false
        advancement.show(*players)
    }

    fun warpExists(arg: String): Boolean = CMI.getInstance().warpManager.getWarp(arg) != null
}
