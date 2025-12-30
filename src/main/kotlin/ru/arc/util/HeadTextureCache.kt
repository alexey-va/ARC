package ru.arc.util

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName
import ru.arc.ARC
import ru.arc.util.Logging.error
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap

class HeadTextureCache {

    private lateinit var path: Path
    private var data: Data = Data()
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    init {
        try {
            path = ARC.plugin!!.dataFolder.toPath().resolve("head-cache.json")
            if (!Files.exists(path)) {
                Files.createFile(path)
            }
            Files.newBufferedReader(path).use { reader ->
                val loaded = gson.fromJson(reader, Data::class.java)
                if (loaded != null) {
                    data = loaded
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            data = Data()
            error("Could not create head cache file", e)
        }
    }

    fun save() {
        try {
            Files.write(
                path,
                gson.toJson(data).toByteArray(),
                StandardOpenOption.WRITE,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
            )
        } catch (e: Exception) {
            error("Could not save head cache", e)
        }
    }

    fun getHeadTexture(name: String): String? {
        return try {
            val td = data.byName[name] ?: return null
            if (System.currentTimeMillis() - td.timestamp > 1000L * 60 * 60 * 24 * 30L) {
                data.byName.remove(name)
                return null
            }
            data.byName[name]?.string
        } catch (e: Exception) {
            null
        }
    }

    fun setHeadTexture(name: String, texture: String) {
        data.byName[name] = TextureData(texture, System.currentTimeMillis())
    }

    class Data {
        @SerializedName("n")
        var byName: ConcurrentHashMap<String, TextureData> = ConcurrentHashMap()
    }

    data class TextureData(
        @SerializedName("s")
        var string: String,
        @SerializedName("t")
        var timestamp: Long
    )
}

