package ru.arc.dungeons

import com.magmaguy.elitemobs.api.utils.EliteItemManager
import com.magmaguy.elitemobs.entitytracker.EntityTracker
import com.magmaguy.elitemobs.items.ItemConsumables
import com.magmaguy.elitemobs.items.customitems.CustomItem
import com.magmaguy.elitemobs.treasurechest.TreasureChest
import io.papermc.paper.datacomponent.DataComponentTypes
import net.kyori.adventure.key.Key
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import ru.arc.dungeons.api.ArcDungeonsService
import ru.arc.dungeons.menu.DungeonsMenus
import ru.arc.eliteloot.EliteLootGuiFactory
import ru.arc.eliteloot.EliteLootManager
import ru.arc.eliteloot.LootType
import ru.arc.hooks.elitemobs.DungeonCaseRewards
import ru.arc.hooks.elitemobs.NativeDungeonClassGrantService
import ru.arc.staffspells.StaffSpell
import ru.arc.staffspells.StaffSpellsModule
import java.util.UUID

internal class DungeonsServiceProvider(private val plugin: ArcDungeonsPlugin) : ArcDungeonsService {
    override fun nativeReady() = plugin.isEnabled && plugin.emHook != null &&
        plugin.server.pluginManager.isPluginEnabled("EliteMobs")
    override fun caseDefinition(id: String) = if (nativeReady()) DungeonCaseRewards.definition(id) else null
    override fun createCase(player: Player, id: String) = if (nativeReady()) DungeonCaseRewards.create(player, id) else null
    override fun createLuckyTicket(player: Player): ItemStack? {
        if (!nativeReady()) return null
        val item = runCatching { CustomItem.getCustomItem("elite_lucky_ticket.yml")
            ?.generateDefaultsItemStack(player, false, null)?.clone() }.getOrNull() ?: return null
        if (item.type != Material.PAPER || !ItemConsumables.`is`(item, ItemConsumables.Type.LUCKY_TICKET)) return null
        item.setData(DataComponentTypes.ITEM_MODEL, Key.key("minecraft:totem_of_undying"))
        return item
    }
    override fun classFormIds() = if (nativeReady()) NativeDungeonClassGrantService.formIds() else emptyList()
    override fun grantClass(player: Player, formId: String): ArcDungeonsService.ClassGrant {
        if (!nativeReady()) return ArcDungeonsService.ClassGrant(ArcDungeonsService.GrantStatus.NOT_READY, formId, formId, emptyList())
        val result = NativeDungeonClassGrantService.grant(player, formId)
        return ArcDungeonsService.ClassGrant(ArcDungeonsService.GrantStatus.valueOf(result.status.name),
            result.formId, result.formName, result.skillIncreases.map {
                ArcDungeonsService.SkillIncrease(it.name, it.previousLevel, it.newLevel)
            })
    }
    override fun staffSpellIds() = StaffSpell.available.map { it.id }
    override fun staffSpellsReady() = StaffSpellsModule.ready()
    override fun giveStaffSpells(player: Player, ids: List<String>): Boolean {
        val selected = ids.map { id -> StaffSpell.available.firstOrNull { it.id == id } ?: return false }
        return StaffSpellsModule.give(player, selected)
    }
    override fun openDecorLoot(player: Player) = EliteLootGuiFactory.open(player)
    override fun decorType(item: ItemStack) = EliteLootManager.toLootType(item)?.name
    override fun addDecor(type: String, material: Material, weight: Double, modelId: Int,
        color: Color?, itemsAdderNamespace: String?, itemsAdderId: String?) =
        EliteLootManager.addDecorItem(LootType.valueOf(type), material, weight, modelId, color, itemsAdderNamespace, itemsAdderId)
    override fun resetShop() { requireNotNull(plugin.emHook).resetShop() }
    override fun openShop(player: Player, gear: Boolean) { requireNotNull(plugin.emHook).openShopGui(player, gear) }
    override fun canLaunchSeasonDungeon(player: Player, world: String) = nativeReady() && plugin.emHook?.canLaunchSeasonDungeon(player, world) == true
    override fun launchSeasonDungeon(player: Player, world: String) {
        check(nativeReady()) { "ArcDungeons native integration is unavailable" }
        requireNotNull(plugin.emHook).launchSeasonDungeon(player, world)
    }
    override fun dungeonAction(player: Player, action: String, arguments: List<String>) {
        // The runtime that opens the panel must own a fresh hotkey entry.
        DungeonsMenus.beginDialogFlow(player)
        plugin.dungeonQol?.action(player, action, arguments)
    }
    override fun usesMenuShortcut(player: Player) = plugin.dungeonQol?.usesMenuShortcut(player) == true
    override fun scoreboardValue(player: UUID, key: String) =
        plugin.dungeonQol?.scoreboard?.value(player, key) ?: if (key == "active") "false" else ""
    override fun isEliteTreasureChest(location: Location): Boolean {
        check(nativeReady()) { "ArcDungeons native integration is unavailable" }
        return TreasureChest.getTreasureChest(location) != null
    }
    override fun isEliteItem(item: ItemStack): Boolean {
        check(nativeReady()) { "ArcDungeons native integration is unavailable" }
        return EliteItemManager.isEliteMobsItem(item)
    }
    override fun isEliteMob(entity: Entity): Boolean {
        check(nativeReady()) { "ArcDungeons native integration is unavailable" }
        return EntityTracker.isEliteMob(entity)
    }
    override fun reload() = plugin.reloadDungeons()
}
