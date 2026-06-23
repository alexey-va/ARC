@file:JvmName("TreasureHuntManager")

package ru.arc.treasurechests

import org.bukkit.Location
import org.bukkit.block.Block
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import ru.arc.common.locationpools.LocationPool
import ru.arc.common.locationpools.LocationPoolManager
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

    /**
     * Генерирует точки в радиусе от центра, создаёт эфемерный location_pool и запускает охоту.
     * Пул не сохраняется на диск и удаляется при остановке охоты.
     */
    @JvmStatic
    fun startGeneratedHunt(
        center: Location,
        radius: Double,
        chests: Int,
        namespaceId: String,
        treasurePoolId: String,
        sender: CommandSender,
    ): ActiveHunt? {
        val world = center.world
        if (world == null) {
            sender.sendMessage(
                ru.arc.util.TextUtil
                    .mm("<red>Мир не найден"),
            )
            return null
        }

        val config = HuntLocationGeneratorConfig(horizontalRadius = radius)
        val locations = HuntLocationGenerator.generate(world, center, chests, config)
        if (locations.isEmpty()) {
            sender.sendMessage(
                ru.arc.util.TextUtil.mm(
                    "<red>Не удалось найти подходящие точки в радиусе <white>$radius<red>. " +
                        "<gray>Попробуйте другой центр или больший радиус.",
                ),
            )
            return null
        }

        val pool = LocationPoolManager.createEphemeralPool()
        locations.forEach { pool.addLocation(it) }

        val placed = minOf(chests, locations.size)
        startHunt(pool, placed, namespaceId, treasurePoolId, sender)

        sender.sendMessage(
            ru.arc.util.TextUtil.mm(
                "<green>Сгенерировано <white>${locations.size}<green> точек " +
                    "(location_pool: <white>${pool.id}<green>, радиус: <white>$radius<green>)",
            ),
        )
        return TreasureHuntRegistry.getByLocationPool(pool)
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

    @JvmStatic
    fun hasActiveHunts(): Boolean = TreasureHuntRegistry.hasActiveHunts()

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
