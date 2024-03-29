package arc.arc.util;

import arc.arc.ARC;
import com.google.gson.Gson;
import de.tr7zw.changeme.nbtapi.NBT;
import de.tr7zw.changeme.nbtapi.iface.ReadWriteNBT;
import lombok.extern.log4j.Log4j2;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.concurrent.CompletableFuture;

@Log4j2
public class HeadUtil {

    private static Gson gson = new Gson();

    private static long last409 = 0;

    public static ItemStack getSkull(UUID uuid) {
        final ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        String texture = fetchHead(uuid).getNow(null);
        if(texture == null || texture.isEmpty()) return item;
        NBT.modify(item, nbt -> {
            final ReadWriteNBT skullOwnerCompound = nbt.getOrCreateCompound("SkullOwner");
            skullOwnerCompound.setUUID("Id", UUID.randomUUID());
            skullOwnerCompound.getOrCreateCompound("Properties")
                    .getCompoundList("textures")
                    .addCompound()
                    .setString("Value", texture);
        });
        return item;
    }

    public static ItemStack getSkull(String name) {
        String texture = fetchHead(name).getNow(null);
        final ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        if(texture == null || texture.isEmpty()) return item;
        NBT.modify(item, nbt -> {
            final ReadWriteNBT skullOwnerCompound = nbt.getOrCreateCompound("SkullOwner");
            skullOwnerCompound.setUUID("Id", UUID.randomUUID());
            skullOwnerCompound.setString("Name", name);
            skullOwnerCompound.getOrCreateCompound("Properties")
                    .getCompoundList("textures")
                    .addCompound()
                    .setString("Value", texture);
        });
        return item;
    }

    public static CompletableFuture<String> fetchHead(UUID uuid){
        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(uuid);
        String name = offlinePlayer.getName();
        if(name == null || name.isEmpty()) name = "MHF_Steve";

        return fetchHead(name);
    }

    public static CompletableFuture<String> fetchHead(String name) {
        log.trace("Fetching head for "+name);
        if (ARC.headTextureCache != null) {
            String texture = ARC.headTextureCache.getHeadTexture(name);
            if(texture != null) {
                if (texture.equalsIgnoreCase("none")) return CompletableFuture.completedFuture(null);
                return CompletableFuture.completedFuture(texture);
            }
        }
        return CompletableFuture.supplyAsync(() -> {
            if(System.currentTimeMillis() - last409 < 30000) return null;
            HttpResponse<String> resp;
            try {
                String url = "https://api.mojang.com/users/profiles/minecraft/" + name;
                HttpClient client = HttpClient.newHttpClient();
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .build();
                resp = client.send(request, HttpResponse.BodyHandlers.ofString());

                if(resp.statusCode() == 409){
                    last409 = System.currentTimeMillis();
                    return null;
                }
                if(resp.statusCode() == 404){
                    if (ARC.headTextureCache != null) ARC.headTextureCache.setHeadTexture(name, "none");
                }
                if(resp.statusCode() != 200) {
                    log.trace("Failed to fetch head for "+name+" "+resp.statusCode());
                    return null;
                }

                String id = (String) gson.fromJson(resp.body(), Map.class).get("id");
                if(id == null) return null;

                url = "https://sessionserver.mojang.com/session/minecraft/profile/" + id;
                request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .build();
                resp = client.send(request, HttpResponse.BodyHandlers.ofString());
                if(resp.statusCode() == 409){
                    last409 = System.currentTimeMillis();
                    return null;
                }
                if(resp.statusCode() != 200) {
                    log.trace("Failed to fetch head for "+name);
                    return null;
                }

                String textureJson = (String) ((Map) ((List) gson.fromJson(resp.body(), Map.class).get("properties")).get(0)).get("value");
                Map textureMap = gson.fromJson(new String(Base64.getDecoder().decode(textureJson)), Map.class);

                Map<String, Object> resMap = new HashMap<>();
                resMap.put("textures", textureMap.get("textures"));

                String texture = Base64.getEncoder().encodeToString(gson.toJson(resMap).getBytes());

                if (ARC.headTextureCache != null) ARC.headTextureCache.setHeadTexture(name, texture);
                return texture;
            } catch (Exception e) {
                log.trace("Failed to fetch head for "+name, e.getMessage());
                return null;
            }
        });
    }

}
