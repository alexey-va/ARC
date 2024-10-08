package arc.arc.xserver.announcements;

import arc.arc.configs.MainConfig;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import me.clip.placeholderapi.PlaceholderAPI;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.OfflinePlayer;
import org.bukkit.boss.BarColor;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AnnouncementData {

    String message;
    @Builder.Default
    boolean minimessage = true;
    @Builder.Default
    List<String> servers = new ArrayList<>();
    @Builder.Default
    boolean everywhere = true;
    @Builder.Default
    boolean playerSpecific = false;
    @Builder.Default
    boolean cache = true;
    @Builder.Default
    int weight = 1;
    @Builder.Default
    Type type = Type.CHAT;
    @Builder.Default
    BarColor bossBarColor = BarColor.RED;
    @Builder.Default
    int seconds = 5;
    @Builder.Default
    List<ArcCondition> arcConditions = new ArrayList<>();

    String originServer = MainConfig.server;

    transient Component cachedComponent;
    transient Map<UUID, Component> playerSpecificComponent = new ConcurrentHashMap<>();

    public Component component() {
        if (cachedComponent != null) return cachedComponent;
        //System.out.println("Parsing simple "+message);
        String parsedMessage = PlaceholderAPI.setPlaceholders(null, message);
        if (minimessage)
            cachedComponent = MiniMessage.miniMessage().deserialize(parsedMessage, TagResolver.builder().build());
        else cachedComponent = LegacyComponentSerializer.legacyAmpersand().deserialize(parsedMessage);
        return cachedComponent;
    }

    public Component component(OfflinePlayer offlinePlayer) {
        if (!playerSpecific) return component();
        if (cache) {
            if (playerSpecificComponent == null) playerSpecificComponent = new HashMap<>();
            Component component = playerSpecificComponent.get(offlinePlayer.getUniqueId());
            if (component != null) return component;

            //System.out.println("Parsing "+message);
            String parsedMessage = PlaceholderAPI.setPlaceholders(offlinePlayer, message);
            Component parsed;
            if (minimessage)
                parsed = MiniMessage.miniMessage().deserialize(parsedMessage, TagResolver.builder().build());
            else parsed = LegacyComponentSerializer.legacyAmpersand().deserialize(parsedMessage);
            playerSpecificComponent.put(offlinePlayer.getUniqueId(), parsed);
            return parsed;
        } else {
            //System.out.println("Parsing "+message);
            String parsedMessage = PlaceholderAPI.setPlaceholders(offlinePlayer, message);
            if (minimessage) return MiniMessage.miniMessage().deserialize(parsedMessage, TagResolver.builder().build());
            else return LegacyComponentSerializer.legacyAmpersand().deserialize(parsedMessage);
        }
    }

    public enum Type {
        CHAT, BOSSBAR, ACTIONBAR
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AnnouncementData data)) return false;

        if (minimessage != data.minimessage) return false;
        if (everywhere != data.everywhere) return false;
        if (playerSpecific != data.playerSpecific) return false;
        if (cache != data.cache) return false;
        if (weight != data.weight) return false;
        if (seconds != data.seconds) return false;
        if (!message.equals(data.message)) return false;
        if (!Objects.equals(servers, data.servers)) return false;
        if (type != data.type) return false;
        if (!Objects.equals(bossBarColor, data.bossBarColor)) return false;
        if (!Objects.equals(arcConditions, data.arcConditions))
            return false;
        return Objects.equals(originServer, data.originServer);
    }

    @Override
    public int hashCode() {
        int result = message.hashCode();
        result = 31 * result + (minimessage ? 1 : 0);
        result = 31 * result + (servers != null ? servers.hashCode() : 0);
        result = 31 * result + (everywhere ? 1 : 0);
        result = 31 * result + (playerSpecific ? 1 : 0);
        result = 31 * result + (cache ? 1 : 0);
        result = 31 * result + weight;
        result = 31 * result + (type != null ? type.hashCode() : 0);
        result = 31 * result + (bossBarColor != null ? bossBarColor.hashCode() : 0);
        result = 31 * result + seconds;
        result = 31 * result + (arcConditions != null ? arcConditions.hashCode() : 0);
        result = 31 * result + (originServer != null ? originServer.hashCode() : 0);
        return result;
    }
}
