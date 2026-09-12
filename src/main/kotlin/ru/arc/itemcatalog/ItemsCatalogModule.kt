package ru.arc.itemcatalog

import org.bukkit.entity.Player
import org.bukkit.Bukkit
import ru.arc.ARC
import ru.arc.core.PluginModule
import ru.arc.onetime.OneTimeUseLedger
import ru.arc.onetime.UnavailableOneTimeUseLedger
import ru.arc.sql.onetime.MySqlOneTimeUseLedger
import ru.arc.sql.onetime.MySqlOneTimeUsePartition
import ru.arc.paper.api.ArcItemMaterializationReference
import ru.arc.paper.api.ArcItemMaterializationRequest
import ru.arc.paper.api.ArcItemMaterializerCapabilitySnapshot
import ru.arc.util.Logging.info
import ru.arc.util.Logging.warn
import ru.arc.util.TextUtil
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

object ItemsCatalogModule : PluginModule {
    override val name = "ItemsCatalog"
    override val priority = 84

    @Volatile private var settings: ItemsCatalogSettings? = null
    @Volatile private var service: ItemsCatalogService? = null
    @Volatile private var controller: ItemsCatalogGuiController? = null
    @Volatile private var rewardController: RewardCatalogGuiController? = null
    @Volatile private var collectionSeals: CollectionSealController? = null
    @Volatile private var physicalRewards: PhysicalRewardController? = null
    private var rewardLedger: OneTimeUseLedger? = null

    override fun init() {
        start(
            ItemsCatalogModuleConfig.load(ARC.instance.dataPath).snapshot(),
            loadRewardCatalogOrDefault(),
        )
    }

    override fun reload() {
        val loaded = ItemsCatalogModuleConfig.load(ARC.instance.dataPath).snapshot()
        val rewards = loadRewardCatalogOrDefault()
        shutdownRuntime()
        start(loaded, rewards)
    }

    override fun shutdown() {
        shutdownRuntime()
        settings = null
    }

    fun isAvailable(): Boolean = controller != null || rewardController?.isAvailable() == true

    fun open(player: Player) {
        val activeController = controller
        if (activeController != null) {
            activeController.openRoot(player)
            return
        }
        rewardController?.takeIf(RewardCatalogGuiController::isAvailable)?.openRoot(player)
            ?: player.sendMessage(TextUtil.mm(settings?.unavailableMessage ?: "<red>Каталог предметов сейчас недоступен.", true))
    }

    fun openRewards(player: Player) {
        rewardController?.takeIf(RewardCatalogGuiController::isAvailable)?.openRoot(player)
            ?: player.sendMessage(TextUtil.mm(settings?.unavailableMessage ?: "<red>Каталог наград сейчас недоступен.", true))
    }

    internal fun currentSnapshot(): ItemsCatalogSnapshot? = service?.currentSnapshot()

    fun rewardHealthSnapshot(): Map<String, Any?> =
        (rewardController?.healthSnapshot() ?: mapOf("enabled" to false)) +
            ("physicalRedemptionAvailable" to (physicalRewards?.available == true))

    fun issueCaseReward(player: Player, categoryId: String, entryId: String): CaseRewardIssueResult =
        rewardController?.issueCaseReward(player, categoryId, entryId) ?: CaseRewardIssueResult.UNAVAILABLE

    internal fun itemMaterializerCapability(): ArcItemMaterializerCapabilitySnapshot =
        rewardController?.materializerCapability()
            ?: ArcItemMaterializerCapabilitySnapshot(available = false, catalogFingerprint = null)

    internal fun prepareItemMaterialization(request: ArcItemMaterializationRequest): ArcItemMaterializationReference? =
        rewardController?.prepareCaseReward(request.categoryId, request.entryId)

    internal fun materializeItem(reference: ArcItemMaterializationReference): List<org.bukkit.inventory.ItemStack>? =
        rewardController?.materializeCaseReward(reference)

    private fun loadRewardCatalogOrDefault(): RewardCatalogSettings =
        rewardCatalogOrDefault(
            load = { RewardCatalogModuleConfig.load(ARC.instance.dataPath).snapshot() },
            onFailure = { failure ->
                warn(
                    "Reward catalogue configuration is invalid; reward tab disabled while the item catalogue stays available: {}",
                    failure.message ?: failure.javaClass.simpleName,
                )
            },
        )

    private fun start(loaded: ItemsCatalogSettings, rewards: RewardCatalogSettings) {
        settings = loaded
        val frozenRewards = FrozenPhysicalRewards(ARC.instance.dataPath)
        lateinit var nativeRewards: CatalogPhysicalRewards
        val seals = CollectionSealController(ARC.instance, rewards) { categoryId -> nativeRewards.archivedSeal(categoryId) }
        nativeRewards = CatalogPhysicalRewards(rewards, frozen = frozenRewards, sealStack = seals::createStack)
        if (rewards.enabled) {
            seals.register()
            collectionSeals = seals
        }
        val storage = PhysicalRewardStorageConfig.load(ARC.instance.dataPath)
        val ledger = if (rewards.enabled && storage.enabled) runCatching {
            MySqlOneTimeUseLedger.open(
                storage.sql(),
                "ARC-catalog-rewards", "arc_catalog_rewards",
                listOf(MySqlOneTimeUseLedger.createTableMigration(1)),
                MySqlOneTimeUsePartition("arc.catalog-reward"),
            )
        }.getOrElse {
            warn("Physical reward storage unavailable: {}", it.javaClass.simpleName)
            UnavailableOneTimeUseLedger
        } else UnavailableOneTimeUseLedger
        rewardLedger = ledger
        val physical = PhysicalRewardController(
            ARC.instance, ledger, nativeRewards::resolve, nativeRewards::canRedeem,
            nativeRewards::redeem, ARC.serverName ?: "arc",
        )
        if (rewards.enabled) {
            physical.register()
            physicalRewards = physical
        }
        val createPhysical: (String) -> org.bukkit.inventory.ItemStack? = { key ->
            if (!nativeRewards.canMaterialize(key)) null
            else if (key.startsWith("set_frozen_")) nativeRewards.createSealStack(key)
            else physical.createStack(key)
        }
        rewardController = RewardCatalogGuiController(
            rewards, loaded.givePermission, seals::createStack,
            { entry ->
                if (entry.previewItemsAdder != null) nativeRewards.visualPreview(entry)
                else physical.previewStack(nativeRewards.key(entry))
            },
            { entry -> nativeRewards.materialization(entry)
                ?.let { createPhysical(it.sourceKey) } },
            nativeRewards::isVoucherSource,
            nativeRewards::materialization,
            createPhysical,
        ).takeIf { rewards.enabled }
        info(
            "Reward catalogue loaded: enabled={} categories={} entries={}",
            rewards.enabled,
            rewards.categories.size,
            rewards.entryCount,
        )
        if (!loaded.enabled) {
            info("Items catalog module disabled by configuration")
            return
        }
        val itemsAdder = Bukkit.getPluginManager().getPlugin("ItemsAdder")
        if (itemsAdder == null || !itemsAdder.isEnabled) {
            warn("Items catalog module disabled because ItemsAdder is unavailable")
            return
        }
        val gateway = BukkitItemsAdderCatalogGateway(itemsAdder)
        val activeService = ItemsCatalogService(ARC.instance, loaded, gateway)
        service = activeService
        controller = ItemsCatalogGuiController(loaded, activeService, rewardController)
        activeService.start()
        info("Items catalog module initialized and is waiting for the ItemsAdder index")
    }

    private fun shutdownRuntime() {
        controller?.shutdown()
        controller = null
        rewardController?.shutdown()
        rewardController = null
        val closingRewards = physicalRewards
        val closingLedger = rewardLedger
        physicalRewards = null
        rewardLedger = null
        val drained = closingRewards?.closeAndDrain() ?: CompletableFuture.completedFuture(null)
        drained.orTimeout(5, TimeUnit.SECONDS).whenCompleteAsync { _, failure ->
            if (failure != null) warn("Physical reward shutdown retained unfinished claims for recovery")
            closingLedger?.close()
        }
        collectionSeals?.close()
        collectionSeals = null
        service?.shutdown()
        service = null
    }
}

internal fun rewardCatalogOrDefault(
    load: () -> RewardCatalogSettings,
    onFailure: (Throwable) -> Unit = {},
): RewardCatalogSettings =
    runCatching(load).getOrElse { failure ->
        onFailure(failure)
        RewardCatalogSettings(
            enabled = false,
            title = "<gold><bold>Сокровищница наград",
            categories = emptyList(),
            messages = RewardCatalogMessages.DEFAULT,
        )
    }
