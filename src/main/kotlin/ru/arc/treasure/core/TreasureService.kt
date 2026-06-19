package ru.arc.treasure.core

import net.milkbowl.vault.economy.Economy
import org.bukkit.Bukkit
import org.bukkit.entity.Player

/**
 * Result of giving a treasure to a player.
 */
sealed class GiveResult {
    data class Success(
        val treasure: Treasure,
    ) : GiveResult()

    data class Failure(
        val reason: String,
    ) : GiveResult()

    val isSuccess: Boolean get() = this is Success
    val isFailure: Boolean get() = this is Failure

    fun onSuccess(action: (Treasure) -> Unit): GiveResult {
        if (this is Success) action(treasure)
        return this
    }

    fun onFailure(action: (String) -> Unit): GiveResult {
        if (this is Failure) action(reason)
        return this
    }
}

/**
 * Service for giving treasures to players.
 * Handles all treasure types and their side effects.
 *
 * Dependencies are injected via lambdas for testability.
 */
class TreasureService(
    private val poolProvider: (String) -> TreasurePool?,
    private val economyProvider: () -> Economy? = { null },
) {
    /**
     * Gives a treasure to a player.
     */
    @JvmOverloads
    fun give(
        treasure: Treasure,
        player: Player,
        config: GiveConfig = GiveConfig.DEFAULT,
    ): GiveResult {
        val result =
            when (treasure) {
                is Treasure.Item -> giveItem(treasure, player)
                is Treasure.Money -> giveMoney(treasure, player)
                is Treasure.Command -> giveCommand(treasure, player)
                is Treasure.SubPool -> giveSubPool(treasure, player, config)
                is Treasure.Enchant -> giveEnchant(treasure, player)
                is Treasure.Potion -> givePotion(treasure, player)
                is Treasure.Ae -> giveAe(treasure, player)
                is Treasure.Slimefun -> giveSlimefun(treasure, player)
            }

        // Send messages on success
        if (result.isSuccess && config.sendMessages) {
            sendMessages(treasure, player, null)
        }

        return result
    }

    /**
     * Gives a random treasure from a pool to a player.
     */
    @JvmOverloads
    fun giveFromPool(
        poolId: String,
        player: Player,
        config: GiveConfig = GiveConfig.DEFAULT,
    ): GiveResult {
        val pool =
            poolProvider(poolId)
                ?: return GiveResult.Failure("Pool not found: $poolId")

        val treasure =
            pool.random()
                ?: return GiveResult.Failure("Pool is empty or all treasures have zero weight")

        val result =
            when (treasure) {
                is Treasure.Item -> giveItem(treasure, player)
                is Treasure.Money -> giveMoney(treasure, player)
                is Treasure.Command -> giveCommand(treasure, player)
                is Treasure.SubPool -> giveSubPool(treasure, player, config)
                is Treasure.Enchant -> giveEnchant(treasure, player)
                is Treasure.Potion -> givePotion(treasure, player)
                is Treasure.Ae -> giveAe(treasure, player)
                is Treasure.Slimefun -> giveSlimefun(treasure, player)
            }

        // Send messages on success
        if (result.isSuccess && config.sendMessages) {
            sendMessages(treasure, player, pool.takeIf { config.sendPoolMessages })
        }

        return result
    }

    // ==================== Private Give Methods ====================

    private fun giveItem(
        treasure: Treasure.Item,
        player: Player,
    ): GiveResult {
        val stack =
            treasure.stack.clone().apply {
                amount = treasure.amount
            }

        val overflow = player.inventory.addItem(stack)

        // Drop overflow on ground
        overflow.values.forEach { item ->
            player.location.world?.dropItemNaturally(player.location, item)
        }

        return GiveResult.Success(treasure)
    }

    private fun giveMoney(
        treasure: Treasure.Money,
        player: Player,
    ): GiveResult {
        val economy =
            economyProvider()
                ?: return GiveResult.Failure("Economy not available")

        val amount = treasure.amount
        val response = economy.depositPlayer(player, amount)

        if (!response.transactionSuccess()) {
            return GiveResult.Failure("Economy transaction failed")
        }

        return GiveResult.Success(treasure)
    }

    private fun giveCommand(
        treasure: Treasure.Command,
        player: Player,
    ): GiveResult {
        val console = Bukkit.getServer().consoleSender

        treasure.commands.forEach { command ->
            val processed = command.replace("%player%", player.name)
            Bukkit.dispatchCommand(console, processed)
        }

        return GiveResult.Success(treasure)
    }

    private fun giveSubPool(
        treasure: Treasure.SubPool,
        player: Player,
        config: GiveConfig,
    ): GiveResult {
        val subPool =
            poolProvider(treasure.poolId)
                ?: return GiveResult.Failure("Sub-pool not found: ${treasure.poolId}")

        val subTreasure =
            subPool.random()
                ?: return GiveResult.Failure("Sub-pool is empty: ${treasure.poolId}")

        // Recursive call with sub-pool messages
        return give(subTreasure, player, config)
    }

    private fun giveEnchant(
        treasure: Treasure.Enchant,
        player: Player,
    ): GiveResult {
        val count = treasure.amount

        repeat(count) {
            val book = treasure.randomBook()
            val overflow = player.inventory.addItem(book)
            overflow.values.forEach { item ->
                player.location.world?.dropItemNaturally(player.location, item)
            }
        }

        return GiveResult.Success(treasure)
    }

    private fun givePotion(
        treasure: Treasure.Potion,
        player: Player,
    ): GiveResult {
        val count = treasure.amount

        repeat(count) {
            val potion = Treasure.Potion.randomPotion()
            val overflow = player.inventory.addItem(potion)
            overflow.values.forEach { item ->
                player.location.world?.dropItemNaturally(player.location, item)
            }
        }

        return GiveResult.Success(treasure)
    }

    private fun giveAe(
        treasure: Treasure.Ae,
        player: Player,
    ): GiveResult {
        val command = AeLoot.buildCommand(player.name, treasure)
        return giveCommand(Treasure.Command(listOf(command)), player)
    }

    private fun giveSlimefun(
        treasure: Treasure.Slimefun,
        player: Player,
    ): GiveResult {
        val amount = treasure.rolledAmount
        val command = "sf give ${player.name} ${treasure.itemId} $amount"
        return giveCommand(Treasure.Command(listOf(command)), player)
    }

    // ==================== Messaging ====================

    private fun sendMessages(
        treasure: Treasure,
        player: Player,
        pool: TreasurePool?,
    ) {
        val context = buildMessageContext(treasure, player, pool?.id)

        when {
            treasure.messages.isNotEmpty() -> {
                treasure.messages.forEach { it.send(context) }
            }

            pool != null && pool.messages.isNotEmpty() -> {
                pool.messages.forEach { it.send(context) }
            }

            else -> {
                defaultMessageFor(treasure)?.send(context)
            }
        }
    }

    private fun defaultMessageFor(treasure: Treasure): TreasureMessage? =
        when (treasure) {
            is Treasure.Money -> TreasureMessage.chat(TreasureConfig.DefaultMessages.moneyReceived)
            is Treasure.Item -> TreasureMessage.chat(TreasureConfig.DefaultMessages.itemReceived)
            is Treasure.Enchant -> TreasureMessage.chat(TreasureConfig.DefaultMessages.enchantReceived)
            is Treasure.Potion -> TreasureMessage.chat(TreasureConfig.DefaultMessages.potionReceived)
            else -> null
        }

    private fun buildMessageContext(
        treasure: Treasure,
        player: Player,
        poolId: String?,
    ): MessageContext =
        when (treasure) {
            is Treasure.Item -> {
                MessageContext(
                    player = player,
                    amount = treasure.amount,
                    itemName =
                        treasure.stack.itemMeta?.displayName()?.let {
                            net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                                .plainText()
                                .serialize(it)
                        } ?: treasure.stack.type.name,
                    poolId = poolId,
                )
            }

            is Treasure.Money -> {
                MessageContext(
                    player = player,
                    amount = treasure.amount,
                    poolId = poolId,
                )
            }

            is Treasure.Enchant -> {
                MessageContext(
                    player = player,
                    amount = treasure.amount,
                    itemName = "Enchanted Book",
                    poolId = poolId,
                )
            }

            is Treasure.Potion -> {
                MessageContext(
                    player = player,
                    amount = treasure.amount,
                    itemName = "Potion",
                    poolId = poolId,
                )
            }

            else -> {
                MessageContext(
                    player = player,
                    poolId = poolId,
                )
            }
        }
}
