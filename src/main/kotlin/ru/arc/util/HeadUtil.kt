package ru.arc.util

import com.google.gson.Gson
import de.tr7zw.changeme.nbtapi.NBT
import org.apache.logging.log4j.LogManager
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import ru.arc.ARC
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CompletableFuture

object HeadUtil {

    private val log = LogManager.getLogger(HeadUtil::class.java)
    private val gson = Gson()
    private var last409: Long = 0

    @JvmStatic
    fun getSkull(uuid: UUID): ItemStack {
        val item = ItemStack(Material.PLAYER_HEAD)
        val texture = fetchHead(uuid).getNow(null)
        if (texture == null || texture.isEmpty()) return item
        NBT.modifyComponents(item) { nbt ->
            val profileNbt = nbt.getOrCreateCompound("minecraft:profile")
            profileNbt.setUUID("id", UUID.randomUUID())
            val propertiesNbt = profileNbt.getCompoundList("properties").addCompound()
            propertiesNbt.setString("name", "GrocerMC")
            propertiesNbt.setString("value", texture)
        }
        return item
    }

    @JvmStatic
    fun getSkull(name: String): ItemStack {
        val texture = fetchHead(name).getNow(null)
        val item = ItemStack(Material.PLAYER_HEAD)
        if (texture == null || texture.isEmpty()) return item
        NBT.modify(item) { nbt ->
            val skullOwnerCompound = nbt.getOrCreateCompound("SkullOwner")
            skullOwnerCompound.setUUID("Id", UUID.randomUUID())
            skullOwnerCompound.setString("Name", name)
            skullOwnerCompound.getOrCreateCompound("Properties")
                .getCompoundList("textures")
                .addCompound()
                .setString("Value", texture)
        }
        return item
    }

    @JvmStatic
    fun fetchHead(uuid: UUID): CompletableFuture<String?> {
        val offlinePlayer = Bukkit.getOfflinePlayer(uuid)
        var name = offlinePlayer.name
        if (name == null || name.isEmpty()) {
            name = "MHF_Steve"
        }
        return fetchHead(name)
    }

    @JvmStatic
    fun fetchHead(name: String): CompletableFuture<String?> {
        log.trace("Fetching head for $name")
        if (ARC.headTextureCache != null) {
            val texture = ARC.headTextureCache!!.getHeadTexture(name)
            if (texture != null) {
                if (texture.equals("none", ignoreCase = true)) {
                    return CompletableFuture.completedFuture(null)
                }
                return CompletableFuture.completedFuture(texture)
            }
        }
        return CompletableFuture.supplyAsync {
            if (System.currentTimeMillis() - last409 < 30000) return@supplyAsync null
            var resp: HttpResponse<String>
            try {
                var url = "https://api.mojang.com/users/profiles/minecraft/$name"
                val client = HttpClient.newHttpClient()
                var request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .build()
                resp = client.send(request, HttpResponse.BodyHandlers.ofString())

                if (resp.statusCode() == 409) {
                    last409 = System.currentTimeMillis()
                    return@supplyAsync null
                }
                if (resp.statusCode() == 404) {
                    ARC.headTextureCache?.setHeadTexture(name, "none")
                }
                if (resp.statusCode() != 200) {
                    log.trace("Failed to fetch head for $name ${resp.statusCode()}")
                    return@supplyAsync null
                }

                @Suppress("UNCHECKED_CAST")
                val id = (gson.fromJson(resp.body(), Map::class.java) as Map<*, *>)["id"] as? String
                if (id == null) return@supplyAsync null

                url = "https://sessionserver.mojang.com/session/minecraft/profile/$id"
                request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .build()
                resp = client.send(request, HttpResponse.BodyHandlers.ofString())
                if (resp.statusCode() == 409) {
                    last409 = System.currentTimeMillis()
                    return@supplyAsync null
                }
                if (resp.statusCode() != 200) {
                    log.trace("Failed to fetch head for $name")
                    return@supplyAsync null
                }

                @Suppress("UNCHECKED_CAST")
                val properties = (gson.fromJson(resp.body(), Map::class.java) as Map<*, *>)["properties"] as? List<*>
                val textureJson = (properties?.get(0) as? Map<*, *>)?.get("value") as? String
                if (textureJson == null) return@supplyAsync null

                @Suppress("UNCHECKED_CAST")
                val textureMap = gson.fromJson(
                    String(Base64.getDecoder().decode(textureJson)),
                    Map::class.java
                ) as Map<*, *>

                val resMap = mutableMapOf<String, Any>()
                resMap["textures"] = textureMap["textures"] ?: return@supplyAsync null

                val texture = Base64.getEncoder().encodeToString(gson.toJson(resMap).toByteArray())

                ARC.headTextureCache?.setHeadTexture(name, texture)
                return@supplyAsync texture
            } catch (e: Exception) {
                log.trace("Failed to fetch head for $name", e.message)
                return@supplyAsync null
            }
        }
    }
}

