package ru.arc.invest

import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.configuration.serialization.ConfigurationSerializable
import org.bukkit.inventory.ItemStack
import ru.arc.ARC
import ru.arc.hooks.HookRegistry
import ru.arc.invest.goods.Inventory
import java.io.File
import java.io.IOException

class Business(private val id: String) : ConfigurationSerializable {

    var displayName: String? = null
    var balance: Double = 0.0
    var shares: Long = 0
    var investors: Map<InvestPlayer, Int>? = null
    var location: BusinessLocation? = null
    var production: Production? = null
    var inventory: Inventory? = null
    var core: BusinessCore? = null
    var level: Int = 1

    private lateinit var invFile: File
    private lateinit var file: File
    private lateinit var coreFile: File

    fun load() {
        val base = ARC.instance.dataFolder.toString() + File.separator + "investing" + File.separator
        invFile = File("${base}inventories${File.separator}$id.yml")
        file = File("${base}businesses${File.separator}$id.yml")
        coreFile = File("${base}cores${File.separator}$id.yml")
        loadInventory()
        loadProduction()
        loadRegion()
        loadCore()
    }

    fun loadRegion() {
        if (HookRegistry.wgHook == null) {
            println("WorldGuard is not loaded. Businesses won't have location!")
            return
        }
        val configuration = YamlConfiguration.loadConfiguration(file)
        val world = configuration.getString("world")
        val region = configuration.getString("region")
        location = BusinessLocation(world ?: "", region ?: "")
    }

    fun loadCore() {
        ensureFileExists(coreFile)
    }

    fun loadInventory() {
        ensureFileExists(invFile)
        val configuration = YamlConfiguration.loadConfiguration(invFile)
        this.inventory = Inventory.deserialize(configuration.getList("storage"))
    }

    fun saveInventory() {
        ensureFileExists(invFile)
        val configuration = YamlConfiguration.loadConfiguration(invFile)
        configuration["storage"] = inventory?.serialize()?.get("storage")
        try {
            configuration.save(invFile)
        } catch (e: IOException) {
            throw RuntimeException(e)
        }
    }

    fun loadProduction() {
        ensureFileExists(file)
        val configuration = YamlConfiguration.loadConfiguration(file)
        println(configuration)
        println(configuration.get("production"))
        this.production = Production.deserialize(configuration.getConfigurationSection("production"))
    }

    fun printInventory() {
        println(inventory?.items)
    }

    fun produce() {
        ARC.instance.server.scheduler.runTaskAsynchronously(ARC.instance, Runnable {
            val inv = inventory ?: return@Runnable
            if (inv.inUse) return@Runnable
            inv.inUse = true
            val prod = production ?: run { inv.inUse = false; return@Runnable }

            val reqs = prod.reqs(level)
            val success = HashSet<String>()
            for ((key, value) in reqs) {
                if (!inv.contains(value)) continue
                inv.remove(value)
                success.add(key)
            }

            val produced = prod.produce(success, level)
            inv.add(produced)
            inv.trim()
            inv.inUse = false
        })
    }

    fun addItem(stack: ItemStack) {
        inventory?.add(stack)
    }

    override fun serialize(): Map<String, Any> = emptyMap()

    private fun ensureFileExists(f: File) {
        if (!f.exists()) {
            f.parentFile.mkdirs()
            try {
                f.createNewFile()
            } catch (e: IOException) {
                throw RuntimeException(e)
            }
        }
    }
}
