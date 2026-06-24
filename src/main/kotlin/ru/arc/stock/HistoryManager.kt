package ru.arc.stock

import org.bukkit.Bukkit
import org.bukkit.scheduler.BukkitTask
import ru.arc.ARC
import ru.arc.config.ConfigManager
import ru.arc.config.StockConfig
import ru.arc.util.Common
import ru.arc.util.Logging.debug
import ru.arc.util.Logging.error
import ru.arc.util.Logging.info
import ru.arc.util.Logging.warn
import com.google.gson.reflect.TypeToken
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

object HistoryManager {

    private const val SCRIPT_FILE = "plots.sh"

    private val history: MutableMap<String, MutableList<StockHistory>> = ConcurrentHashMap()
    private val highLows: MutableMap<String, HighLow> = ConcurrentHashMap()
    private var messager: HistoryMessager? = null
    private var historyPath: Path? = null
    private var saveTask: BukkitTask? = null

    private val config by lazy {
        ConfigManager.of(ARC.instance.dataPath, "stocks/stock.yml")
    }

    data class StockHistory(val cost: Double, val timestamp: Long)

    data class HighLow(var high: Double, var low: Double)

    @JvmStatic
    fun setMessager(m: HistoryMessager) {
        messager = m
    }

    @JvmStatic
    fun setHighLows(highLowMap: Map<String, HighLow>) {
        highLows.clear()
        highLows.putAll(highLowMap)
    }

    @JvmStatic
    fun init() {
        if (!config.bool("enabled", false)) {
            info("Stocks are disabled")
            return
        }
        historyPath = ARC.instance.dataFolder.toPath().resolve("stocks/history.json").also { path ->
            if (!Files.exists(path)) {
                try {
                    Files.createDirectories(path.parent)
                    Files.createFile(path)
                } catch (e: IOException) {
                    error("Error creating history file", e)
                }
            }
        }

        loadFromFile()
        startTasks()
    }

    @JvmStatic
    fun startTasks() {
        cancelTasks()
        saveTask = ARC.instance.server.scheduler.runTaskTimerAsynchronously(
            ARC.instance,
            Runnable {
                try {
                    if (!StockConfig.mainServer) return@Runnable
                    saveHistory()
                    drawPlots(true)
                    messager?.send(highLows)
                } catch (e: Exception) {
                    error("Error in saveTask", e)
                }
            },
            100L,
            20L * 300,
        )
    }

    @JvmStatic
    fun drawPlots(sendPackets: Boolean) {
        val path = "${ARC.instance.dataFolder}/stocks/$SCRIPT_FILE"
        val time = System.currentTimeMillis()
        val file = java.io.File(path)
        if (file.exists()) {
            try {
                val process = ProcessBuilder(path).redirectErrorStream(true).start()
                process.inputStream.bufferedReader().forEachLine { debug("plot: {}", it) }
                debug("Plotting took: {}ms", System.currentTimeMillis() - time)
                if (sendPackets) {
                    Bukkit.getScheduler().runTask(ARC.instance, Runnable {
                        ARC.trySeverCommand("arc-invest -t:update")
                    })
                }
            } catch (e: Exception) {
                error("Error drawing plots", e)
                throw RuntimeException(e)
            }
        } else {
            warn("Plot script {} does not exist, skipping", path)
        }
    }

    @JvmStatic
    fun cancelTasks() {
        saveTask?.takeIf { !it.isCancelled }?.cancel()
    }

    @JvmStatic
    fun pruneHistory(symbol: String) {
        history.remove(symbol)
        saveHistory()
    }

    @JvmStatic
    fun saveHistory() {
        if (!config.bool("enabled", false)) {
            info("Stocks are disabled")
            return
        }
        val path = historyPath ?: run {
            info("Stocks not initialized, skipping history save")
            return
        }
        info("Saving history size {}", history.values.sumOf { it.size })
        evictOldHistory()
        saveToFile(path)
    }

    private fun evictOldHistory() {
        val current = System.currentTimeMillis()
        for (list in history.values) {
            list.removeIf { current - it.timestamp > 1000L * StockConfig.historyLifetime }
            val seen = mutableSetOf<Long>()
            list.removeIf { !seen.add(it.timestamp) }
        }
    }

    @JvmStatic
    fun add(symbol: String, price: Double) = add(symbol, price, System.currentTimeMillis())

    @JvmStatic
    fun add(symbol: String, price: Double, timestamp: Long) {
        history.compute(symbol) { _, list ->
            (list ?: mutableListOf()).also { it.add(StockHistory(price, timestamp)) }
        }
        highLows.merge(symbol, HighLow(price, price)) { old, new ->
            HighLow(maxOf(old.high, new.high), minOf(old.low, new.low))
        }
    }

    @JvmStatic
    fun high(symbol: String): Double = highLows[symbol]?.high ?: 0.0

    @JvmStatic
    fun low(symbol: String): Double = highLows[symbol]?.low ?: 0.0

    @JvmStatic
    fun loadFromFile() {
        val path = historyPath ?: return
        try {
            val typeToken = object : TypeToken<Map<String, List<StockHistory>>>() {}
            val loaded: Map<String, List<StockHistory>>? = path.toFile().bufferedReader().use { reader ->
                Common.gson.fromJson(reader, typeToken.type)
            }
            if (loaded == null) {
                info("History file empty, starting fresh")
                saveToFile(path)
                return
            }
            info("Loaded history: {}", loaded.values.sumOf { it.size })
            appendHistory(loaded)
        } catch (e: Exception) {
            error("Error loading history", e)
            saveToFile(path)
        }
    }

    @JvmStatic
    fun saveToFile() = historyPath?.let { saveToFile(it) }

    private fun saveToFile(path: Path) {
        try {
            Files.write(path, Common.prettyGson.toJson(history).toByteArray())
        } catch (e: IOException) {
            error("Error saving history", e)
        }
    }

    @JvmStatic
    fun appendHistory(loaded: Map<String, List<StockHistory>>) {
        for ((symbol, entries) in loaded) {
            for (entry in entries) {
                add(symbol, entry.cost, entry.timestamp)
            }
        }
        info("Appended history: {}", loaded.values.sumOf { it.size })
    }
}
