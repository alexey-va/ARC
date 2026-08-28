package ru.arc.hooks

import org.bukkit.Bukkit
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import ru.arc.ARC
import ru.arc.hooks.auraskills.AuraSkillsHook
import ru.arc.hooks.bank.BankHook
import ru.arc.hooks.betterstructures.BSListener
import ru.arc.hooks.citizens.CitizensHook
import ru.arc.hooks.elitemobs.EMHook
import ru.arc.hooks.elitemobs.EMListener
import ru.arc.hooks.economyshop.EconomyShopGuiPurchaseService
import ru.arc.hooks.economyshop.EconomyShopGuiAuditListener
import ru.arc.hooks.economyshop.ShopPurchaseService
import ru.arc.hooks.lands.LandsHook
import ru.arc.hooks.lootchest.LootChestHook
import ru.arc.hooks.luckperms.LuckPermsHook
import ru.arc.hooks.packetevents.PacketEventsHook
import ru.arc.hooks.slimefun.BackpackBlockListener
import ru.arc.hooks.slimefun.SFHook
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
import ru.arc.contracts.SeasonTrophyProtectionListener
import ru.arc.util.Logging.debug
import ru.arc.util.Logging.error
import ru.arc.util.Logging.info

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
    var emListener: EMListener? = null
    var seasonTrophyProtectionListener: SeasonTrophyProtectionListener? = null

    private val registeredHooks = HashSet<String>()
    private val registeredListeners = LinkedHashSet<Listener>()

    internal var isClosed: Boolean = false
        private set

    companion object {
        @JvmField var landsHook: LandsHook? = null

        @JvmField var huskHomesHook: HuskHomesHook? = null

        @JvmField var papiHook: PAPIHook? = null

        @JvmField var cmiHook: CMIHook? = null

        @JvmField var itemsAdderHook: ItemsAdderHook? = null

        @JvmField var citizensHook: CitizensHook? = null

        @JvmField var viaVersionHook: ViaVersionHook? = null

        @JvmField var wgHook: WGHook? = null

        @JvmField var sfHook: SFHook? = null

        @JvmField var emHook: EMHook? = null

        @JvmField var yamipaHook: YamipaHook? = null

        @JvmField var luckPermsHook: LuckPermsHook? = null

        @JvmField var lootChestHook: LootChestHook? = null

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

        private fun clearGlobalHooks() {
            landsHook = null
            huskHomesHook = null
            papiHook = null
            cmiHook = null
            itemsAdderHook = null
            citizensHook = null
            viaVersionHook = null
            wgHook = null
            sfHook = null
            emHook = null
            yamipaHook = null
            luckPermsHook = null
            lootChestHook = null
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
        }
    }

    fun setupHooks() {
        check(!isClosed) { "HookRegistry is closed" }
        registerVanillaEvents()
        registerHooks()
    }

    @Deprecated("Use close()", ReplaceWith("close()"))
    fun cancelTasks() {
        close()
    }

    override fun close() {
        if (isClosed) return
        isClosed = true

        val failures = mutableListOf<Throwable>()
        cleanup(failures) { emHook?.close() }
        cleanup(failures) { auctionHook?.close() }
        cleanup(failures) { citizensHook?.close() }
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
        clearInstanceListeners()
        clearGlobalHooks()

        if (failures.isNotEmpty()) {
            val first = failures.first()
            failures.drop(1).forEach(first::addSuppressed)
            throw first
        }
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
        emListener = null
        seasonTrophyProtectionListener = null
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

    private fun registerHooks() {
        register("PlaceholderAPI", true) {
            val hook = PAPIHook()
            check(hook.register()) { "PlaceholderAPI rejected ARC expansion registration" }
            papiHook = hook
        }
        register("WorldGuard", true) {
            wgHook = registerListener(WGHook())
        }
        register("Slimefun", true) {
            val hook = registerListener(SFHook())
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
        register("EliteMobs", false) {
            val existingHook = emHook
            val hook = existingHook ?: EMHook()
            try {
                hook.reload()
                val listener = emListener ?: registerListener(EMListener())
                emHook = hook
                emListener = listener
            } catch (failure: Throwable) {
                if (existingHook == null) hook.close()
                throw failure
            }
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
        register("Bank", true) { bankHook = BankHook() }
        register("Parties", true) { partiesHook = PartiesHook() }
        register("RedisEconomy", true) {
            val hook = RedisEcoHook()
            registerListener(RedisEcoListener())
            redisEcoHook = hook
        }
        if (translatorHook == null) translatorHook = TranslatorHook()
        register("LuckPerms", true) { luckPermsHook = LuckPermsHook() }
        register("AuraSkills", true) { auraSkillsHook = AuraSkillsHook() }
        register("CMI", true) {
            val hook = CMIHook()
            registerListener(CMIListener())
            cmiHook = hook
        }
        register("ViaVersion", true) { viaVersionHook = ViaVersionHook() }
        register("packetevents", true) { packetEventsHook = PacketEventsHook() }
        register("PlayerWarps", true) { playerWarpsHook = PlayerWarpsHook() }
        register("LootChest", true) { lootChestHook = LootChestHook() }
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
            registerListener(EconomyShopGuiAuditListener())
            shopPurchaseService = purchaseService
        }
    }

    private fun registerVanillaEvents() {
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
            joinListener = registerListener(JoinListener())
        }
        if (blockListener == null) {
            blockListener = registerListener(BlockListener())
        }
        if (pickupListener == null) {
            pickupListener = registerListener(PickupListener())
        }
        if (commandListener == null) {
            commandListener = registerListener(CommandListener())
        }
        if (seasonTrophyProtectionListener == null) {
            seasonTrophyProtectionListener = registerListener(SeasonTrophyProtectionListener())
        }
    }
}
