@file:JvmName("TreasureHuntManager")

package ru.arc.treasurechests

import org.bukkit.block.Block
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import ru.arc.common.locationpools.LocationPool
import ru.arc.treasure.core.TreasurePool
import ru.arc.treasure.core.Treasures
import java.util.Optional

/**
 * Фасад для управления охотами за сокровищами.
 *
 * Делегирует в [TreasureHuntRegistry] для обратной совместимости
 * с существующим Java кодом.
 */
object TreasureHuntManager {
    // === Управление охотами ===

    @JvmStatic
    fun startHunt(
        locationPool: LocationPool,
        chests: Int,
        namespaceId: String,
        treasurePoolId: String,
        sender: CommandSender,
    ) {
        val variant = if (namespaceId == "vanilla") ChestVariant.VANILLA else ChestVariant.ITEMS_ADDER
        TreasureHuntRegistry.startHunt(
            locationPool = locationPool,
            chestCount = chests,
            chestVariant = variant,
            namespaceId = namespaceId.takeIf { it != "vanilla" },
            treasurePoolId = treasurePoolId,
            sender = sender,
        )
    }

    @JvmStatic
    fun startHunt(
        type: String,
        chests: Int,
        sender: CommandSender,
    ) {
        TreasureHuntRegistry.startHunt(type, chests, sender)
    }

    @JvmStatic
    fun stopHunt(hunt: ActiveHunt) {
        TreasureHuntRegistry.stopHunt(hunt)
    }

    @JvmStatic
    fun removeHunt(hunt: ActiveHunt) {
        TreasureHuntRegistry.removeHunt(hunt)
    }

    @JvmStatic
    fun stopAll() {
        TreasureHuntRegistry.stopAll()
    }

    // === Получение охот ===

    @JvmStatic
    fun getByBlock(block: Block): ActiveHunt? = TreasureHuntRegistry.getByBlock(block)

    @JvmStatic
    fun getByLocationPool(locationPool: LocationPool): Optional<ActiveHunt> =
        Optional.ofNullable(TreasureHuntRegistry.getByLocationPool(locationPool))

    @JvmStatic
    fun getActiveHunts(): Collection<ActiveHunt> = TreasureHuntRegistry.getActiveHunts()

    // === Типы охот ===

    @JvmStatic
    fun getTreasureHuntTypes(): List<String> = TreasureHuntRegistry.getHuntTypeIds()

    @JvmStatic
    fun getTreasureHuntType(id: String): TreasureHuntConfig? = TreasureHuntRegistry.getHuntConfig(id)

    @JvmStatic
    fun loadTreasureHuntTypes() {
        TreasureHuntRegistry.loadHuntTypes()
    }

    // === Обработка событий ===

    @JvmStatic
    fun popChest(
        block: Block,
        hunt: ActiveHunt,
        player: Player,
    ) {
        TreasureHuntRegistry.claimChest(block, player)
    }

    @JvmStatic
    fun onPlayerQuit(player: Player) {
        TreasureHuntRegistry.onPlayerQuit(player)
    }

    // === Прочее ===

    @JvmStatic
    fun getTreasurePools(): Collection<TreasurePool> = Treasures.getAllPools()
}
