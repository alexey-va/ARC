package arc.arc.xserver.announcements;

import arc.arc.hooks.HookRegistry;
import arc.arc.hooks.PAPIHook;
import arc.arc.network.ArcSerializable;
import com.fasterxml.jackson.annotation.JsonIgnore;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AnnouncementData extends ArcSerializable {

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
    List<ArcCondition> arcConditions = new ArrayList<>();
    @JsonIgnore
    Component cachedComponent;
    @JsonIgnore
    Map<UUID, Component> playerSpecificComponent;



    public Component component(){
        if(cachedComponent != null) return cachedComponent;
        String parsedMessage = PlaceholderAPI.setPlaceholders(null, message);
        if(minimessage) cachedComponent = MiniMessage.miniMessage().deserialize(parsedMessage, TagResolver.builder().build());
        else cachedComponent = LegacyComponentSerializer.legacyAmpersand().deserialize(parsedMessage);
        return cachedComponent;
    }

    public Component component(OfflinePlayer offlinePlayer){
        if(!playerSpecific) return component();
        if(cache) {
            Component component = playerSpecificComponent.get(offlinePlayer.getUniqueId());
            if (component != null) return component;

            String parsedMessage = PlaceholderAPI.setPlaceholders(offlinePlayer, message);
            Component parsed;
            if(minimessage) parsed = MiniMessage.miniMessage().deserialize(parsedMessage, TagResolver.builder().build());
            else parsed = LegacyComponentSerializer.legacyAmpersand().deserialize(parsedMessage);
            playerSpecificComponent.put(offlinePlayer.getUniqueId(), parsed);
            return parsed;
        } else{
            String parsedMessage = PlaceholderAPI.setPlaceholders(offlinePlayer, message);
            if(minimessage) return MiniMessage.miniMessage().deserialize(parsedMessage, TagResolver.builder().build());
            else return LegacyComponentSerializer.legacyAmpersand().deserialize(parsedMessage);
        }
    }



}