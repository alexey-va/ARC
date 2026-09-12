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
import ru.arc.treasure.core.AeArg
import ru.arc.treasure.core.AeLoot
import ru.arc.treasure.core.AeKind
import ru.arc.treasure.core.Treasure
import ru.arc.treasure.core.Treasures
import ru.arc.util.TextUtil
import ru.arc.util.withCustomModelData
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ThreadLocalRandom

/** Converts provider-backed sources into durable physical entitlements. Native effects run only after a durable claim. */
internal class CatalogPhysicalRewards(
    private val settings: RewardCatalogSettings,
    private val wallets: MountWallet = RedisEconomyMountWallet(),
    private val frozen: FrozenPhysicalRewards? = null,
    /** Creates the configured seal so its authored icon and presentation survive archiving. */
    private val sealStack: (String, CatalogIconStyle?) -> ItemStack? = { _, _ -> null },
) {
    private val entries = settings.categories.filter { it.rolls == null }.flatMap { it.entries }
        .plus(settings.categories.filter { it.rolls != null }.flatMap { it.entries })
        .distinctBy { key(it) }.associateBy { key(it) }

    fun key(entry: RewardCatalogEntry): String = when (val source = entry.source) {
        is RewardCatalogSource.Treasure -> "treasure:${source.pool}:${source.id}"
        is RewardCatalogSource.Mount -> "mount:${source.id}"
        is RewardCatalogSource.FurniturePackage -> "package:${source.id}"
        is RewardCatalogSource.Seal -> "seal:${source.categoryId}"
        else -> "native:${source}"
    }

    /**
     * Identifies sources that require a durable physical materialization in the
     * catalogue. Collection seals use a category marker; they are not bearer vouchers.
     */
    fun isVoucherSource(entry: RewardCatalogEntry): Boolean = when (val source = entry.source) {
        is RewardCatalogSource.Mount,
        is RewardCatalogSource.FurniturePackage,
        -> true
        is RewardCatalogSource.Treasure -> when (treasure(source)) {
            is Treasure.Item,
            is Treasure.Slimefun,
            is Treasure.Enchant,
            is Treasure.Potion,
            null,
            -> false
            else -> true
        }
        is RewardCatalogSource.Seal -> true
        else -> false
    }

    fun materialization(entry: RewardCatalogEntry): PhysicalRewardMaterialization? = runCatching {
        if (!isVoucherSource(entry)) return null
        val archive = frozen ?: return null
        val sourceKey = key(entry)
        val spec = resolve(sourceKey) ?: return null
        val recipe = freezeRecipe(entry) ?: return null
        if (!frozenProvidersReady(recipe)) return null
        val archived = archive.prepare(sourceKey, recipe, spec.preview) ?: return null
        if (entry.source is RewardCatalogSource.Seal) {
            val categoryId = archive.archivedCategoryId(archived.sourceKey) ?: return null
            return PhysicalRewardMaterialization(categoryId, archived.providerFingerprint)
        }
        return archived
    }.getOrNull()

    /** Archived references can be delivered only while their native provider is live. */
    fun canMaterialize(key: String): Boolean = runCatching {
        frozen?.find(key)?.let { frozenProvidersReady(it.recipe) }
            ?: (archivedSeal(key) != null)
    }.getOrDefault(false)

    /**
     * Renders the configured catalogue-only presentation. Callers displaying a
     * menu may use the optional ItemsAdder model; physical reward resolution
     * deliberately keeps using [preview] so that this visual stack cannot be
     * embedded in a voucher or archive.
     */
    internal fun visualPreview(entry: RewardCatalogEntry): ItemStack = renderPreview(entry, allowItemsAdder = true)

    /** Mints an archived collection seal marker; it has no bearer UUID. */
    fun createSealStack(categoryId: String): ItemStack? = runCatching {
        val archive = frozen ?: return@runCatching null
        archivedSeal(categoryId) ?: return@runCatching null
        val preview = archive.archivedSealPreview(categoryId) ?: return@runCatching null
        CollectionSealIdentity.mark(preview, categoryId)
    }.getOrNull()

    /** Resolver passed to CollectionSealController for archived set markers. */
    fun archivedSeal(categoryId: String): ArchivedCollectionSeal? = frozen?.archivedSeal(categoryId)?.takeIf { snapshot ->
        snapshot.choices.all { !requiresItemsAdder(it) || Bukkit.getPluginManager().isPluginEnabled("ItemsAdder") }
    }

    fun resolve(key: String): PhysicalRewardSpec? = runCatching {
        frozen?.find(key)?.let { archived ->
            val preview = frozen.preview(archived) ?: return@runCatching null
            return@runCatching PhysicalRewardSpec(
                key = archived.key,
                fingerprint = OneTimeUseFingerprint.parse(archived.fingerprint),
                preview = preview,
            )
        }
        val entry = entries[key] ?: return@runCatching null
        if (!providersReady(entry)) return@runCatching null
        val rewardPreview = when (entry.source) {
            is RewardCatalogSource.Seal -> inertSealPreview(entry) ?: return@runCatching null
            else -> preview(entry)
        }
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
            is RewardCatalogSource.Seal -> sealSnapshot(source.categoryId)?.definition ?: return@runCatching null
            else -> return@runCatching null
        }
        val fingerprint = OneTimeUseFingerprint.sha256(("catalog-v1\n$key\n$definition").toByteArray())
        PhysicalRewardSpec(key, fingerprint, rewardPreview)
    }.getOrNull()

    fun canRedeem(player: Player, spec: PhysicalRewardSpec): String? {
        frozen?.find(spec.key)?.let { archived ->
            if (!frozenProvidersReady(archived.recipe)) return UNAVAILABLE
            val requiredSlots = frozenRequiredSlots(archived.recipe) ?: return UNAVAILABLE
            if (player.inventory.storageContents.count { it == null || it.type.isAir } < requiredSlots) {
                return "<red>Освободите $requiredSlots яч. инвентаря для награды."
            }
            return null
        }
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
        frozen?.find(spec.key)?.let { archived ->
            if (!frozenProvidersReady(archived.recipe)) return completed(rejected())
            return redeemFrozen(archived.recipe, player, operationId)
        }
        val entry = entries[spec.key] ?: return completed(rejected())
        return when (val source = entry.source) {
            is RewardCatalogSource.Mount -> MountModule.grantReward(player, source.id).thenApply(::mountOutcome)
            is RewardCatalogSource.FurniturePackage -> completed(giveStacks(player, furnitureBoxes(source.id) ?: return completed(rejected())))
            is RewardCatalogSource.Treasure -> completed(redeemTreasure(player, treasure(source) ?: return completed(rejected()), operationId, emptySet()))
            else -> completed(rejected())
        }
    }

    private fun freezeRecipe(entry: RewardCatalogEntry): FrozenPhysicalRecipe? = runCatching {
        when (val source = entry.source) {
            is RewardCatalogSource.Mount -> FrozenPhysicalRecipe("mount", mountId = source.id)
            is RewardCatalogSource.FurniturePackage -> {
                val boxes = furnitureBoxes(source.id) ?: return@runCatching null
                FrozenPhysicalRecipe(
                    type = "furniture",
                    furnitureBoxes = boxes.map { Base64.getEncoder().encodeToString(it.serializeAsBytes()) },
                )
            }
            is RewardCatalogSource.Treasure -> {
                val treasure = treasure(source) ?: return@runCatching null
                val node = freezeTreasure(treasure, emptySet(), intArrayOf(MAX_GRAPH_NODES)) ?: return@runCatching null
                FrozenPhysicalRecipe(type = "treasure", treasure = node)
            }
            is RewardCatalogSource.Seal -> {
                val items = sealSnapshot(source.categoryId)?.items ?: return@runCatching null
                val category = settings.categories.firstOrNull { it.id == source.categoryId } ?: return@runCatching null
                FrozenPhysicalRecipe(
                    type = "seal",
                    sealItems = items.map { Base64.getEncoder().encodeToString(it.serializeAsBytes()) },
                    sealName = category.name,
                    sealDescription = category.description,
                )
            }
            else -> null
        }
    }.getOrNull()

    private fun freezeTreasure(
        value: Treasure,
        visitedPools: Set<String>,
        budget: IntArray,
    ): FrozenTreasureNode? {
        if (budget[0]-- <= 0) return null
        return runCatching {
            when (value) {
                is Treasure.Item -> {
                    val stack = value.stack.clone().takeUnless(::containsOneTimeIdentity)
                        ?: return@runCatching null
                    FrozenTreasureNode(
                        id = value.id,
                        type = "item",
                        weight = value.weight,
                        minInt = value.min,
                        maxInt = value.max,
                        stack = Base64.getEncoder().encodeToString(stack.serializeAsBytes()),
                        requiresItemsAdder = requiresItemsAdder(stack),
                    )
                }
                is Treasure.Money -> FrozenTreasureNode(
                    id = value.id,
                    type = "money",
                    weight = value.weight,
                    minDouble = value.min,
                    maxDouble = value.max,
                )
                is Treasure.Command -> {
                    val command = value.commands.singleOrNull()?.takeIf(::isAllowedCommand) ?: return@runCatching null
                    FrozenTreasureNode(value.id, "command", value.weight, commands = listOf(command))
                }
                is Treasure.SubPool -> {
                    if (value.poolId in visitedPools || visitedPools.size >= MAX_POOL_DEPTH) return@runCatching null
                    val pool = Treasures.getPool(value.poolId) ?: return@runCatching null
                    val children = pool.treasures.map { child ->
                        freezeTreasure(child, visitedPools + value.poolId, budget) ?: return@runCatching null
                    }
                    FrozenTreasureNode(
                        id = value.id,
                        type = "sub-pool",
                        weight = value.weight,
                        poolId = value.poolId,
                        children = children,
                    )
                }
                is Treasure.Enchant -> FrozenTreasureNode(
                    id = value.id,
                    type = "enchant",
                    weight = value.weight,
                    minInt = value.min,
                    maxInt = value.max,
                    exclude = value.exclude.toList().sorted(),
                )
                is Treasure.Potion -> FrozenTreasureNode(
                    id = value.id,
                    type = "potion",
                    weight = value.weight,
                    minInt = value.min,
                    maxInt = value.max,
                )
                is Treasure.Ae -> FrozenTreasureNode(
                    id = value.id,
                    type = "ae",
                    weight = value.weight,
                    aeKind = when (value.kind) {
                        AeKind.ITEM -> "item"
                        AeKind.RANDOM_BOOK -> "random_book"
                    },
                    itemName = value.itemName,
                    amount = value.amount,
                    aeArgs = value.args.map { arg ->
                        when (arg) {
                            AeArg.RandomTier -> FrozenAeArg("random-tier")
                            AeArg.RandomSlot -> FrozenAeArg("random-slot")
                            is AeArg.IntRange -> FrozenAeArg("int", arg.min, arg.max)
                        }
                    },
                )
                is Treasure.Slimefun -> {
                    val stack = HookRegistry.sfHook?.getSlimefunItemStack(value.itemId)?.clone()
                        ?.takeUnless(::containsOneTimeIdentity) ?: return@runCatching null
                    FrozenTreasureNode(
                        id = value.id,
                        type = "slimefun",
                        weight = value.weight,
                        minInt = value.min,
                        maxInt = value.max,
                        stack = Base64.getEncoder().encodeToString(stack.serializeAsBytes()),
                        itemId = value.itemId,
                    )
                }
            }
        }.getOrNull()
    }

    private fun frozenProvidersReady(recipe: FrozenPhysicalRecipe): Boolean = when (recipe.type) {
        "money" -> wallets.walletForCurrency(requireNotNull(recipe.currency)).let { it?.available == true }
        "tokens" -> wallets.walletForCurrency("tokens")?.available == true
        "command" -> commandProviderReady(requireNotNull(recipe.commandValue))
        "ae" -> Bukkit.getPluginManager().isPluginEnabled("AdvancedEnchantments")
        "mount" -> MountModule.rewardPreview(requireNotNull(recipe.mountId)) != null
        "furniture" -> Bukkit.getPluginManager().isPluginEnabled("ItemsAdder")
        "seal" -> recipe.sealItems.orEmpty().all { encoded ->
            decodeStack(encoded)?.let { !requiresItemsAdder(it) || Bukkit.getPluginManager().isPluginEnabled("ItemsAdder") } == true
        }
        "treasure" -> frozenTreasureProvidersReady(requireNotNull(recipe.treasure))
        else -> false
    }

    private fun frozenTreasureProvidersReady(node: FrozenTreasureNode): Boolean = when (node.type) {
        "item" -> !node.requiresItemsAdder || Bukkit.getPluginManager().isPluginEnabled("ItemsAdder")
        "enchant", "potion" -> true
        "money" -> wallets.walletForCurrency("vault")?.available == true
        "command" -> commandProviderReady(requireNotNull(node.commands).single())
        "sub-pool" -> node.children.orEmpty().filter { it.weight > 0 }.all(::frozenTreasureProvidersReady)
        "ae" -> Bukkit.getPluginManager().isPluginEnabled("AdvancedEnchantments")
        "slimefun" -> Bukkit.getPluginManager().isPluginEnabled("Slimefun")
            && HookRegistry.sfHook != null && decodeStack(requireNotNull(node.stack)) != null
        else -> false
    }

    private fun commandProviderReady(command: String): Boolean = when {
        tokenAmount(command) != null -> wallets.walletForCurrency("tokens")?.available == true
        command.startsWith("arcbuilder:") -> Bukkit.getPluginManager().isPluginEnabled("ArcBuilder")
        command.startsWith("arcecojobs:") -> Bukkit.getPluginManager().isPluginEnabled("ArcEcoJobs")
        command.startsWith("elitemobs:") -> Bukkit.getPluginManager().isPluginEnabled("EliteMobs")
        else -> false
    }

    private fun frozenRequiredSlots(recipe: FrozenPhysicalRecipe): Int? = when (recipe.type) {
        "money", "tokens" -> 0
        "command" -> if (tokenAmount(requireNotNull(recipe.commandValue)) != null) 0 else 1
        "ae" -> 1
        "mount" -> 0
        "furniture" -> recipe.furnitureBoxes?.size
        "seal" -> null
        "treasure" -> frozenTreasureRequiredSlots(requireNotNull(recipe.treasure), emptySet())
        else -> null
    }

    private fun frozenTreasureRequiredSlots(node: FrozenTreasureNode, visitedPools: Set<String>): Int? = when (node.type) {
        "money" -> 0
        "command" -> if (tokenAmount(requireNotNull(node.commands).single()) != null) 0 else 1
        "item" -> {
            val stack = decodeStack(requireNotNull(node.stack)) ?: return null
            (requireNotNull(node.maxInt) + stack.maxStackSize - 1) / stack.maxStackSize
        }
        "slimefun" -> {
            val stack = decodeStack(requireNotNull(node.stack)) ?: return null
            (requireNotNull(node.maxInt) + stack.maxStackSize - 1) / stack.maxStackSize
        }
        "enchant", "potion" -> requireNotNull(node.maxInt)
        "ae" -> requireNotNull(node.amount)
        "sub-pool" -> {
            val poolId = requireNotNull(node.poolId)
            if (poolId in visitedPools) return null
            node.children.orEmpty().filter { it.weight > 0 }
                .map { frozenTreasureRequiredSlots(it, visitedPools + poolId) ?: return null }.maxOrNull()
        }
        else -> null
    }

    private fun redeemFrozen(recipe: FrozenPhysicalRecipe, player: Player, operationId: UUID): CompletableFuture<PhysicalRewardOutcome> = when (recipe.type) {
        "mount" -> MountModule.grantReward(player, requireNotNull(recipe.mountId)).thenApply(::mountOutcome)
        else -> completed(redeemFrozenSync(recipe, player, operationId))
    }

    private fun redeemFrozenSync(recipe: FrozenPhysicalRecipe, player: Player, operationId: UUID): PhysicalRewardOutcome = when (recipe.type) {
        "money" -> redeemFrozenMoney(player, requireNotNull(recipe.minAmount), requireNotNull(recipe.maxAmount), operationId)
        "tokens" -> deposit(player, "tokens", requireNotNull(recipe.tokenAmount).toDouble(), operationId)
        "command" -> redeemFrozenCommand(player, requireNotNull(recipe.commandValue), operationId)
        "furniture" -> {
            val boxes = recipe.furnitureBoxes.orEmpty().map { decodeStack(it) ?: return PhysicalRewardOutcome.Rejected(UNAVAILABLE) }
            giveStacks(player, boxes)
        }
        "seal" -> PhysicalRewardOutcome.Rejected(UNAVAILABLE)
        "treasure" -> redeemFrozenTreasure(player, requireNotNull(recipe.treasure), operationId, emptySet())
        "ae" -> PhysicalRewardOutcome.Rejected(UNAVAILABLE)
        else -> PhysicalRewardOutcome.Rejected(UNAVAILABLE)
    }

    private fun redeemFrozenTreasure(
        player: Player,
        node: FrozenTreasureNode,
        operationId: UUID,
        visitedPools: Set<String>,
    ): PhysicalRewardOutcome = when (node.type) {
        "money" -> redeemFrozenMoney(player, requireNotNull(node.minDouble), requireNotNull(node.maxDouble), operationId)
        "command" -> redeemFrozenCommand(player, requireNotNull(node.commands).single(), operationId)
        "sub-pool" -> {
            val poolId = requireNotNull(node.poolId)
            if (poolId in visitedPools || visitedPools.size >= MAX_POOL_DEPTH) return PhysicalRewardOutcome.Rejected(UNAVAILABLE)
            val child = chooseFrozenChild(node.children.orEmpty()) ?: return PhysicalRewardOutcome.Rejected(UNAVAILABLE)
            redeemFrozenTreasure(player, child, operationId, visitedPools + poolId)
        }
        "item" -> {
            val stack = decodeStack(requireNotNull(node.stack)) ?: return PhysicalRewardOutcome.Rejected(UNAVAILABLE)
            giveStacks(player, split(stack, randomInt(requireNotNull(node.minInt), requireNotNull(node.maxInt))))
        }
        "slimefun" -> {
            val stack = decodeStack(requireNotNull(node.stack))
                ?: return PhysicalRewardOutcome.Rejected(UNAVAILABLE)
            giveStacks(player, split(stack, randomInt(requireNotNull(node.minInt), requireNotNull(node.maxInt))))
        }
        "enchant" -> {
            val value = Treasure.Enchant(requireNotNull(node.minInt), requireNotNull(node.maxInt), node.exclude.orEmpty().toSet())
            giveStacks(player, List(value.amount) { value.randomBook() })
        }
        "potion" -> {
            val value = Treasure.Potion(requireNotNull(node.minInt), requireNotNull(node.maxInt))
            giveStacks(player, List(value.amount) { Treasure.Potion.randomPotion() })
        }
        "ae" -> {
            val value = Treasure.Ae(
                kind = when (requireNotNull(node.aeKind)) {
                    "item" -> AeKind.ITEM
                    "random_book" -> AeKind.RANDOM_BOOK
                    else -> return PhysicalRewardOutcome.Rejected(UNAVAILABLE)
                },
                itemName = node.itemName,
                amount = requireNotNull(node.amount),
                args = node.aeArgs.orEmpty().map { arg ->
                    when (arg.type) {
                        "random-tier" -> AeArg.RandomTier
                        "random-slot" -> AeArg.RandomSlot
                        "int" -> AeArg.IntRange(requireNotNull(arg.min), requireNotNull(arg.max))
                        else -> return PhysicalRewardOutcome.Rejected(UNAVAILABLE)
                    }
                },
            )
            redeemTreasure(player, value, operationId, emptySet())
        }
        else -> PhysicalRewardOutcome.Rejected(UNAVAILABLE)
    }

    private fun redeemFrozenMoney(player: Player, min: Double, max: Double, operationId: UUID): PhysicalRewardOutcome =
        deposit(player, "vault", if (min == max) min else ThreadLocalRandom.current().nextDouble(min, max), operationId)

    private fun redeemFrozenCommand(player: Player, command: String, operationId: UUID): PhysicalRewardOutcome =
        tokenAmount(command)?.let { deposit(player, "tokens", it.toDouble(), operationId) } ?: giveNativeCommand(player, command)

    private fun mountOutcome(result: MountRewardResult): PhysicalRewardOutcome = when (result) {
        is MountRewardResult.Granted -> PhysicalRewardOutcome.Applied
        is MountRewardResult.AlreadyOwned -> PhysicalRewardOutcome.Rejected("<gold>Этот маунт уже открыт. Контракт можно передать другому игроку.")
        is MountRewardResult.Rejected -> if (result.reason == MountRewardRejection.GRANT_UNCERTAIN ||
            result.reason == MountRewardRejection.SHUTDOWN) uncertain() else rejected()
    }

    private fun chooseFrozenChild(children: List<FrozenTreasureNode>): FrozenTreasureNode? {
        val effective = children.filter { it.weight > 0 }
        val total = effective.sumOf { it.weight.toLong() }
        if (total <= 0L) return null
        var roll = ThreadLocalRandom.current().nextLong(total)
        effective.forEach { child ->
            roll -= child.weight.toLong()
            if (roll < 0L) return child
        }
        return effective.lastOrNull()
    }

    private fun randomInt(min: Int, max: Int): Int =
        if (min == max) min else ThreadLocalRandom.current().nextInt(min, max + 1)

    private fun decodeStack(encoded: String): ItemStack? = runCatching {
        Base64.getDecoder().decode(encoded).let(ItemStack::deserializeBytes).takeIf { !it.type.isAir }
    }.getOrNull()

    private fun requiresItemsAdder(stack: ItemStack): Boolean =
        stack.itemMeta?.persistentDataContainer?.keys?.any { it.namespace.equals("itemsadder", ignoreCase = true) } == true

    private fun isAllowedCommand(command: String): Boolean = tokenAmount(command) != null || NATIVE_ITEM_COMMANDS.any { it.second.matches(command) }

    private fun preview(entry: RewardCatalogEntry): ItemStack = renderPreview(entry, allowItemsAdder = false)

    private fun renderPreview(entry: RewardCatalogEntry, allowItemsAdder: Boolean): ItemStack {
        val style = entry.icon ?: CatalogIconStyle("PAPER")
        val stack = entry.previewItemsAdder
            ?.takeIf { allowItemsAdder }
            ?.let { id -> runCatching { CustomStack.getInstance(id)?.itemStack?.clone() }.getOrNull() }
            ?: ItemStack(Material.valueOf(style.material)).also {
                if (style.customModelData != 0) it.withCustomModelData(style.customModelData)
            }
        return stack.also {
            it.editMeta { meta ->
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

    /** Frozen templates must never carry a redeemable ARC identity into a new voucher. */
    private fun containsOneTimeIdentity(stack: ItemStack): Boolean =
        PhysicalRewardVoucher.identity(stack) != null || CollectionSealIdentity.categoryId(stack) != null

    /**
     * Captures every configured collection member. A seal is a set entitlement,
     * so silently dropping a currently unavailable member would turn one
     * entitlement into a smaller one. The whole snapshot therefore fails closed.
     */
    private fun sealSnapshot(categoryId: String): SealSnapshot? {
        val category = settings.categories.firstOrNull { it.id == categoryId }
            ?.takeIf { CollectionSealIdentity.isValidCategoryId(it.id) && it.entries.isNotEmpty() }
            ?: return null
        val items = ArrayList<ItemStack>(category.entries.size)
        for (entry in category.entries) {
            if (!providersReady(entry)) return null
            val base = when (val source = entry.source) {
                is RewardCatalogSource.Treasure -> runCatching {
                    Treasures.getPool(source.pool)?.findById(source.id) as? Treasure.Item
                }.getOrNull()?.stack?.clone()
                is RewardCatalogSource.ItemsAdder -> {
                    if (!Bukkit.getPluginManager().isPluginEnabled("ItemsAdder")) return null
                    runCatching { CustomStack.getInstance(source.id)?.itemStack?.clone() }.getOrNull()
                }
                else -> null
            } ?: return null
            if (base.type.isAir) return null
            val enriched = RewardItemEnhancer.enrich(base, entry.enchantments) ?: return null
            val presented = RewardItemPresentation.apply(enriched, entry).also { it.amount = 1 }
            if (containsOneTimeIdentity(presented)) return null
            items += presented
        }
        val definition = buildString {
            append("seal-v1\n").append(category.id)
            category.entries.zip(items).forEachIndexed { index, (entry, stack) ->
                append('\n').append(index).append(':').append(entry.id).append(':')
                    .append(OneTimeUseFingerprint.sha256(stack.serializeAsBytes()).sha256)
            }
        }
        return SealSnapshot(items, definition)
    }

    /**
     * Archives the configured seal's icon/name/lore without retaining its
     * redeemable PDC marker. A voucher bearer is deliberately rejected rather
     * than partially sanitised: its UUID must never enter an archive preview.
     */
    private fun inertSealPreview(entry: RewardCatalogEntry): ItemStack? {
        val source = entry.source as? RewardCatalogSource.Seal ?: return null
        val stack = runCatching { sealStack(source.categoryId, entry.icon)?.clone() }.getOrNull()
            ?: return null
        if (stack.type.isAir) return null
        stack.editMeta { meta ->
            meta.persistentDataContainer.remove(CollectionSealIdentity.key)
            meta.persistentDataContainer.remove(CollectionSealIdentity.versionKey)
        }
        return stack.takeUnless(::containsOneTimeIdentity)
    }

    private fun supported(value: Treasure): Boolean = when (value) {
        is Treasure.Command -> tokenAmount(value) != null || value.commands.singleOrNull()?.let { command -> NATIVE_ITEM_COMMANDS.any { it.second.matches(command) } } == true
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

    private data class SealSnapshot(val items: List<ItemStack>, val definition: String)
    private fun rejected() = PhysicalRewardOutcome.Rejected(UNAVAILABLE)
    private fun uncertain() = PhysicalRewardOutcome.Uncertain("<gold>Результат требует проверки. Сохраните предмет и сообщите администрации.")
    private fun completed(outcome: PhysicalRewardOutcome) = CompletableFuture.completedFuture(outcome)

    companion object {
        private const val UNAVAILABLE = "<red>Награда сейчас недоступна. Предмет сохранён."
        private const val MAX_GRAPH_NODES = 2_048
        private const val MAX_POOL_DEPTH = 8
        private val TOKEN_COMMAND = Regex("rediseconomy:balance %player% tokens give ([1-9][0-9]{0,5}) arc-lootbox-catalog")
        private val NATIVE_ITEM_COMMANDS = listOf(
            "arcbuilder" to Regex("arcbuilder:builder systembook %player% [a-z0-9_-]+\\.schem"),
            "arcecojobs" to Regex("arcecojobs:arcjobs booster give %player% [a-z0-9_-]+ 1"),
            "elitemobs" to Regex("elitemobs:elitemobs loot give %player% [a-z0-9_-]+\\.yml"),
        )
        internal fun tokenAmount(value: Treasure.Command): Long? = value.commands.singleOrNull()?.let(::tokenAmount)
        internal fun tokenAmount(command: String): Long? = TOKEN_COMMAND.matchEntire(command)?.groupValues?.get(1)?.toLongOrNull()
    }
}
