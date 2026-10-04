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
        startHunt(locationPool, chests, namespaceId, treasurePoolId, sender, replaceExisting = true)
    }

    @JvmStatic
    fun startHunt(
        locationPool: LocationPool,
        chests: Int,
        namespaceId: String,
        treasurePoolId: String,
        sender: CommandSender,
        replaceExisting: Boolean,
    ): ActiveHunt? {
        val variant = if (namespaceId == "vanilla") ChestVariant.VANILLA else ChestVariant.ITEMS_ADDER
        return TreasureHuntRegistry.startHunt(
            locationPool = locationPool,
            chestCount = chests,
            chestVariant = variant,
            namespaceId = namespaceId.takeIf { it != "vanilla" },
            treasurePoolId = treasurePoolId,
            sender = sender,
            replaceExisting = replaceExisting,
        )
    }

    @JvmStatic
    fun startHunt(
        type: String,
        chests: Int,
        sender: CommandSender,
    ) {
        startHunt(type, chests, sender, replaceExisting = true)
    }

    @JvmStatic
    fun startHunt(
        type: String,
        chests: Int,
        sender: CommandSender,
        replaceExisting: Boolean,
    ): ActiveHunt? = TreasureHuntRegistry.startHunt(type, chests, sender, replaceExisting)

    /** Starts a resolved config so command overrides retain the preset's lifecycle and presentation settings. */
    @JvmStatic
    fun startHunt(
        config: TreasureHuntConfig,
        chests: Int,
        sender: CommandSender,
        replaceExisting: Boolean,
    ): ActiveHunt? = TreasureHuntRegistry.startHunt(config, chests, sender, replaceExisting)

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
    ): ActiveHunt? =
        startGeneratedHunt(
            center,
            radius,
            chests,
            namespaceId,
            treasurePoolId,
            sender,
            replaceExisting = true,
        )

    @JvmStatic
    fun startGeneratedHunt(
        center: Location,
        radius: Double,
        chests: Int,
        namespaceId: String,
        treasurePoolId: String,
        sender: CommandSender,
        replaceExisting: Boolean,
    ): ActiveHunt? {
        val chestType =
            if (namespaceId == "vanilla") {
                ChestType.vanilla(treasurePoolId)
            } else {
                ChestType.itemsAdder(namespaceId, treasurePoolId)
            }
        val huntConfig = TreasureHuntConfig.simple("generated-${System.nanoTime()}", "", chestType)
        return startGeneratedHunt(center, radius, chests, huntConfig, sender, replaceExisting)
    }

    /** Generates locations, then starts the supplied preset config against that ephemeral pool. */
    @JvmStatic
    fun startGeneratedHunt(
        center: Location,
        radius: Double,
        chests: Int,
        config: TreasureHuntConfig,
        sender: CommandSender,
        replaceExisting: Boolean,
    ): ActiveHunt? {
        if (!TreasureHuntRegistry.validateStartConfig(config, sender)) return null
        val world = center.world ?: run {
            sender.sendMessage(ru.arc.util.TextUtil.mm("<red>Мир не найден"))
            return null
        }
        val locations = HuntLocationGenerator.generate(world, center, chests, HuntLocationGeneratorConfig(horizontalRadius = radius))
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
        locations.forEach(pool::addLocation)
        val hunt = startHunt(config.copy(locationPoolId = pool.id), minOf(chests, locations.size), sender, replaceExisting)
        if (hunt == null) {
            LocationPoolManager.removeEphemeralPool(pool.id)
            return null
        }

        sender.sendMessage(
            ru.arc.util.TextUtil.mm(
                "<green>Сгенерировано <white>${locations.size}<green> точек " +
                    "(location_pool: <white>${pool.id}<green>, радиус: <white>$radius<green>)",
            ),
        )
        return hunt
    }

    @JvmStatic
    fun stopHunt(hunt: ActiveHunt) {
        TreasureHuntRegistry.stopHunt(hunt)
    }

    @JvmStatic
    fun stopAll() {
        TreasureHuntRegistry.stopAll()
    }

    // === Получение охот ===

    @JvmStatic
    fun getByBlock(block: Block): ActiveHunt? = TreasureHuntRegistry.getByBlock(block)

    @JvmStatic
    fun getByLocationPool(locationPool: LocationPool): ActiveHunt? =
        TreasureHuntRegistry.getByLocationPool(locationPool)

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
