package ru.arc.itemcatalog

import dev.lone.itemsadder.api.CustomStack
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.block.ShulkerBox
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.BlockStateMeta
import ru.arc.hooks.HookRegistry
import ru.arc.mounts.MountModule
import ru.arc.mounts.MountRewardRejection
import ru.arc.mounts.MountRewardResult
import ru.arc.mounts.MountWallet
import ru.arc.mounts.RedisEconomyMountWallet
import ru.arc.onetime.OneTimeUseFingerprint
import ru.arc.treasure.core.AeLoot
import ru.arc.treasure.core.Treasure
import ru.arc.treasure.core.Treasures
import ru.arc.util.TextUtil
import ru.arc.util.withCustomModelData
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID
import java.util.concurrent.CompletableFuture

/** Converts non-item sources into bearer vouchers. Native effects run only after a durable claim. */
internal class CatalogPhysicalRewards(
    private val settings: RewardCatalogSettings,
    private val wallets: MountWallet = RedisEconomyMountWallet(),
) {
    private val entries = settings.categories.filter { it.rolls == null }.flatMap { it.entries }
        .plus(settings.categories.filter { it.rolls != null }.flatMap { it.entries })
        .distinctBy { key(it) }.associateBy { key(it) }

    fun key(entry: RewardCatalogEntry): String = when (val source = entry.source) {
        is RewardCatalogSource.Treasure -> "treasure:${source.pool}:${source.id}"
        is RewardCatalogSource.Mount -> "mount:${source.id}"
        is RewardCatalogSource.FurniturePackage -> "package:${source.id}"
        else -> "native:${source}"
    }

    fun resolve(key: String): PhysicalRewardSpec? = runCatching {
        val entry = entries[key] ?: return@runCatching null
        if (!providersReady(entry)) return@runCatching null
        val definition = when (val source = entry.source) {
            is RewardCatalogSource.Mount -> {
                MountModule.rewardPreview(source.id) ?: return@runCatching null
                "mount:${source.id}:level:1"
            }
            is RewardCatalogSource.FurniturePackage -> {
                val pack = settings.packages[source.id] ?: return@runCatching null
                val contents = pack.items.map { id ->
                    val native = CustomStack.getInstance(id)?.itemStack ?: return@runCatching null
                    "$id:${OneTimeUseFingerprint.sha256(native.serializeAsBytes()).sha256}"
                }
                "package:${contents.joinToString("\n")}"
            }
            is RewardCatalogSource.Treasure -> {
                val treasure = treasure(source) ?: return@runCatching null
                if (!supported(treasure)) return@runCatching null
                definition(treasure, emptySet()) ?: return@runCatching null
            }
            else -> return@runCatching null
        }
        val fingerprint = OneTimeUseFingerprint.sha256(("catalog-v1\n$key\n$definition").toByteArray())
        PhysicalRewardSpec(key, fingerprint, preview(entry))
    }.getOrNull()

    fun canRedeem(player: Player, spec: PhysicalRewardSpec): String? {
        val entry = entries[spec.key] ?: return UNAVAILABLE
        if (!providersReady(entry)) return UNAVAILABLE
        val requiredSlots = when (val source = entry.source) {
            is RewardCatalogSource.FurniturePackage -> (settings.packages[source.id]?.items?.size ?: return UNAVAILABLE).let { (it + 26) / 27 }
            is RewardCatalogSource.Mount -> 0
            is RewardCatalogSource.Treasure -> requiredSlots(treasure(source) ?: return UNAVAILABLE, emptySet()) ?: return UNAVAILABLE
            else -> return UNAVAILABLE
        }
        if (player.inventory.storageContents.count { it == null || it.type.isAir } < requiredSlots) {
            return "<red>Освободите $requiredSlots яч. инвентаря для награды."
        }
        return null
    }

    fun redeem(player: Player, spec: PhysicalRewardSpec, operationId: UUID): CompletableFuture<PhysicalRewardOutcome> {
        val entry = entries[spec.key] ?: return completed(rejected())
        return when (val source = entry.source) {
            is RewardCatalogSource.Mount -> MountModule.grantReward(player, source.id).thenApply { result ->
                when (result) {
                    is MountRewardResult.Granted -> PhysicalRewardOutcome.Applied
                    is MountRewardResult.AlreadyOwned -> PhysicalRewardOutcome.Rejected("<gold>Этот маунт уже открыт. Контракт можно передать другому игроку.")
                    is MountRewardResult.Rejected -> if (result.reason == MountRewardRejection.GRANT_UNCERTAIN ||
                        result.reason == MountRewardRejection.SHUTDOWN) uncertain() else rejected()
                }
            }
            is RewardCatalogSource.FurniturePackage -> completed(giveStacks(player, furnitureBoxes(source.id) ?: return completed(rejected())))
            is RewardCatalogSource.Treasure -> completed(redeemTreasure(player, treasure(source) ?: return completed(rejected()), operationId, emptySet()))
            else -> completed(rejected())
        }
    }

    private fun preview(entry: RewardCatalogEntry): ItemStack {
        val style = entry.icon ?: CatalogIconStyle("PAPER")
        return ItemStack(Material.valueOf(style.material)).also { stack ->
            if (style.customModelData != 0) stack.withCustomModelData(style.customModelData)
            stack.editMeta { meta ->
                meta.displayName(TextUtil.mm(entry.name ?: "<gold>Запечатанная награда", true))
                meta.lore((entry.description + when (val source = entry.source) {
                    is RewardCatalogSource.FurniturePackage -> {
                        val count = settings.packages.getValue(source.id).items.size
                        listOf("<gold>Полный набор: <yellow>$count предметов", "<gold>Упаковка: <yellow>${(count + 26) / 27} шалкер(а)")
                    }
                    is RewardCatalogSource.Mount -> listOf("<light_purple>Контракт открывает маунта I уровня.")
                    else -> emptyList()
                } + listOf("<green>ПКМ с предметом в руке — получить награду.", "<yellow>Можно хранить и передавать до использования.")).map { TextUtil.mm(it, true) })
            }
        }
    }

    private fun furnitureBoxes(id: String): List<ItemStack>? {
        val pack = settings.packages[id] ?: return null
        val items = pack.items.map { CustomStack.getInstance(it)?.itemStack?.clone() ?: return null }
        return items.chunked(27).mapIndexed { index, contents ->
            ItemStack(Material.PURPLE_SHULKER_BOX).also { box ->
                val meta = box.itemMeta as BlockStateMeta
                val state = meta.blockState as ShulkerBox
                contents.forEachIndexed { slot, item -> state.inventory.setItem(slot, item.also { it.amount = 1 }) }
                meta.blockState = state
                meta.displayName(TextUtil.mm("${pack.name} <gold>· ${index + 1}/${(items.size + 26) / 27}", true))
                meta.lore(listOf(TextUtil.mm("<yellow>${contents.size} предметов мебели из полного набора.", true)))
                box.itemMeta = meta
            }
        }
    }

    private fun redeemTreasure(player: Player, value: Treasure, operationId: UUID, visited: Set<String>): PhysicalRewardOutcome = when (value) {
        is Treasure.Money -> deposit(player, "vault", value.amount, operationId)
        is Treasure.Command -> {
            val tokens = tokenAmount(value)
            if (tokens != null) deposit(player, "tokens", tokens.toDouble(), operationId)
            else giveNativeCommand(player, value.commands.single())
        }
        is Treasure.Ae -> giveNativeCommand(player, AeLoot.buildCommand(player.name, value))
        is Treasure.SubPool -> {
            if (value.poolId in visited || visited.size >= 8) rejected()
            else Treasures.getPool(value.poolId)?.random()?.let { redeemTreasure(player, it, operationId, visited + value.poolId) } ?: rejected()
        }
        is Treasure.Item -> giveStacks(player, split(value.stack, value.amount))
        is Treasure.Slimefun -> HookRegistry.sfHook?.getSlimefunItemStack(value.itemId)?.let { giveStacks(player, split(it, value.rolledAmount)) } ?: rejected()
        is Treasure.Enchant -> giveStacks(player, List(value.amount) { value.randomBook() })
        is Treasure.Potion -> giveStacks(player, List(value.amount) { Treasure.Potion.randomPotion() })
    }

    internal fun deposit(player: Player, currency: String, amount: Double, operationId: UUID): PhysicalRewardOutcome {
        val wallet = wallets.walletForCurrency(currency)?.takeIf { it.available } ?: return rejected()
        val minor = runCatching { BigDecimal.valueOf(amount).movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact() }
            .getOrNull()?.takeIf { it > 0 } ?: return rejected()
        val before = wallet.balanceMinor(player.uniqueId) ?: return rejected()
        val evidence = wallet.deposit(player.uniqueId, minor, "arc-reward:$operationId", before)
        return when {
            evidence.providerAccepted == true && evidence.balanceAfterMinor == Math.addExact(before, minor) -> PhysicalRewardOutcome.Applied
            !evidence.providerCallAttempted || evidence.providerAccepted == false && evidence.balanceAfterMinor == before -> rejected()
            else -> uncertain()
        }
    }

    /** Only the finite native item issuers accepted below reach dispatch. Confirm an item delta, not a command boolean. */
    private fun giveNativeCommand(player: Player, command: String): PhysicalRewardOutcome {
        val before = player.inventory.storageContents.map { it?.clone() }
        val succeeded = runCatching { Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command.replace("%player%", player.name)) }
        val after = player.inventory.storageContents.toList()
        val changed = after.sumOf { it?.amount ?: 0 } > before.sumOf { it?.amount ?: 0 }
        if (changed) return PhysicalRewardOutcome.Applied
        // ArcBuilder and ArcEcoJobs are synchronous item-only issuers; no inventory change proves refusal.
        if (command.startsWith("arcbuilder:builder systembook ") || command.startsWith("arcecojobs:arcjobs booster give ")) return rejected()
        return if (succeeded.getOrNull() == false) rejected() else uncertain()
    }

    private fun giveStacks(player: Player, values: List<ItemStack>): PhysicalRewardOutcome {
        val before = player.inventory.storageContents.map { it?.clone() }.toTypedArray()
        val simulation = Bukkit.createInventory(null, 36)
        simulation.contents = before.map { it?.clone() }.toTypedArray()
        if (values.any { simulation.addItem(it.clone()).isNotEmpty() }) return PhysicalRewardOutcome.Rejected("<red>В инвентаре не хватает места.")
        return try {
            if (player.inventory.addItem(*values.map { it.clone() }.toTypedArray()).isEmpty()) PhysicalRewardOutcome.Applied
            else { player.inventory.storageContents = before; rejected() }
        } catch (_: RuntimeException) {
            player.inventory.storageContents = before
            rejected()
        }
    }

    private fun requiredSlots(value: Treasure, visited: Set<String>): Int? {
        return when (value) {
        is Treasure.Money -> 0
        is Treasure.Command -> if (tokenAmount(value) != null) 0 else 1
        is Treasure.Ae -> value.amount.coerceAtLeast(1)
        is Treasure.SubPool -> if (value.poolId in visited || visited.size >= 8) null else
            Treasures.getPool(value.poolId)?.treasures?.map { requiredSlots(it, visited + value.poolId) ?: return null }?.maxOrNull()
        is Treasure.Item -> (value.max + value.stack.maxStackSize - 1) / value.stack.maxStackSize
        is Treasure.Slimefun -> HookRegistry.sfHook?.getSlimefunItemStack(value.itemId)?.let { (value.max + it.maxStackSize - 1) / it.maxStackSize }
        is Treasure.Enchant -> value.max
        is Treasure.Potion -> value.max
        }
    }

    private fun supported(value: Treasure): Boolean = when (value) {
        is Treasure.Command -> tokenAmount(value) != null || value.commands.singleOrNull()?.let { command -> NATIVE_ITEM_COMMANDS.any { it.matches(command) } } == true
        else -> true
    }

    private fun definition(value: Treasure, visited: Set<String>): String? {
        if (!supported(value)) return null
        if (value is Treasure.SubPool) {
            if (value.poolId in visited || visited.size >= 8) return null
            val pool = Treasures.getPool(value.poolId) ?: return null
            val parts = pool.treasures.map { "${it.weight}:" + (definition(it, visited + pool.id) ?: return null) }
            return "pool:${pool.id}:" + parts.joinToString("\n")
        }
        return value.toMap().toString()
    }

    private fun treasure(source: RewardCatalogSource.Treasure): Treasure? = Treasures.getPool(source.pool)?.findById(source.id)
    private fun providersReady(entry: RewardCatalogEntry): Boolean = entry.requires.all { Bukkit.getPluginManager().isPluginEnabled(it) }
    private fun split(stack: ItemStack, amount: Int): List<ItemStack> = (0 until amount step stack.maxStackSize).map { offset ->
        stack.clone().also { it.amount = minOf(stack.maxStackSize, amount - offset) }
    }
    private fun rejected() = PhysicalRewardOutcome.Rejected(UNAVAILABLE)
    private fun uncertain() = PhysicalRewardOutcome.Uncertain("<gold>Результат требует проверки. Сохраните предмет и сообщите администрации.")
    private fun completed(outcome: PhysicalRewardOutcome) = CompletableFuture.completedFuture(outcome)

    companion object {
        private const val UNAVAILABLE = "<red>Награда сейчас недоступна. Предмет сохранён."
        private val TOKEN_COMMAND = Regex("rediseconomy:balance %player% tokens give ([1-9][0-9]{0,5}) arc-lootbox-catalog")
        private val NATIVE_ITEM_COMMANDS = listOf(
            Regex("arcbuilder:builder systembook %player% [a-z0-9_-]+\\.schem"),
            Regex("arcecojobs:arcjobs booster give %player% [a-z0-9_-]+ 1"),
            Regex("elitemobs:elitemobs loot give %player% [a-z0-9_-]+\\.yml"),
        )
        internal fun tokenAmount(value: Treasure.Command): Long? = value.commands.singleOrNull()
            ?.let { TOKEN_COMMAND.matchEntire(it)?.groupValues?.get(1)?.toLongOrNull() }
    }
}
