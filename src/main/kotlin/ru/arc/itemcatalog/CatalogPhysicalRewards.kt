package ru.arc.itemcatalog

import com.magmaguy.elitemobs.items.ItemConsumables
import com.magmaguy.elitemobs.items.customitems.CustomItem
import dev.lone.itemsadder.api.CustomStack
import io.papermc.paper.datacomponent.DataComponentTypes
import net.kyori.adventure.key.Key
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import ru.arc.hooks.HookRegistry
import ru.arc.ops.ItemPresets
import ru.arc.hooks.elitemobs.DungeonCaseRewards
import ru.arc.mounts.MountModule
import ru.arc.mounts.MountRewardRejection
import ru.arc.mounts.MountRewardResult
import ru.arc.mounts.MountWallet
import ru.arc.mounts.RedisEconomyMountWallet
import ru.arc.onetime.OneTimeUseFingerprint
import ru.arc.treasure.core.AeArg
import ru.arc.treasure.core.AeLoot
import ru.arc.treasure.core.AeNativeItems
import ru.arc.treasure.core.AeKind
import ru.arc.treasure.core.MessageContext
import ru.arc.treasure.core.Treasure
import ru.arc.treasure.core.TreasureConfig
import ru.arc.treasure.core.TreasureMessage
import ru.arc.treasure.core.TreasureStackFactory
import ru.arc.treasure.core.Treasures
import ru.arc.travelanchors.TravelAnchorsModule
import ru.arc.util.TextUtil
import ru.arc.util.Logging.warn
import ru.arc.util.withCustomModelData
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ThreadLocalRandom

/** Converts provider-backed sources into durable physical entitlements. Native effects run only after a durable claim. */
internal class CatalogPhysicalRewards(
    private val settings: RewardCatalogSettings,
    private val wallets: MountWallet? = null,
    private val frozen: FrozenPhysicalRewards? = null,
    /** Creates the configured seal so its authored icon and presentation survive archiving. */
    private val sealStack: (String, CatalogIconStyle?) -> ItemStack? = { _, _ -> null },
) {
    private val particlePresets by lazy { ParticlePresetRewards() }
    private val treasureStackFactory by lazy { TreasureStackFactory() }
    private val preparedInteractive = ConcurrentHashMap<String, PhysicalRewardMaterialization>()
    private val interactiveReady = AtomicBoolean(false)
    private val entries = settings.categories.filter { it.rolls == null }.flatMap { it.entries }
        .plus(settings.categories.filter { it.rolls != null }.flatMap { it.entries })
        .distinctBy { key(it) }.associateBy { key(it) }

    private fun activeWallet(): MountWallet? =
        wallets ?: HookRegistry.redisEcoHook?.let { RedisEconomyMountWallet() }

    fun key(entry: RewardCatalogEntry): String = when (val source = entry.source) {
        is RewardCatalogSource.Treasure -> "treasure:${source.pool}:${source.id}"
        is RewardCatalogSource.Mount -> "mount:${source.id}"
        is RewardCatalogSource.FurniturePackage -> "package:${source.id}"
        is RewardCatalogSource.Seal -> "seal:${source.categoryId}"
        is RewardCatalogSource.DungeonCase -> "dungeon-case:${source.id}"
        is RewardCatalogSource.TravelAnchors -> "travel-anchors:${source.amount}"
        is RewardCatalogSource.ParticlePreset -> "particle-preset:${source.id}"
        is RewardCatalogSource.Choice -> "choice:${OneTimeUseFingerprint.sha256(source.options.joinToString("\n") { "${it.id}|${it.categoryId}|${it.entryId}" }.toByteArray()).sha256}"
        is RewardCatalogSource.PersonalMap -> {
            val policy = source.searchPolicy
            val fingerprint = if (policy.bounds == null && policy.additionalWorlds.isEmpty() && source.prizeRolls == 1) {
                // Preserve bearer addresses for the legacy one-roll, radius-based recipe.
                OneTimeUseFingerprint.sha256Fields(
                    "personal-map-source-v2", entry.id, source.rewardCategoryId, source.rewardEntryId,
                    policy.server, policy.world, requireNotNull(policy.radius).toString(),
                )
            } else {
                OneTimeUseFingerprint.sha256Fields(
                    "personal-map-source-v3", entry.id, source.rewardCategoryId, source.rewardEntryId,
                    policy.targetPolicyFingerprint.sha256, source.prizeRolls.toString(),
                )
            }
            "personal-map:${fingerprint.sha256}"
        }
        else -> "native:${source}"
    }

    /**
     * Identifies sources that require a durable physical materialization in the
     * catalogue. Collection seals use a category marker; they are not bearer vouchers.
     */
    fun isVoucherSource(entry: RewardCatalogEntry): Boolean = when (val source = entry.source) {
        is RewardCatalogSource.Mount,
        is RewardCatalogSource.FurniturePackage,
        is RewardCatalogSource.DungeonCase,
        is RewardCatalogSource.TravelAnchors,
        is RewardCatalogSource.ParticlePreset,
        is RewardCatalogSource.Choice,
        is RewardCatalogSource.PersonalMap,
        -> true
        is RewardCatalogSource.Treasure -> when (val value = treasure(source)) {
            is Treasure.Ae -> !AeNativeItems.supports(value)
            is Treasure.Item,
            is Treasure.Slimefun,
            is Treasure.Enchant,
            is Treasure.Potion,
            is Treasure.Preset,
            null,
            -> false
            else -> true
        }
        is RewardCatalogSource.Seal -> true
        else -> false
    }

    fun materialization(entry: RewardCatalogEntry): PhysicalRewardMaterialization? = runCatching {
        if (!isVoucherSource(entry)) return null
        val sourceKey = key(entry)
        if (entry.source is RewardCatalogSource.Choice || entry.source is RewardCatalogSource.PersonalMap) {
            if (!interactiveReady.get()) return null
            return preparedInteractive[sourceKey]
        }
        val spec = resolve(sourceKey) ?: return null
        // Fixed, allowlisted entitlements must resolve on either backend without a local recipe archive.
        if (entry.source is RewardCatalogSource.ParticlePreset) {
            return PhysicalRewardMaterialization(sourceKey, spec.fingerprint.sha256)
        }
        val archive = frozen ?: return null
        val recipe = freezeRecipe(entry) ?: return null
        if (!frozenProvidersReady(recipe)) return null
        val archived = archive.prepare(sourceKey, recipe, RewardItemPresentation.tooltip(spec.preview, entry)) ?: return null
        if (entry.source is RewardCatalogSource.Seal) {
            val categoryId = archive.archivedCategoryId(archived.sourceKey) ?: return null
            return PhysicalRewardMaterialization(categoryId, archived.providerFingerprint)
        }
        return archived
    }.getOrNull()

    /** Captures all configured choice children and parent recipes on the Bukkit owner thread. */
    fun captureInteractiveArchives(): List<FrozenPhysicalPrepared>? = runCatching {
        val archive = frozen ?: return@runCatching emptyList()
        val interactiveEntries = entries.values.filter {
            it.source is RewardCatalogSource.Choice || it.source is RewardCatalogSource.PersonalMap
        }
        val prepared = linkedMapOf<String, FrozenPhysicalPrepared>()
        fun capture(sourceKey: String, recipe: FrozenPhysicalRecipe, preview: ItemStack): FrozenPhysicalPrepared? {
            val snapshot = archive.capture(sourceKey, recipe, preview) ?: return null
            val previous = prepared.putIfAbsent(snapshot.record.fingerprint, snapshot)
            require(previous == null || previous.record == snapshot.record) { "Choice archive fingerprint collision" }
            return previous ?: snapshot
        }
        val interactiveSnapshots = mutableListOf<FrozenPhysicalPrepared>()
        fun captureChild(categoryId: String, entryId: String): Pair<RewardCatalogEntry, FrozenPhysicalPrepared>? {
            val (_, child) = settings.entry(categoryId, entryId) ?: return null
            if (child.source is RewardCatalogSource.Choice || child.source is RewardCatalogSource.PersonalMap) return null
            val childSourceKey = key(child)
            val childSpec = resolve(childSourceKey) ?: return null
            val childRecipe = freezeRecipe(child) ?: return null
            if (!frozenProvidersReady(childRecipe)) return null
            val snapshot = capture(
                childSourceKey,
                childRecipe,
                RewardItemPresentation.tooltip(childSpec.preview, child),
            ) ?: return null
            return child to snapshot
        }
        interactiveEntries.forEach { entry ->
            val recipe = when (val source = entry.source) {
                is RewardCatalogSource.Choice -> {
                    val childSourceKeys = mutableSetOf<String>()
                    val options = source.options.map { ref ->
                        val (child, archivedChild) = captureChild(ref.categoryId, ref.entryId) ?: return@runCatching null
                        if (!childSourceKeys.add(key(child))) return@runCatching null
                        FrozenChoiceOption(
                            id = ref.id,
                            name = child.name ?: ref.id,
                            description = child.description,
                            childKey = archivedChild.materialization.sourceKey,
                            childFingerprint = archivedChild.materialization.providerFingerprint,
                        )
                    }
                    if (options.map { it.childKey }.distinct().size != options.size) return@runCatching null
                    FrozenPhysicalRecipe(type = "choice", choiceOptions = options)
                }
                is RewardCatalogSource.PersonalMap -> {
                    val (child, archivedChild) = captureChild(source.rewardCategoryId, source.rewardEntryId)
                        ?: return@runCatching null
                    FrozenPhysicalRecipe(
                        type = "personal-map",
                        mapId = entry.id,
                        mapPrizeKey = archivedChild.materialization.sourceKey,
                        mapPrizeFingerprint = archivedChild.materialization.providerFingerprint,
                        mapPrizeRolls = source.prizeRolls,
                        mapSearchServer = source.searchPolicy.server,
                        mapSearchWorld = source.searchPolicy.world,
                        mapSearchRadius = source.searchPolicy.radius,
                        mapSearchMinX = source.searchPolicy.bounds?.minX,
                        mapSearchMaxX = source.searchPolicy.bounds?.maxX,
                        mapSearchMinZ = source.searchPolicy.bounds?.minZ,
                        mapSearchMaxZ = source.searchPolicy.bounds?.maxZ,
                        mapSearchMinDistance = source.searchPolicy.minDistance.takeIf { source.searchPolicy.bounds != null },
                        mapSearchAdditionalWorlds = source.searchPolicy.additionalWorlds.sorted().takeIf { it.isNotEmpty() },
                    )
                }
                else -> return@forEach
            }
            val composite = capture(
                key(entry),
                recipe,
                RewardItemPresentation.tooltip(preview(entry), entry),
            ) ?: return@runCatching null
            interactiveSnapshots += composite
        }
        // Interactive parents are persisted after all frozen prize/option records.
        prepared.values.filter { it.record.recipe.type !in INTERACTIVE_RECIPE_TYPES } +
            interactiveSnapshots.distinctBy { it.record.fingerprint }
    }.getOrNull()

    /** Commits captured interactive children and parents in dependency order on a storage executor. */
    fun persistInteractiveArchives(snapshots: List<FrozenPhysicalPrepared>): Boolean = runCatching {
        val archive = frozen ?: return false
        val materializations = linkedMapOf<String, PhysicalRewardMaterialization>()
        snapshots.forEach { snapshot ->
            val materialization = archive.persist(snapshot) ?: return false
            if (snapshot.record.recipe.type in INTERACTIVE_RECIPE_TYPES) {
                materializations[snapshot.record.sourceKey] = materialization
            }
        }
        preparedInteractive.clear()
        preparedInteractive.putAll(materializations)
        interactiveReady.set(true)
        true
    }.getOrDefault(false)

    /** Resolves the exact map definition archived with this bearer source. */
    fun personalMapDefinition(spec: PhysicalRewardSpec): PersonalTreasureMapDefinition? {
        val archived = frozen?.find(spec.key)?.takeIf {
            it.fingerprint == spec.fingerprint.sha256 && it.recipe.type == "personal-map"
        } ?: return null
        val recipe = archived.recipe
        val mapId = recipe.mapId ?: return null
        val legacyRoute = recipe.mapSearchServer == null
        val migrateWeeklyRadiusRoute = isPublishedWeeklyMapLegacyRoute(recipe)
        val migrateWeeklyBoundsRoute = isPublishedWeeklyMapLegacyBoundedRoute(recipe)
        val migrateWeeklyRoute = migrateWeeklyRadiusRoute || migrateWeeklyBoundsRoute
        val redirectToCurrent = legacyRoute || migrateWeeklyRadiusRoute
        val archivedPrize = if (legacyRoute) null else {
            frozen.find(requireNotNull(recipe.mapPrizeKey))?.takeIf {
                it.fingerprint == recipe.mapPrizeFingerprint
            } ?: return null
        }
        val prize = if (redirectToCurrent) {
            currentMapPrize(mapId) ?: return null
        } else {
            requireNotNull(archivedPrize)
        }
        val searchPolicy = if (redirectToCurrent || migrateWeeklyBoundsRoute) {
            currentMapSource(mapId)?.searchPolicy ?: return null
        } else {
            recipe.searchPolicy() ?: return null
        }
        val legacyDestinations = recipe.mapDestinations.orEmpty()
        val legacyFingerprint = if (legacyRoute) {
            PersonalTreasureMapDefinition.legacyFingerprint(
                mapId,
                requireNotNull(recipe.mapPrizeKey),
                legacyDestinations,
            )
        } else null
        val oldSearchPolicy = recipe.searchPolicy().takeIf { migrateWeeklyRoute }
        val prizeRolls = if (redirectToCurrent) currentMapSource(mapId)?.prizeRolls ?: return null
            else recipe.mapPrizeRolls ?: 1
        val archivedIdentityFingerprint = if (migrateWeeklyRoute) {
            PersonalTreasureMapDefinition(
                id = mapId,
                prizeSourceRef = requireNotNull(archivedPrize).key,
                destinations = emptyList(),
                searchPolicy = requireNotNull(oldSearchPolicy),
                prizeRolls = recipe.mapPrizeRolls ?: 1,
            ).fingerprint
        } else null
        return runCatching {
            PersonalTreasureMapDefinition(
                id = mapId,
                prizeSourceRef = prize.key,
                destinations = legacyDestinations,
                searchPolicy = searchPolicy,
                prizeRolls = prizeRolls,
                identityFingerprintOverride = legacyFingerprint ?: archivedIdentityFingerprint,
                legacyTargetPolicy = oldSearchPolicy,
                preserveLegacyTarget = migrateWeeklyBoundsRoute,
            )
        }.getOrNull()
    }

    /** Archived references can be delivered only while their native provider is live. */
    fun canMaterialize(key: String): Boolean = runCatching {
        if (entries[key]?.source is RewardCatalogSource.ParticlePreset) return resolve(key) != null
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
            val preview = if (archived.recipe.type == "personal-map") {
                currentMapPreview(requireNotNull(archived.recipe.mapId)) ?: frozen.preview(archived)
            } else frozen.preview(archived)
            val resolvedPreview = preview ?: return@runCatching null
            when (archived.recipe.type) {
                "choice" -> archived.recipe.choiceOptions.orEmpty().forEach { option ->
                    val child = frozen.find(option.childKey) ?: return@runCatching null
                    if (child.fingerprint != option.childFingerprint) return@runCatching null
                }
                "personal-map" -> {
                    val child = frozen.find(requireNotNull(archived.recipe.mapPrizeKey)) ?: return@runCatching null
                    if (child.fingerprint != archived.recipe.mapPrizeFingerprint) return@runCatching null
                }
            }
            val choiceOptions = if (archived.recipe.type == "choice") {
                archived.recipe.choiceOptions.orEmpty().map { option ->
                    PhysicalRewardChoiceOption(
                        option.id, option.name, option.description, option.childKey, option.childFingerprint,
                    )
                }
            } else null
            return@runCatching PhysicalRewardSpec(
                key = archived.key,
                fingerprint = OneTimeUseFingerprint.parse(archived.fingerprint),
                preview = resolvedPreview,
                choiceOptions = choiceOptions,
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
                    "$id:${pack.weightFor(id)}:${OneTimeUseFingerprint.sha256(native.serializeAsBytes()).sha256}"
                }
                "package-rolls-v1:${pack.minRolls}:${pack.maxRolls}\n${contents.joinToString("\n")}"
            }
            is RewardCatalogSource.Treasure -> {
                val treasure = treasure(source) ?: return@runCatching null
                if (!supported(treasure)) return@runCatching null
                definition(treasure, emptySet()) ?: return@runCatching null
            }
            is RewardCatalogSource.Seal -> sealSnapshot(source.categoryId)?.definition ?: return@runCatching null
            is RewardCatalogSource.DungeonCase -> DungeonCaseRewards.definition(source.id) ?: return@runCatching null
            is RewardCatalogSource.TravelAnchors -> {
                if (!TravelAnchorsModule.isEnabled) return@runCatching null
                "travel-anchors-v1:${source.amount}"
            }
            is RewardCatalogSource.ParticlePreset -> "particle-preset-v1:${source.id}"
            else -> return@runCatching null
        }
        val fingerprint = OneTimeUseFingerprint.sha256(("catalog-v1\n$key\n$definition").toByteArray())
        PhysicalRewardSpec(key, fingerprint, RewardItemPresentation.tooltip(rewardPreview, entry))
    }.getOrNull()

    fun canRedeem(player: Player, spec: PhysicalRewardSpec): String? {
        frozen?.find(spec.key)?.let { archived ->
            val recipe = selectedRecipe(archived.recipe, spec) ?: return UNAVAILABLE
            if (!frozenProvidersReady(recipe)) return UNAVAILABLE
            if (recipe.type == "particle-preset") {
                return particlePresets.canRedeem(player, requireNotNull(recipe.particlePresetId))
            }
            val requiredSlots = if (recipe.type == "travel-anchors") {
                TravelAnchorsModule.createPersonalAnchorStacks(
                    player.name,
                    requireNotNull(recipe.travelAnchorAmount),
                )?.size ?: return UNAVAILABLE
            } else {
                frozenRequiredSlots(recipe) ?: return UNAVAILABLE
            }
            val furnitureRolls = recipe.type == "furniture-rolls"
            if ((recipe.treasureRolls != null || furnitureRolls) && requiredSlots > PLAYER_STORAGE_SLOTS) {
                return if (furnitureRolls) furnitureInventoryFull(requiredSlots) else INVENTORY_FULL
            }
            if (player.inventory.storageContents.count { it == null || it.type.isAir } < requiredSlots) {
                return if (furnitureRolls) furnitureInventoryFull(requiredSlots)
                else if (recipe.treasureRolls != null) INVENTORY_FULL
                    else "<red>Освободите $requiredSlots яч. инвентаря для награды."
            }
            return null
        }
        val entry = entries[spec.key] ?: return UNAVAILABLE
        if (!providersReady(entry)) return UNAVAILABLE
        if (entry.source is RewardCatalogSource.ParticlePreset) {
            return particlePresets.canRedeem(player, entry.source.id)
        }
        val requiredSlots = when (val source = entry.source) {
            is RewardCatalogSource.FurniturePackage -> {
                val recipe = freezeRecipe(entry) ?: return UNAVAILABLE
                if (!frozenProvidersReady(recipe)) return UNAVAILABLE
                frozenRequiredSlots(recipe) ?: return UNAVAILABLE
            }
            is RewardCatalogSource.Mount -> 0
            is RewardCatalogSource.DungeonCase -> 1
            is RewardCatalogSource.TravelAnchors ->
                TravelAnchorsModule.createPersonalAnchorStacks(player.name, source.amount)?.size ?: return UNAVAILABLE
            is RewardCatalogSource.Treasure -> requiredSlots(treasure(source) ?: return UNAVAILABLE, emptySet()) ?: return UNAVAILABLE
            else -> return UNAVAILABLE
        }
        if (player.inventory.storageContents.count { it == null || it.type.isAir } < requiredSlots) {
            return if (entry.source is RewardCatalogSource.FurniturePackage) {
                furnitureInventoryFull(requiredSlots)
            } else {
                "<red>Освободите $requiredSlots яч. инвентаря для награды."
            }
        }
        return null
    }

    fun redeem(player: Player, spec: PhysicalRewardSpec, operationId: UUID): CompletableFuture<PhysicalRewardOutcome> {
        frozen?.find(spec.key)?.let { archived ->
            val recipe = selectedRecipe(archived.recipe, spec) ?: return completed(rejected())
            if (!frozenProvidersReady(recipe)) return completed(rejected())
            return redeemFrozen(recipe, player, operationId)
        }
        val entry = entries[spec.key] ?: return completed(rejected())
        return when (val source = entry.source) {
            is RewardCatalogSource.Mount -> MountModule.grantReward(player, source.id).thenApply(::mountOutcome)
            is RewardCatalogSource.ParticlePreset -> particlePresets.redeem(player, source.id)
            is RewardCatalogSource.FurniturePackage -> {
                val recipe = freezeRecipe(entry) ?: return completed(rejected())
                if (!frozenProvidersReady(recipe)) completed(rejected())
                else completed(redeemFrozenFurnitureRolls(player, recipe))
            }
            is RewardCatalogSource.Treasure -> completed(redeemTreasure(player, treasure(source) ?: return completed(rejected()), operationId, emptySet()))
            is RewardCatalogSource.DungeonCase -> completed(
                giveStacks(player, listOf(DungeonCaseRewards.create(player, source.id) ?: return completed(rejected()))),
            )
            is RewardCatalogSource.TravelAnchors -> completed(
                giveStacks(
                    player,
                    TravelAnchorsModule.createPersonalAnchorStacks(player.name, source.amount)
                        ?: return completed(rejected()),
                ),
            )
            else -> completed(rejected())
        }
    }

    private fun freezeRecipe(entry: RewardCatalogEntry): FrozenPhysicalRecipe? = runCatching {
        when (val source = entry.source) {
            is RewardCatalogSource.Mount -> FrozenPhysicalRecipe("mount", mountId = source.id)
            is RewardCatalogSource.ParticlePreset -> FrozenPhysicalRecipe("particle-preset", particlePresetId = source.id)
            is RewardCatalogSource.FurniturePackage -> freezeFurniturePackage(source.id)
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
            is RewardCatalogSource.DungeonCase -> FrozenPhysicalRecipe(
                type = "dungeon-case",
                dungeonCaseId = source.id,
                dungeonCaseDefinition = DungeonCaseRewards.definition(source.id) ?: return@runCatching null,
            )
            is RewardCatalogSource.TravelAnchors -> FrozenPhysicalRecipe(
                type = "travel-anchors",
                travelAnchorAmount = source.amount,
            )
            else -> null
        }
    }.getOrNull()

    private fun freezeFurniturePackage(id: String): FrozenPhysicalRecipe? = runCatching {
        val pack = settings.packages[id] ?: return@runCatching null
        val children = pack.items.map { itemId ->
            val stack = CustomStack.getInstance(itemId)?.itemStack?.clone() ?: return@runCatching null
            if (stack.type.isAir || containsOneTimeIdentity(stack)) return@runCatching null
            FrozenTreasureNode(
                id = itemId,
                type = "item",
                weight = pack.weightFor(itemId),
                minInt = 1,
                maxInt = 1,
                stack = Base64.getEncoder().encodeToString(stack.serializeAsBytes()),
                requiresItemsAdder = true,
            )
        }
        FrozenPhysicalRecipe(
            type = "furniture-rolls",
            furnitureMinRolls = pack.minRolls,
            furnitureMaxRolls = pack.maxRolls,
            treasure = FrozenTreasureNode(
                id = id,
                type = "sub-pool",
                weight = 1,
                poolId = "furniture-package:$id",
                children = children,
            ),
        ).also { it.validate() }
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
                    aeGroup = value.group,
                    aeMaxLevel = value.maxLevel,
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
                is Treasure.Preset -> {
                    val stacks = ItemPresets.resolveStacks(value.preset, value.amount).getOrNull()
                        ?: return@runCatching null
                    // Archived choice rewards freeze their selected contents so later config edits cannot mutate an issued voucher.
                    if (stacks.size != 1) return@runCatching null
                    val stack = stacks.single().clone().takeUnless(::containsOneTimeIdentity)
                        ?: return@runCatching null
                    val amount = stack.amount
                    stack.amount = 1
                    FrozenTreasureNode(
                        id = value.id,
                        type = "item",
                        weight = value.weight,
                        minInt = amount,
                        maxInt = amount,
                        stack = Base64.getEncoder().encodeToString(stack.serializeAsBytes()),
                        requiresItemsAdder = requiresItemsAdder(stack),
                    )
                }
            }
        }.getOrNull()
    }

    private fun frozenProvidersReady(recipe: FrozenPhysicalRecipe): Boolean = when (recipe.type) {
        "money" -> activeWallet()?.walletForCurrency(requireNotNull(recipe.currency)).let { it?.available == true }
        "tokens" -> activeWallet()?.walletForCurrency("tokens")?.available == true
        "command" -> commandProviderReady(requireNotNull(recipe.commandValue))
        "ae" -> Bukkit.getPluginManager().isPluginEnabled("AdvancedEnchantments")
        "mount" -> MountModule.rewardPreview(requireNotNull(recipe.mountId)) != null
        "furniture" -> Bukkit.getPluginManager().isPluginEnabled("ItemsAdder")
        "furniture-rolls" -> frozenTreasureIsItemOnly(recipe.treasure, emptySet()) &&
            frozenTreasureProvidersReady(requireNotNull(recipe.treasure))
        "seal" -> recipe.sealItems.orEmpty().all { encoded ->
            decodeStack(encoded)?.let { !requiresItemsAdder(it) || Bukkit.getPluginManager().isPluginEnabled("ItemsAdder") } == true
        }
        "treasure" -> (recipe.treasureRolls == null || frozenTreasureIsItemOnly(recipe.treasure, emptySet())) &&
            frozenTreasureProvidersReady(requireNotNull(recipe.treasure))
        "dungeon-case" -> Bukkit.getPluginManager().isPluginEnabled("EliteMobs") &&
            DungeonCaseRewards.definition(requireNotNull(recipe.dungeonCaseId)) == recipe.dungeonCaseDefinition
        "travel-anchors" -> TravelAnchorsModule.isEnabled
        "particle-preset" -> particlePresets.ready(requireNotNull(recipe.particlePresetId))
        "choice" -> recipe.choiceOptions.orEmpty().all { option ->
            frozen?.find(option.childKey)?.let { child ->
                child.fingerprint == option.childFingerprint && frozenProvidersReady(child.recipe)
            } == true
        }
        "personal-map" -> effectiveMapPrize(recipe)?.let { prize ->
            val rolls = effectiveMapPrizeRolls(recipe) ?: return@let false
            (rolls == 1 || prize.recipe.type == "treasure" &&
                frozenTreasureIsItemOnly(prize.recipe.treasure, emptySet())) && frozenProvidersReady(prize.recipe)
        } == true
        else -> false
    }

    private fun selectedRecipe(recipe: FrozenPhysicalRecipe, spec: PhysicalRewardSpec): FrozenPhysicalRecipe? {
        if (recipe.type == "personal-map") {
            if (spec.selectedChoiceIndex != null) return null
            val definition = personalMapDefinition(spec) ?: return null
            val child = frozen?.find(definition.prizeSourceRef) ?: return null
            if (child.key != definition.prizeSourceRef) return null
            if (definition.prizeRolls == 1) return child.recipe
            if (child.recipe.type != "treasure") return null
            return child.recipe.copy(treasureRolls = definition.prizeRolls)
        }
        val childAddress = when (recipe.type) {
            "choice" -> {
                val index = spec.selectedChoiceIndex ?: return null
                val option = recipe.choiceOptions?.getOrNull(index) ?: return null
                option.childKey to option.childFingerprint
            }
            else -> return recipe.takeIf { spec.selectedChoiceIndex == null }
        }
        val (childKey, childFingerprint) = childAddress
        val child = frozen?.find(requireNotNull(childKey)) ?: return null
        return child.recipe.takeIf { child.fingerprint == childFingerprint }
    }

    private fun effectiveMapPrizeRolls(recipe: FrozenPhysicalRecipe): Int? =
        if (recipe.mapSearchServer == null || isPublishedWeeklyMapLegacyRoute(recipe)) {
            currentMapSource(requireNotNull(recipe.mapId))?.prizeRolls
        } else {
            recipe.mapPrizeRolls ?: 1
        }

    private fun frozenTreasureIsItemOnly(node: FrozenTreasureNode?, visitedPools: Set<String>): Boolean {
        node ?: return false
        return when (node.type) {
            "item" -> decodeStack(node.stack ?: return false)?.let { !containsOneTimeIdentity(it) } == true
            "sub-pool" -> {
                val poolId = node.poolId ?: return false
                if (poolId in visitedPools || visitedPools.size >= MAX_POOL_DEPTH) return false
                val selectable = node.children.orEmpty().filter { it.weight > 0 }
                selectable.isNotEmpty() && selectable.all {
                    frozenTreasureIsItemOnly(it, visitedPools + poolId)
                }
            }
            else -> false
        }
    }

    /** Old issued maps keep their voucher address but redirect prize selection through the current map entry. */
    private fun effectiveMapPrize(recipe: FrozenPhysicalRecipe): FrozenPhysicalRewardRecord? {
        if (recipe.type != "personal-map") return null
        val child = if (recipe.mapSearchServer == null || isPublishedWeeklyMapLegacyRoute(recipe)) {
            currentMapPrize(requireNotNull(recipe.mapId))
        } else {
            frozen?.find(requireNotNull(recipe.mapPrizeKey))?.takeIf {
                it.fingerprint == recipe.mapPrizeFingerprint
            }
        } ?: return null
        return child
    }

    private fun isPublishedWeeklyMapLegacyRoute(recipe: FrozenPhysicalRecipe): Boolean =
        recipe.type == "personal-map" &&
            recipe.mapId == "weekly_personal_map" &&
            recipe.mapDestinations == null &&
            recipe.mapSearchServer == "survival" &&
            recipe.mapSearchWorld in setOf("world", "survival") &&
            recipe.mapSearchRadius == 96 &&
            recipe.mapSearchMinX == null && recipe.mapSearchMaxX == null &&
            recipe.mapSearchMinZ == null && recipe.mapSearchMaxZ == null &&
            (recipe.mapSearchMinDistance == null ||
                recipe.mapSearchMinDistance == PersonalTreasureMapSearchPolicy.LEGACY_MIN_TARGET_DISTANCE)

    private fun isPublishedWeeklyMapLegacyBoundedRoute(recipe: FrozenPhysicalRecipe): Boolean =
        recipe.type == "personal-map" &&
            recipe.mapId == "weekly_personal_map" &&
            recipe.mapDestinations == null &&
            recipe.mapSearchServer == "survival" &&
            recipe.mapSearchWorld == "survival" &&
            recipe.mapSearchRadius == null &&
            recipe.mapSearchMinX == -9650 && recipe.mapSearchMaxX == 9650 &&
            recipe.mapSearchMinZ == -9650 && recipe.mapSearchMaxZ == 9650 &&
            recipe.mapSearchMinDistance == PersonalTreasureMapSearchPolicy.EXPEDITION_MIN_TARGET_DISTANCE &&
            recipe.mapSearchAdditionalWorlds.isNullOrEmpty()

    private fun FrozenPhysicalRecipe.searchPolicy(): PersonalTreasureMapSearchPolicy? {
        val server = mapSearchServer ?: return null
        val world = mapSearchWorld ?: return null
        return if (mapSearchRadius != null) {
            PersonalTreasureMapSearchPolicy(
                server,
                world,
                mapSearchRadius,
                minDistance = mapSearchMinDistance ?: PersonalTreasureMapSearchPolicy.LEGACY_MIN_TARGET_DISTANCE,
                additionalWorlds = mapSearchAdditionalWorlds.orEmpty().toSet(),
            )
        } else {
            PersonalTreasureMapSearchPolicy(
                server = server,
                world = world,
                bounds = PersonalTreasureMapBounds(
                    requireNotNull(mapSearchMinX), requireNotNull(mapSearchMaxX),
                    requireNotNull(mapSearchMinZ), requireNotNull(mapSearchMaxZ),
                ),
                minDistance = requireNotNull(mapSearchMinDistance),
                additionalWorlds = mapSearchAdditionalWorlds.orEmpty().toSet(),
            )
        }
    }

    private fun currentMapSource(mapId: String): RewardCatalogSource.PersonalMap? =
        entries.values.asSequence()
            .filter { it.id == mapId && it.source is RewardCatalogSource.PersonalMap }
            .singleOrNull()
            ?.source as? RewardCatalogSource.PersonalMap

    private fun currentMapPreview(mapId: String): ItemStack? = entries.values
        .filter { it.id == mapId && it.source is RewardCatalogSource.PersonalMap }
        .singleOrNull()
        ?.let(::preview)

    /** Redirects a legacy bearer through the current map recipe already captured at warmup. */
    private fun currentMapPrize(mapId: String): FrozenPhysicalRewardRecord? {
        val mapEntry = entries.values.singleOrNull {
            it.id == mapId && it.source is RewardCatalogSource.PersonalMap
        } ?: return null
        val prepared = preparedInteractive[key(mapEntry)] ?: return null
        val parent = frozen?.find(prepared.sourceKey)?.takeIf {
            it.fingerprint == prepared.providerFingerprint &&
                it.recipe.type == "personal-map" && it.recipe.mapId == mapId
        } ?: return null
        val recipe = parent.recipe
        val prizeKey = recipe.mapPrizeKey ?: return null
        return frozen.find(prizeKey)?.takeIf { it.fingerprint == recipe.mapPrizeFingerprint }
    }

    private fun frozenTreasureProvidersReady(node: FrozenTreasureNode): Boolean = when (node.type) {
        "item" -> !node.requiresItemsAdder || Bukkit.getPluginManager().isPluginEnabled("ItemsAdder")
        "enchant", "potion" -> true
        "money" -> activeWallet()?.walletForCurrency("vault")?.available == true
        "command" -> commandProviderReady(requireNotNull(node.commands).single())
        "sub-pool" -> node.children.orEmpty().filter { it.weight > 0 }.all(::frozenTreasureProvidersReady)
        "ae" -> Bukkit.getPluginManager().isPluginEnabled("AdvancedEnchantments")
        "slimefun" -> Bukkit.getPluginManager().isPluginEnabled("Slimefun")
            && HookRegistry.sfHook != null && decodeStack(requireNotNull(node.stack)) != null
        else -> false
    }

    private fun commandProviderReady(command: String): Boolean = when {
        tokenAmount(command) != null -> activeWallet()?.walletForCurrency("tokens")?.available == true
        command.startsWith("arcbuilder:") -> Bukkit.getPluginManager().isPluginEnabled("ArcBuilder")
        command.startsWith("arcecojobs:") -> Bukkit.getPluginManager().isPluginEnabled("ArcEcoJobs")
        command.startsWith("elitemobs:") -> Bukkit.getPluginManager().isPluginEnabled("EliteMobs")
        else -> false
    }

    private fun frozenRequiredSlots(recipe: FrozenPhysicalRecipe): Int? = when (recipe.type) {
        "money", "tokens", "particle-preset" -> 0
        "command" -> if (tokenAmount(requireNotNull(recipe.commandValue)) != null) 0 else 1
        "ae" -> 1
        "mount" -> 0
        "furniture" -> recipe.furnitureBoxes?.size
        "furniture-rolls" -> frozenTreasureRollRequiredSlots(
            requireNotNull(recipe.treasure),
            requireNotNull(recipe.furnitureMaxRolls),
        )
        "seal" -> null
        // Require room for the worst selectable result of every roll before drawing anything.
        "treasure" -> if (recipe.treasureRolls != null) frozenTreasureRollRequiredSlots(
            requireNotNull(recipe.treasure),
            recipe.treasureRolls,
        )
            else frozenTreasureRequiredSlots(requireNotNull(recipe.treasure), emptySet())
        "dungeon-case" -> 1
        else -> null
    }

    private fun frozenTreasureRequiredSlots(node: FrozenTreasureNode, visitedPools: Set<String>): Int? = when (node.type) {
        "money" -> 0
        "command" -> if (tokenAmount(requireNotNull(node.commands).single()) != null) 0 else 1
        "item" -> {
            val stack = decodeStack(requireNotNull(node.stack)) ?: return null
            val maxStack = stack.maxStackSize.coerceAtLeast(1)
            ((requireNotNull(node.maxInt).toLong() + maxStack - 1) / maxStack).toInt()
        }
        "slimefun" -> {
            val stack = decodeStack(requireNotNull(node.stack)) ?: return null
            (requireNotNull(node.maxInt) + stack.maxStackSize - 1) / stack.maxStackSize
        }
        "enchant", "potion" -> requireNotNull(node.maxInt)
        "ae" -> frozenAe(node)?.let { requiredSlots(it, visitedPools) }
        "sub-pool" -> {
            val poolId = requireNotNull(node.poolId)
            if (poolId in visitedPools) return null
            node.children.orEmpty().filter { it.weight > 0 }
                .map { frozenTreasureRequiredSlots(it, visitedPools + poolId) ?: return null }.maxOrNull()
        }
        else -> null
    }

    private fun frozenTreasureRollRequiredSlots(node: FrozenTreasureNode, rolls: Int): Int? {
        if (rolls !in 1..PLAYER_STORAGE_SLOTS) return null
        val slotsPerRoll = frozenTreasureRequiredSlots(node, emptySet()) ?: return null
        return minOf(
            PLAYER_STORAGE_SLOTS.toLong() + 1,
            slotsPerRoll.toLong() * rolls.toLong(),
        ).toInt()
    }

    private fun redeemFrozen(recipe: FrozenPhysicalRecipe, player: Player, operationId: UUID): CompletableFuture<PhysicalRewardOutcome> = when (recipe.type) {
        "mount" -> MountModule.grantReward(player, requireNotNull(recipe.mountId)).thenApply(::mountOutcome)
        "particle-preset" -> particlePresets.redeem(player, requireNotNull(recipe.particlePresetId))
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
        "treasure" -> if (recipe.treasureRolls == null) {
            redeemFrozenTreasure(player, requireNotNull(recipe.treasure), operationId, emptySet())
        } else {
            redeemFrozenItemRolls(player, requireNotNull(recipe.treasure), recipe.treasureRolls)
        }
        "furniture-rolls" -> redeemFrozenFurnitureRolls(player, recipe)
        "dungeon-case" -> DungeonCaseRewards.create(player, requireNotNull(recipe.dungeonCaseId))
            ?.let { giveStacks(player, listOf(it)) }
            ?: PhysicalRewardOutcome.Rejected(UNAVAILABLE)
        "travel-anchors" -> TravelAnchorsModule.createPersonalAnchorStacks(
            player.name,
            requireNotNull(recipe.travelAnchorAmount),
        )?.let { giveStacks(player, it) } ?: PhysicalRewardOutcome.Rejected(UNAVAILABLE)
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
        "ae" -> frozenAe(node)?.let { redeemTreasure(player, it, operationId, emptySet()) }
            ?: PhysicalRewardOutcome.Rejected(UNAVAILABLE)
        else -> PhysicalRewardOutcome.Rejected(UNAVAILABLE)
    }

    /** Draws the complete bounded item bundle before the single all-or-nothing inventory write. */
    private fun redeemFrozenItemRolls(
        player: Player,
        root: FrozenTreasureNode,
        rolls: Int,
    ): PhysicalRewardOutcome {
        if (rolls !in 1..PLAYER_STORAGE_SLOTS ||
            !frozenTreasureIsItemOnly(root, emptySet())
        ) return PhysicalRewardOutcome.Rejected(UNAVAILABLE)

        val quantities = mutableListOf<Pair<ItemStack, Long>>()
        repeat(rolls) {
            val (stack, amount) = rollFrozenItem(root, emptySet())
                ?: return PhysicalRewardOutcome.Rejected(UNAVAILABLE)
            val index = quantities.indexOfFirst { (existing, _) -> existing.isSimilar(stack) }
            if (index < 0) {
                quantities += stack to amount.toLong()
            } else {
                val (existing, previous) = quantities[index]
                quantities[index] = existing to (previous + amount)
            }
        }

        val values = mutableListOf<ItemStack>()
        quantities.forEach { (stack, total) ->
            val maxStack = stack.maxStackSize.coerceAtLeast(1)
            // No 36-slot player inventory can hold more than this amount of one stack identity.
            if (total > maxStack.toLong() * PLAYER_STORAGE_SLOTS) return PhysicalRewardOutcome.Rejected(UNAVAILABLE)
            var remaining = total
            while (remaining > 0) {
                val amount = minOf(maxStack.toLong(), remaining).toInt()
                values += stack.clone().also { it.amount = amount }
                remaining -= amount
            }
        }
        return giveStacks(player, values)
    }

    private fun redeemFrozenFurnitureRolls(
        player: Player,
        recipe: FrozenPhysicalRecipe,
    ): PhysicalRewardOutcome {
        val minRolls = recipe.furnitureMinRolls ?: return PhysicalRewardOutcome.Rejected(UNAVAILABLE)
        val maxRolls = recipe.furnitureMaxRolls ?: return PhysicalRewardOutcome.Rejected(UNAVAILABLE)
        if (minRolls !in RewardFurniturePackage.MIN_ROLLS..RewardFurniturePackage.MAX_ROLLS ||
            maxRolls !in minRolls..RewardFurniturePackage.MAX_ROLLS
        ) return PhysicalRewardOutcome.Rejected(UNAVAILABLE)
        return redeemFrozenItemRolls(player, requireNotNull(recipe.treasure), randomInt(minRolls, maxRolls))
    }

    private fun furnitureInventoryFull(requiredSlots: Int): String =
        "<red>Освободите $requiredSlots ячеек инвентаря и используйте набор повторно."

    private fun rollFrozenItem(
        node: FrozenTreasureNode,
        visitedPools: Set<String>,
    ): Pair<ItemStack, Int>? = when (node.type) {
        "item" -> {
            val stack = decodeStack(node.stack ?: return null) ?: return null
            stack to randomInt(requireNotNull(node.minInt), requireNotNull(node.maxInt))
        }
        "sub-pool" -> {
            val poolId = node.poolId ?: return null
            if (poolId in visitedPools || visitedPools.size >= MAX_POOL_DEPTH) return null
            val child = chooseFrozenChild(node.children.orEmpty()) ?: return null
            rollFrozenItem(child, visitedPools + poolId)
        }
        else -> null
    }

    private fun frozenAe(node: FrozenTreasureNode): Treasure.Ae? = runCatching {
            Treasure.Ae(
                kind = when (requireNotNull(node.aeKind)) {
                    "item" -> AeKind.ITEM
                    "random_book" -> AeKind.RANDOM_BOOK
                    else -> return@runCatching null
                },
                itemName = node.itemName,
                amount = requireNotNull(node.amount),
                group = node.aeGroup,
                maxLevel = node.aeMaxLevel,
                args = node.aeArgs.orEmpty().map { arg ->
                    when (arg.type) {
                        "random-tier" -> AeArg.RandomTier
                        "random-slot" -> AeArg.RandomSlot
                        "int" -> AeArg.IntRange(requireNotNull(arg.min), requireNotNull(arg.max))
                        else -> return@runCatching null
                    }
                },
            )
    }.getOrNull()

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
        val style = entry.icon ?: CatalogIconStyle(if (entry.source is RewardCatalogSource.PersonalMap) "FILLED_MAP" else "PAPER")
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
                        val pack = settings.packages.getValue(source.id)
                        listOf(
                            "<#e8dfd2>При открытии выдаёт <yellow>${pack.minRolls}–${pack.maxRolls} предметов мебели.",
                            "<#e8dfd2>Каждый выбор независим; повторы возможны.",
                            "<#e8dfd2>Для выдачи освободите <yellow>${pack.maxRolls} ячеек инвентаря.",
                        )
                    }
                    is RewardCatalogSource.Mount -> listOf("<light_purple>Контракт открывает маунта I уровня.")
                    is RewardCatalogSource.DungeonCase -> listOf("<#d6c2ff>При использовании создаёт один предмет EliteMobs вашего уровня.")
                    is RewardCatalogSource.TravelAnchors -> listOf("<#67f4dc>При использовании выдаёт ${source.amount} личных путевых якоря.")
                    is RewardCatalogSource.ParticlePreset -> listOf(
                        "<#d1beff>Включить образ: <#e9a6ff>/pp group load ${source.id}",
                        "<#d1beff>Выбрать или отключить: <#e9a6ff>/pp",
                    )
                    is RewardCatalogSource.Choice -> listOf("<#ffd166>При использовании выберите одну из трёх наград.")
                    is RewardCatalogSource.PersonalMap -> emptyList()
                    else -> emptyList()
                } + if (entry.source is RewardCatalogSource.PersonalMap && entry.description.isEmpty()) {
                    listOf("<#a6ffce>Активируйте карту в мире выживания.", "<#fff2df>Найдите отмеченное сокровище.")
                } else if (entry.source is RewardCatalogSource.PersonalMap) {
                    emptyList()
                } else {
                    listOf("<green>ПКМ с предметом в руке — получить награду.", "<yellow>Можно хранить и передавать до использования.")
                }).map { TextUtil.mm(it, true) })
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
        is Treasure.Ae -> when {
            AeNativeItems.supports(value) -> AeNativeItems.create(value)?.let { giveStacks(player, it) } ?: rejected()
            value.kind == AeKind.RANDOM_BOOK && (value.group != null || value.maxLevel != null) ->
                runCatching { treasureStackFactory.create(value, player) }
                    .getOrNull()?.let { giveStacks(player, it) } ?: rejected()
            else -> giveNativeCommand(player, AeLoot.buildCommand(player.name, value))
        }
        is Treasure.SubPool -> {
            if (value.poolId in visited || visited.size >= 8) rejected()
            else Treasures.getPool(value.poolId)?.random()?.let { redeemTreasure(player, it, operationId, visited + value.poolId) } ?: rejected()
        }
        is Treasure.Item -> giveStacks(player, split(value.stack, value.amount))
        is Treasure.Preset -> ItemPresets.resolveStacks(value.preset, value.amount).getOrNull()
            ?.let { giveStacks(player, it) } ?: rejected()
        is Treasure.Slimefun -> HookRegistry.sfHook?.getSlimefunItemStack(value.itemId)?.let { giveStacks(player, split(it, value.rolledAmount)) } ?: rejected()
        is Treasure.Enchant -> giveStacks(player, List(value.amount) { value.randomBook() })
        is Treasure.Potion -> giveStacks(player, List(value.amount) { Treasure.Potion.randomPotion() })
    }

    internal fun deposit(player: Player, currency: String, amount: Double, operationId: UUID): PhysicalRewardOutcome {
        val wallet = activeWallet()?.walletForCurrency(currency)?.takeIf { it.available } ?: return rejected()
        val minor = runCatching { BigDecimal.valueOf(amount).movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact() }
            .getOrNull()?.takeIf { it > 0 } ?: return rejected()
        val before = wallet.balanceMinor(player.uniqueId) ?: return rejected()
        val evidence = wallet.deposit(player.uniqueId, minor, "arc-reward:$operationId", before)
        return when {
            evidence.providerAccepted == true && evidence.balanceAfterMinor == Math.addExact(before, minor) -> {
                if (currency == "vault") {
                    // Use the confirmed, rounded wallet delta, never reroll the reward for its message.
                    runCatching {
                        TreasureMessage.chat(TreasureConfig.DefaultMessages.moneyReceived)
                            .send(MessageContext(player, amount = BigDecimal.valueOf(minor, 2)))
                    }.onFailure {
                        warn("Money reward receipt failed after payout: operation={} player={}", operationId, player.uniqueId, it)
                    }
                }
                PhysicalRewardOutcome.Applied
            }
            !evidence.providerCallAttempted || evidence.providerAccepted == false && evidence.balanceAfterMinor == before -> rejected()
            else -> uncertain()
        }
    }

    /** Only the finite native item issuers accepted below reach dispatch. Confirm an item delta, not a command boolean. */
    private fun giveNativeCommand(player: Player, command: String): PhysicalRewardOutcome {
        if (command == ELITE_LUCKY_TICKET_COMMAND) {
            val ticket = eliteLuckyTicket(player) ?: return rejected()
            return giveStacks(player, listOf(ticket))
        }
        val before = player.inventory.storageContents.map { it?.clone() }
        val succeeded = runCatching { Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command.replace("%player%", player.name)) }
        val after = player.inventory.storageContents.toList()
        val changed = after.sumOf { it?.amount ?: 0 } > before.sumOf { it?.amount ?: 0 }
        if (changed) return PhysicalRewardOutcome.Applied
        // ArcBuilder and ArcEcoJobs are synchronous item-only issuers; no inventory change proves refusal.
        if (command.startsWith("arcbuilder:builder systembook ") || command.startsWith("arcecojobs:arcjobs booster give ")) return rejected()
        return if (succeeded.getOrNull() == false) rejected() else uncertain()
    }

    /** Uses EliteMobs' own configured item so its consumable identity and all native PDC remain intact. */
    private fun eliteLuckyTicket(player: Player): ItemStack? {
        if (!Bukkit.getPluginManager().isPluginEnabled("EliteMobs")) return null
        val ticket = runCatching {
            CustomItem.getCustomItem(ELITE_LUCKY_TICKET_ID)
                ?.generateDefaultsItemStack(player, false, null)
                ?.clone()
        }.getOrNull() ?: return null
        if (ticket.type != Material.PAPER || !ItemConsumables.`is`(ticket, ItemConsumables.Type.LUCKY_TICKET)) return null
        ticket.setData(DataComponentTypes.ITEM_MODEL, Key.key("minecraft:totem_of_undying"))
        return ticket
    }

    private fun giveStacks(player: Player, values: List<ItemStack>): PhysicalRewardOutcome {
        val before = player.inventory.storageContents.map { it?.clone() }.toTypedArray()
        val simulation = Bukkit.createInventory(null, 36)
        simulation.contents = before.map { it?.clone() }.toTypedArray()
        if (values.any { simulation.addItem(it.clone()).isNotEmpty() }) return PhysicalRewardOutcome.Rejected("<red>В инвентаре не хватает места.")
        return try {
            val deliveries = values.map { it.clone() }.toTypedArray()
            if (player.inventory.addItem(*deliveries).isEmpty()) PhysicalRewardOutcome.Applied
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
        is Treasure.Ae -> if (AeNativeItems.supports(value)) AeNativeItems.create(value, preview = true)?.size
            else value.amount.coerceAtLeast(1)
        is Treasure.SubPool -> if (value.poolId in visited || visited.size >= 8) null else
            Treasures.getPool(value.poolId)?.treasures?.map { requiredSlots(it, visited + value.poolId) ?: return null }?.maxOrNull()
        is Treasure.Item -> (value.max + value.stack.maxStackSize - 1) / value.stack.maxStackSize
        is Treasure.Preset -> ItemPresets.resolveStacks(value.preset, value.amount).getOrNull()
            ?.sumOf { (it.amount + it.maxStackSize - 1) / it.maxStackSize }
            ?: return null
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
    private fun providersReady(entry: RewardCatalogEntry): Boolean =
        entry.requires.all { Bukkit.getPluginManager().isPluginEnabled(it) } &&
            (entry.source !is RewardCatalogSource.DungeonCase || Bukkit.getPluginManager().isPluginEnabled("EliteMobs")) &&
            (entry.source !is RewardCatalogSource.TravelAnchors || TravelAnchorsModule.isEnabled) &&
            (entry.source !is RewardCatalogSource.ParticlePreset || particlePresets.ready(entry.source.id))
    private fun split(stack: ItemStack, amount: Int): List<ItemStack> = (0 until amount step stack.maxStackSize).map { offset ->
        stack.clone().also { it.amount = minOf(stack.maxStackSize, amount - offset) }
    }

    private data class SealSnapshot(val items: List<ItemStack>, val definition: String)
    private fun rejected() = PhysicalRewardOutcome.Rejected(UNAVAILABLE)
    private fun uncertain() = PhysicalRewardOutcome.Uncertain("<gold>Результат требует проверки. Сохраните предмет и сообщите администрации.")
    private fun completed(outcome: PhysicalRewardOutcome) = CompletableFuture.completedFuture(outcome)

    companion object {
        private val INTERACTIVE_RECIPE_TYPES = setOf("choice", "personal-map")
        private const val UNAVAILABLE = "<red>Награда сейчас недоступна. Предмет сохранён."
        private const val MAX_GRAPH_NODES = 2_048
        private const val MAX_POOL_DEPTH = 8
        private const val PLAYER_STORAGE_SLOTS = 36
        private const val INVENTORY_FULL = "<red>В инвентаре не хватает места."
        private const val ELITE_LUCKY_TICKET_ID = "elite_lucky_ticket.yml"
        private const val ELITE_LUCKY_TICKET_COMMAND = "elitemobs:elitemobs loot give %player% elite_lucky_ticket.yml"
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
