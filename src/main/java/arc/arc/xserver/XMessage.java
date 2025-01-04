package arc.arc.xserver;

import arc.arc.hooks.HookRegistry;
import arc.arc.util.TextUtil;
import arc.arc.xserver.playerlist.PlayerManager;
import com.google.gson.annotations.SerializedName;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.boss.BarColor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

@EqualsAndHashCode(callSuper = true)
@Data
@ToString(callSuper = true)
@Builder
@Slf4j
public class XMessage extends XAction {

    @SerializedName("t")
    Type type;
    @SerializedName("m")
    String serializedMessage;
    @SerializedName("st")
    SerializationType serializationType;
    @SerializedName("cond")
    List<XCondition> conditions;

    @SerializedName("td")
    ToastData toastData;

    @SerializedName("bbn")
    BossBarData bossBarData;

    @SerializedName("p")
    AnnounceData announceData;

    @SerializedName("ab")
    ActionBarData actionBarData;

    @Override
    protected void runInternal() {
        List<Player> players = filteredPlayers();
        switch (type) {
            case CHAT -> players.forEach(p -> p.sendMessage(component(p)));
            case TOAST -> {
                if (HookRegistry.cmiHook == null) {
                    log.warn("CMILIB is required for TOAST xMessage");
                    return;
                }
                if (toastData == null) {
                    log.warn("ToastData is required for TOAST xMessage");
                    return;
                }
                HookRegistry.cmiHook.sendToast(serializedMessage, toastData.title, toastData.modelData,
                        toastData.material, players.toArray(new Player[0]));
            }
            case BOSS_BAR -> {
                if (HookRegistry.cmiHook == null) {
                    log.warn("CMILIB is required for BOSS_BAR xMessage");
                    return;
                }
                if (bossBarData == null) {
                    log.warn("BossBarData is required for BOSS_BAR xMessage");
                    return;
                }
                players.forEach(p ->
                        HookRegistry.cmiHook.sendBossbar(bossBarData.name == null ? "xmessage" : bossBarData.name,
                                serializedMessage, p, bossBarData.color, bossBarData.seconds, bossBarData.keepFor));
            }
        }
    }

    List<Player> filteredPlayers() {
        List<Player> players = new ArrayList<>();
        List<Player> allPlayers = PlayerManager.getOnlinePlayersThreadSafe();
        for (Player player : allPlayers) {
            boolean fits = true;
            if (conditions != null) {
                for (XCondition xCondition : conditions) {
                    if (!xCondition.test(player)) {
                        fits = false;
                        break;
                    }
                }
            }
            if (fits) players.add(player);
        }
        return players;
    }

    public Component component(Player player) {
        Component component;
        String message = serializedMessage;
        if(HookRegistry.papiHook != null) HookRegistry.papiHook.parse(serializedMessage, player);
        if (serializationType == SerializationType.MINI_MESSAGE) {
            component = TextUtil.mm(message);
        } else if (serializationType == SerializationType.LEGACY) {
            component = TextUtil.legacy(message);
        } else {
            component = TextUtil.plain(message);
        }
        return component;
    }


    public enum Type {
        CHAT, ACTION_BAR, BOSS_BAR, TOAST
    }

    public enum SerializationType {
        MINI_MESSAGE, LEGACY, PLAIN
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    @Builder
    public static class BossBarData {
        @SerializedName("n")
        String name;
        @SerializedName("c")
        BarColor color;
        @SerializedName("s")
        int seconds;
        @SerializedName("t")
        int keepFor;
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    @Builder
    public static class ToastData {
        @SerializedName("m")
        Material material = Material.STONE;
        @SerializedName("md")
        int modelData;
        @SerializedName("t")
        String title;
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    @Builder
    public static class AnnounceData {
        @SerializedName("m")
        int weight;
        @SerializedName("p")
        boolean personal;
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    @Builder
    public static class ActionBarData {
        @SerializedName("s")
        int seconds;
    }


}


