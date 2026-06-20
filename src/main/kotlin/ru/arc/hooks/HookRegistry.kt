package ru.arc.hooks

import org.bukkit.Bukkit
import ru.arc.ARC
import ru.arc.hooks.auraskills.AuraSkillsHook
import ru.arc.hooks.bank.BankHook
import ru.arc.hooks.betterstructures.BSListener
import ru.arc.hooks.citizens.CitizensHook
import ru.arc.hooks.elitemobs.EMHook
import ru.arc.hooks.elitemobs.EMListener
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
import ru.arc.hooks.ztranslator.TranslatorHook
import ru.arc.jobs.JobsModule
import ru.arc.listeners.BlockListener
import ru.arc.listeners.CMIListener
import ru.arc.listeners.ChatListener
import ru.arc.listeners.CommandListener
import ru.arc.listeners.IAEvents
import ru.arc.listeners.JoinListener
import ru.arc.listeners.PickupListener
import ru.arc.listeners.RespawnListener
import ru.arc.listeners.SpawnerListener
import ru.arc.util.Logging.debug
import ru.arc.util.Logging.error
import ru.arc.util.Logging.info

class HookRegistry {

    var chatListener: ChatListener? = null
    var commandListener: CommandListener? = null
    var spawnerListener: SpawnerListener? = null
    var blockListener: BlockListener? = null
    var joinListener: JoinListener? = null
    var pickupListener: PickupListener? = null
    var iaEvents: IAEvents? = null
    var betterRTPListener: BetterRTPListener? = null
    var respawnListener: RespawnListener? = null
    var bsListener: BSListener? = null
    var shopListener: ShopListener? = null
    var emListener: EMListener? = null

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
        @JvmField var auctionHook: AuctionHook? = null
        @JvmField var translatorHook: TranslatorHook? = null
        @JvmField var jobsEnabled: Boolean = false
        @JvmField var bankHook: BankHook? = null
        @JvmField var redisEcoHook: RedisEcoHook? = null
        @JvmField var auraSkillsHook: AuraSkillsHook? = null
        @JvmField var playerWarpsHook: PlayerWarpsHook? = null
        @JvmField var packetEventsHook: PacketEventsHook? = null
        @JvmField var aeHook: AEHook? = null

        private val registeredHooks = HashSet<String>()

        private fun register(pluginName: String, single: Boolean, runnable: Runnable) {
            if (registeredHooks.contains(pluginName)) {
                info("Plugin {} already registered", pluginName)
                return
            }
            if (Bukkit.getServer().pluginManager.getPlugin(pluginName) != null) {
                info("Registering {} hook", pluginName)
                try {
                    runnable.run()
                    if (single) registeredHooks.add(pluginName)
                } catch (e: Throwable) {
                    error("Error registering {} hook", pluginName, e)
                    debug("Hook {} registration failed: {}", pluginName, e.message)
                }
            } else {
                debug("Plugin {} not installed — hook skipped", pluginName)
            }
        }

        private fun registerFirstAvailable(single: Boolean, runnable: Runnable, vararg pluginNames: String) {
            for (pluginName in pluginNames) {
                if (Bukkit.getServer().pluginManager.getPlugin(pluginName) != null) {
                    register(pluginName, single, runnable)
                    return
                }
            }
            info("Unable to find plugin '{}'", pluginNames.joinToString("' or '"))
        }
    }

    fun setupHooks() {
        registerVanillaEvents()
        registerHooks()
    }

    fun cancelTasks() {
        emHook?.cancel()
    }

    private fun registerHooks() {
        register("PlaceholderAPI", true) {
            papiHook = PAPIHook()
            papiHook!!.register()
        }
        register("WorldGuard", true) {
            wgHook = WGHook()
            Bukkit.getPluginManager().registerEvents(wgHook!!, ARC.instance)
        }
        register("Slimefun", true) {
            sfHook = SFHook()
            Bukkit.getPluginManager().registerEvents(sfHook!!, ARC.instance)
            Bukkit.getPluginManager().registerEvents(BackpackBlockListener, ARC.instance)
        }
        register("AdvancedEnchantments", true) {
            aeHook = AEHook()
            Bukkit.getPluginManager().registerEvents(aeHook!!, ARC.instance)
        }
        register("EliteMobs", false) {
            if (emHook == null) emHook = EMHook()
            emHook!!.reload()
            if (emListener == null) {
                emListener = EMListener()
                Bukkit.getPluginManager().registerEvents(emListener!!, ARC.instance)
            }
        }
        register("HuskHomes", true) {
            huskHomesHook = HuskHomesHook()
            Bukkit.getPluginManager().registerEvents(huskHomesHook!!, ARC.instance)
        }
        register("Lands", true) { landsHook = LandsHook() }
        register("Jobs", true) {
            JobsModule.init()
            jobsEnabled = true
        }
        registerFirstAvailable(true, Runnable { auctionHook = AuctionHook() }, "zAuctionHouse", "zAuctionHouseV3")
        register("Bank", true) { bankHook = BankHook() }
        register("RedisEconomy", true) {
            redisEcoHook = RedisEcoHook()
            val redisEcoListener = RedisEcoListener()
            Bukkit.getPluginManager().registerEvents(redisEcoListener, ARC.instance)
        }
        translatorHook = TranslatorHook()
        register("LuckPerms", true) { luckPermsHook = LuckPermsHook() }
        register("AuraSkills", true) { auraSkillsHook = AuraSkillsHook() }
        register("CMI", true) {
            cmiHook = CMIHook()
            val cmiListener = CMIListener()
            Bukkit.getPluginManager().registerEvents(cmiListener, ARC.instance)
        }
        register("ViaVersion", true) { viaVersionHook = ViaVersionHook() }
        register("packetevents", true) { packetEventsHook = PacketEventsHook() }
        register("PlayerWarps", true) { playerWarpsHook = PlayerWarpsHook() }
        register("LootChest", true) { lootChestHook = LootChestHook() }
        register("YamipaPlugin", true) { yamipaHook = YamipaHook() }
        register("ItemsAdder", true) {
            itemsAdderHook = ItemsAdderHook()
            iaEvents = IAEvents()
            Bukkit.getPluginManager().registerEvents(iaEvents!!, ARC.instance)
        }
        register("Citizens", true) {
            citizensHook = CitizensHook()
            citizensHook!!.registerListeners()
        }
        register("BetterRTP", true) {
            betterRTPListener = BetterRTPListener()
            Bukkit.getPluginManager().registerEvents(betterRTPListener!!, ARC.instance)
        }
        register("BetterStructures", true) {
            bsListener = BSListener()
            Bukkit.getPluginManager().registerEvents(bsListener!!, ARC.instance)
        }
        register("EconomyShopGUI-Premium", true) {
            shopListener = ShopListener()
            Bukkit.getPluginManager().registerEvents(shopListener!!, ARC.instance)
        }
    }

    private fun registerVanillaEvents() {
        if (chatListener == null) {
            chatListener = ChatListener()
            Bukkit.getPluginManager().registerEvents(chatListener!!, ARC.instance)
        }
        if (respawnListener == null) {
            respawnListener = RespawnListener()
            Bukkit.getPluginManager().registerEvents(respawnListener!!, ARC.instance)
        }
        if (spawnerListener == null) {
            spawnerListener = SpawnerListener()
            Bukkit.getPluginManager().registerEvents(spawnerListener!!, ARC.instance)
        }
        if (joinListener == null) {
            joinListener = JoinListener()
            Bukkit.getPluginManager().registerEvents(joinListener!!, ARC.instance)
        }
        if (blockListener == null) {
            blockListener = BlockListener()
            Bukkit.getPluginManager().registerEvents(blockListener!!, ARC.instance)
        }
        if (pickupListener == null) {
            pickupListener = PickupListener()
            Bukkit.getPluginManager().registerEvents(pickupListener!!, ARC.instance)
        }
        if (commandListener == null) {
            commandListener = CommandListener()
            Bukkit.getPluginManager().registerEvents(commandListener!!, ARC.instance)
        }
    }
}
