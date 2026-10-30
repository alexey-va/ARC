package ru.arc.hooks

import org.bukkit.Bukkit
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import ru.arc.ARC
import ru.arc.config.ArcRedisConfig
import ru.arc.config.ArcRuntimeProfile
import ru.arc.chat.ChatModeConfig
import ru.arc.chat.ItemsAdderChatGuard
import ru.arc.chat.ChatGlyphProtection
import ru.arc.hooks.auraskills.AuraSkillsHook
import ru.arc.hooks.bank.BankHook
import ru.arc.hooks.betterstructures.BSListener
import ru.arc.hooks.citizens.CitizensHook
import ru.arc.hooks.economyshop.EconomyShopGuiPurchaseService
import ru.arc.hooks.economyshop.EconomyShopGuiAuditListener
import ru.arc.hooks.economyshop.FurnitureGalleryInteractionRuntime
import ru.arc.hooks.economyshop.FurnitureGalleryPurchaseListener
import ru.arc.hooks.economyshop.ShopPurchaseService
import ru.arc.hooks.economyshop.loadFurnitureGalleryTargets
import ru.arc.hooks.lands.LandsHook
import ru.arc.hooks.luckperms.LuckPermsHook
import ru.arc.hooks.packetevents.PacketEventsHook
import ru.arc.hooks.slimefun.BackpackBlockListener
import ru.arc.hooks.slimefun.SFHook
import ru.arc.hooks.slimefun.SlimefunTradeListener
import ru.arc.hooks.viaversion.ViaVersionHook
import ru.arc.hooks.worldguard.WGHook
import ru.arc.hooks.yamipa.YamipaHook
import ru.arc.hooks.zauction.AuctionHook
import ru.arc.hooks.zauction.AuctionTrophyGuardListener
import ru.arc.hooks.zauction.AuctionSaleNotifier
import ru.arc.hooks.ztranslator.TranslatorHook
import ru.arc.jobs.JobsModule
import ru.arc.listeners.BlockListener
import ru.arc.listeners.CMIListener
import ru.arc.listeners.ChatListener
import ru.arc.listeners.CommandListener
import ru.arc.listeners.JoinListener
import ru.arc.listeners.PickupListener
import ru.arc.listeners.RespawnListener
import ru.arc.listeners.SpawnerListener
import ru.arc.misc.BuilderSurvivalSyncListener
import ru.arc.paper.api.ArcSidebarPriorities
import ru.arc.util.Logging.debug
import ru.arc.util.Logging.error
import ru.arc.util.Logging.info
import java.util.concurrent.CompletableFuture

class HookRegistry(
    private val stopJobs: () -> Unit = JobsModule::shutdown,
) : AutoCloseable {
    var chatListener: ChatListener? = null
    var commandListener: CommandListener? = null
    var spawnerListener: SpawnerListener? = null
    var blockListener: BlockListener? = null
    var joinListener: JoinListener? = null
    var pickupListener: PickupListener? = null
    var betterRTPListener: BetterRTPListener? = null
    var leafRTPListener: LeafRTPListener? = null
    var respawnListener: RespawnListener? = null
    var bsListener: BSListener? = null

    private var chatGlyphProtection: ChatGlyphProtection? = null

    private val registeredHooks = HashSet<String>()
    private val registeredListeners = LinkedHashSet<Listener>()
    private var furnitureGalleryPurchaseListener: FurnitureGalleryPurchaseListener? = null

    internal var isClosed: Boolean = false
        private set
    private var closeCompletion: CompletableFuture<Void>? = null
    private var immediateCloseFailure: Throwable? = null

    companion object {
        @JvmField var landsHook: LandsHook? = null

        @JvmField var huskHomesHook: HuskHomesHook? = null

        @JvmField var papiHook: PAPIHook? = null

        @JvmField var cmiHook: CMIHook? = null

        @JvmField var itemsAdderHook: ItemsAdderHook? = null

        internal var chatGlyphGuard: ItemsAdderChatGuard? = null

        @JvmField var citizensHook: CitizensHook? = null

        @JvmField var viaVersionHook: ViaVersionHook? = null

        @JvmField var wgHook: WGHook? = null

        @JvmField var sfHook: SFHook? = null


        @JvmField var yamipaHook: YamipaHook? = null

        @JvmField var luckPermsHook: LuckPermsHook? = null


        @JvmField internal var auctionHook: AuctionHook? = null

        @JvmField var translatorHook: TranslatorHook? = null

        @JvmField var jobsEnabled: Boolean = false

        @JvmField var bankHook: BankHook? = null

        @JvmField var redisEcoHook: RedisEcoHook? = null

        @JvmField var auraSkillsHook: AuraSkillsHook? = null

        @JvmField var playerWarpsHook: PlayerWarpsHook? = null

        @JvmField var packetEventsHook: PacketEventsHook? = null

        @JvmField var aeHook: AEHook? = null

        @JvmField var myWorldsHook: MyWorldsHook? = null

        @JvmField var partiesHook: PartiesHook? = null

        internal var shopPurchaseService: ShopPurchaseService? = null

        @JvmField internal var furnitureGalleryInteractionRuntime: FurnitureGalleryInteractionRuntime? = null

        private fun clearGlobalHooks() {
            landsHook = null
            huskHomesHook = null
            papiHook = null
            cmiHook = null
            itemsAdderHook = null
            chatGlyphGuard = null
            citizensHook = null
            viaVersionHook = null
            wgHook = null
            sfHook = null
            yamipaHook = null
            luckPermsHook = null
            auctionHook = null
            translatorHook = null
            jobsEnabled = false
            bankHook = null
            redisEcoHook = null
            auraSkillsHook = null
            playerWarpsHook = null
            packetEventsHook = null
            aeHook = null
            myWorldsHook = null
            partiesHook = null
            shopPurchaseService = null
            furnitureGalleryInteractionRuntime = null
        }
    }

    fun setupHooks() {
        check(!isClosed) { "HookRegistry is closed" }
        papiHook?.clearPlaceholderCache()
        if (ARC.instance.runtimeProfile == ArcRuntimeProfile.SLIMEFUN) {
            registerInterfaceHooks()
            register("Slimefun", true) { registerListener(SlimefunTradeListener()) }
            return
        }
        registerVanillaEvents()
        registerHooks()
    }

    /** Reloads the environment-owned target map without rebuilding other hooks. */
    internal fun refreshFurnitureGalleryTargets() {
        check(!isClosed) { "HookRegistry is closed" }
        val purchases = shopPurchaseService ?: return
        val profiles = loadFurnitureGalleryTargets(ARC.instance.dataPath).orEmpty()
        val nextRuntime = FurnitureGalleryInteractionRuntime(ARC.instance, profiles)
        val nextListener = FurnitureGalleryPurchaseListener(purchases, nextRuntime)

        furnitureGalleryInteractionRuntime?.close()
        furnitureGalleryInteractionRuntime?.let { old ->
            HandlerList.unregisterAll(old)
            registeredListeners.remove(old)
        }
        furnitureGalleryPurchaseListener?.let { old ->
            HandlerList.unregisterAll(old)
            registeredListeners.remove(old)
        }
        try {
            registerListener(nextRuntime)
            registerListener(nextListener)
            furnitureGalleryInteractionRuntime = nextRuntime
            furnitureGalleryPurchaseListener = nextListener
            nextRuntime.start()
        } catch (failure: Throwable) {
            nextRuntime.close()
            furnitureGalleryInteractionRuntime = null
            furnitureGalleryPurchaseListener = null
            throw failure
        }
    }

    @Deprecated("Use close()", ReplaceWith("close()"))
    fun cancelTasks() {
        close()
    }

    fun closeAsync(): CompletableFuture<Void> {
        closeCompletion?.let { return it.copy() }
        val result = CompletableFuture<Void>()
        closeCompletion = result
        isClosed = true

        val failures = mutableListOf<Throwable>()
        val oldPackets = packetEventsHook
        var packetDrain = CompletableFuture.completedFuture<Void>(null)
        cleanup(failures) { auctionHook?.close() }
        cleanup(failures) { huskHomesHook?.close() }
        cleanup(failures) { furnitureGalleryInteractionRuntime?.close() }
        cleanup(failures) { citizensHook?.close() }
        cleanup(failures) { packetDrain = oldPackets?.closeAsync() ?: packetDrain }
        cleanup(failures) { chatGlyphProtection?.close() }
        chatGlyphProtection = null
        cleanup(failures) { joinListener?.close() }
        cleanup(failures) { papiHook?.clearPlaceholderCache() }
        cleanup(failures) { papiHook?.unregister() }
        if (jobsEnabled) {
            cleanup(failures, stopJobs)
        }
        jobsEnabled = false

        registeredListeners.forEach { listener ->
            cleanup(failures) { HandlerList.unregisterAll(listener) }
        }
        registeredListeners.clear()
        registeredHooks.clear()
        furnitureGalleryPurchaseListener = null
        clearInstanceListeners()
        clearGlobalHooks()

        if (failures.isNotEmpty()) {
            val first = failures.first()
            failures.drop(1).forEach(first::addSuppressed)
            immediateCloseFailure = first
        }
        packetDrain.whenComplete { _, packetFailure ->
            val failure = immediateCloseFailure ?: packetFailure
            if (failure != null && packetFailure != null && failure !== packetFailure) failure.addSuppressed(packetFailure)
            if (failure == null) result.complete(null) else result.completeExceptionally(failure)
        }
        return result.copy()
    }

    /** Dispatch-only for packet retirement; retains the previous synchronous hook-error behavior. */
    override fun close() {
        val first = !isClosed
        closeAsync()
        if (first) immediateCloseFailure?.let { throw it }
    }

    private fun cleanup(
        failures: MutableList<Throwable>,
        action: () -> Unit,
    ) {
        try {
            action()
        } catch (failure: Throwable) {
            failures += failure
        }
    }

    private fun clearInstanceListeners() {
        chatListener = null
        commandListener = null
        spawnerListener = null
        blockListener = null
        joinListener = null
        pickupListener = null
        betterRTPListener = null
        leafRTPListener = null
        respawnListener = null
        bsListener = null
    }

    private fun register(
        pluginName: String,
        single: Boolean,
        action: () -> Unit,
    ) {
        if (pluginName in registeredHooks) {
            info("Plugin {} already registered", pluginName)
            return
        }
        if (Bukkit.getServer().pluginManager.getPlugin(pluginName) == null) {
            debug("Plugin {} not installed — hook skipped", pluginName)
            return
        }

        info("Registering {} hook", pluginName)
        val listenersBefore = registeredListeners.toSet()
        try {
            action()
            if (single) registeredHooks += pluginName
        } catch (failure: Throwable) {
            val partiallyRegistered = registeredListeners - listenersBefore
            partiallyRegistered.forEach { listener ->
                runCatching { HandlerList.unregisterAll(listener) }
            }
            registeredListeners.removeAll(partiallyRegistered.toSet())
            error("Error registering {} hook", pluginName, failure)
            debug("Hook {} registration failed: {}", pluginName, failure.message)
        }
    }

    private fun registerFirstAvailable(
        single: Boolean,
        action: () -> Unit,
        vararg pluginNames: String,
    ) {
        for (pluginName in pluginNames) {
            if (Bukkit.getServer().pluginManager.getPlugin(pluginName) != null) {
                register(pluginName, single, action)
                return
            }
        }
        info("Unable to find plugin '{}'", pluginNames.joinToString("' or '"))
    }

    private fun <T : Listener> registerListener(listener: T): T {
        Bukkit.getPluginManager().registerEvents(listener, ARC.instance)
        registeredListeners += listener
        return listener
    }

    private fun registerInterfaceHooks() {
        register("PlaceholderAPI", true) {
            val hook = PAPIHook()
            check(hook.register()) { "PlaceholderAPI rejected ARC expansion registration" }
            papiHook = hook
        }
        register("LuckPerms", true) { luckPermsHook = LuckPermsHook() }
        register("AuraSkills", true) { auraSkillsHook = AuraSkillsHook() }
    }

    private fun registerHooks() {
        registerInterfaceHooks()
        register("WorldGuard", true) {
            wgHook = registerListener(WGHook())
        }
        register("Slimefun", true) {
            val hook = registerListener(SFHook())
            registerListener(SlimefunTradeListener())
            registerListener(BackpackBlockListener)
            sfHook = hook
            runCatching { hook.registerOptionalDenizenTags() }
                .onSuccess { tags ->
                    if (tags.isNotEmpty()) {
                        info("Registered optional Denizen Slimefun tags: {}", tags.joinToString())
                    }
                }
                .onFailure { failure ->
                    error("Unable to register optional Denizen Slimefun tags", failure)
                }
        }
        register("AdvancedEnchantments", true) {
            aeHook = registerListener(AEHook())
        }
        register("HuskHomes", true) {
            huskHomesHook = registerListener(HuskHomesHook())
        }
        register("Lands", true) { landsHook = LandsHook() }
        register("Jobs", false) {
            if (!jobsEnabled) jobsEnabled = JobsModule.init()
        }
        registerFirstAvailable(
            true,
            {
                val hook = AuctionHook()
                try {
                    hook.start()
                    registerListener(AuctionTrophyGuardListener())
                    registerListener(AuctionSaleNotifier(hook))
                    auctionHook = hook
                } catch (failure: Throwable) {
                    hook.close()
                    throw failure
                }
            },
            "zAuctionHouse",
            "zAuctionHouseV3",
        )
        register("Bank", true) { bankHook = registerListener(BankHook()) }
        register("Parties", true) { partiesHook = PartiesHook() }
        register("RedisEconomy", true) {
            val hook = RedisEcoHook()
            registerListener(RedisEcoListener())
            redisEcoHook = hook
        }
        if (translatorHook == null) translatorHook = TranslatorHook()
        register("CMI", true) {
            val hook = CMIHook()
            registerListener(CMIListener())
            cmiHook = hook
        }
        register("ViaVersion", true) { viaVersionHook = ViaVersionHook() }
        register("packetevents", true) { packetEventsHook = PacketEventsHook() }
        register("PlayerWarps", true) { playerWarpsHook = PlayerWarpsHook() }
        register("YamipaPlugin", true) { yamipaHook = YamipaHook() }
        register("ItemsAdder", true) {
            val itemsAdder = checkNotNull(Bukkit.getPluginManager().getPlugin("ItemsAdder"))
            val resourcePackZip = itemsAdder.dataFolder.toPath().resolve("output/generated.zip")
            val syncScript = BundledResourcePackSyncScript.install(ARC.instance.dataFolder.toPath())
            val config = ResourcePackSyncConfig.load(ARC.instance.dataFolder.toPath())
            itemsAdderHook = registerListener(ItemsAdderHook(resourcePackZip, syncScript, config))
        }
        register("Citizens", true) {
            citizensHook = CitizensHook()
        }
        register("BetterRTP", true) {
            betterRTPListener = registerListener(BetterRTPListener())
        }
        register("RTP", true) {
            leafRTPListener = registerListener(LeafRTPListener())
        }
        register("My_Worlds", true) {
            val plugin = checkNotNull(Bukkit.getPluginManager().getPlugin("My_Worlds"))
            myWorldsHook = MyWorldsHook(plugin)
        }
        register("BetterStructures", true) {
            bsListener = registerListener(BSListener())
        }
        register("EconomyShopGUI-Premium", true) {
            val translator = checkNotNull(translatorHook) { "Material translator is not initialized" }
            val purchaseService = EconomyShopGuiPurchaseService { item -> translator.translate(item) }
            val galleryRuntime = FurnitureGalleryInteractionRuntime(
                ARC.instance,
                loadFurnitureGalleryTargets(ARC.instance.dataPath).orEmpty(),
            )
            val galleryListener = FurnitureGalleryPurchaseListener(purchaseService, galleryRuntime)
            try {
                registerListener(purchaseService)
                registerListener(galleryRuntime)
                registerListener(galleryListener)
                registerListener(EconomyShopGuiAuditListener())
                furnitureGalleryInteractionRuntime = galleryRuntime
                furnitureGalleryPurchaseListener = galleryListener
                galleryRuntime.start()
                shopPurchaseService = purchaseService
            } catch (failure: Throwable) {
                galleryRuntime.close()
                furnitureGalleryInteractionRuntime = null
                furnitureGalleryPurchaseListener = null
                throw failure
            }
        }
    }

    private fun registerVanillaEvents() {
        if (chatGlyphProtection == null) {
            val protection = ChatGlyphProtection(
                ARC.instance, ArcRedisConfig.get().serverName, ARC.redisManager,
                ChatModeConfig.load(ARC.instance.dataPath),
            )
            chatGlyphProtection = protection
            chatGlyphGuard = registerListener(protection.guard)
            protection.start()
        }
        if (chatListener == null) {
            chatListener = registerListener(ChatListener())
        }
        if (respawnListener == null) {
            respawnListener = registerListener(RespawnListener())
        }
        if (spawnerListener == null) {
            spawnerListener = registerListener(SpawnerListener())
        }
        if (joinListener == null) {
            val listener = JoinListener()
            joinListener = registerListener(listener)
            if (ARC.serverName == "survival" && Bukkit.getPluginManager().isPluginEnabled("HuskSync")) {
                registerListener(BuilderSurvivalSyncListener(listener::checkBuilderSurvivalLater))
            }
        }
        if (blockListener == null) {
            blockListener = registerListener(BlockListener())
            registerListener(ru.arc.listeners.CreativeRewardItemGuard())
        }
        if (pickupListener == null) {
            pickupListener = registerListener(PickupListener())
        }
        if (commandListener == null) {
            commandListener = registerListener(CommandListener())
        }
    }
}
