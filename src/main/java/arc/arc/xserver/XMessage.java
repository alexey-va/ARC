package arc.arc.xserver;

import java.util.ArrayList;
import java.util.List;

import arc.arc.hooks.HookRegistry;
import arc.arc.util.TextUtil;
import arc.arc.xserver.playerlist.PlayerManager;
import com.google.gson.annotations.SerializedName;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.boss.BarColor;
import org.bukkit.entity.Player;

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
    @SerializedName("ca")
    @Builder.Default
    boolean isCacheable = true;
    @SerializedName("cond")
    List<XCondition> conditions;



    @SerializedName("tm")
    Material toastMaterial;
    @SerializedName("tmd")
    int toastModelData;
    @SerializedName("tt")
    String toastTitle;

    @SerializedName("bbn")
    String bossBarName;
    @SerializedName("bc")
    BarColor barColor;
    @SerializedName("bbs")
    int seconds;

    @SerializedName("p")
    @Builder.Default
    boolean personal = false;
    @SerializedName("we")
    @Builder.Default
    int weight = 1;

    transient Component deserialized;

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
                HookRegistry.cmiHook.sendToast(serializedMessage, toastTitle, toastModelData, toastMaterial, players.toArray(new Player[0]));
            }
            case BOSS_BAR -> {
                if (HookRegistry.cmiHook == null) {
                    log.warn("CMILIB is required for BOSS_BAR xMessage");
                    return;
                }
                players.forEach(p ->
                        HookRegistry.cmiHook.sendBossbar(bossBarName == null ? "xmessage" : bossBarName,
                                serializedMessage, p, barColor, seconds));
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
        if (!personal && deserialized != null) return deserialized;
        Component component;
        String message = personal ? HookRegistry.papiHook.parse(serializedMessage, player) : serializedMessage;
        if (serializationType == SerializationType.MINI_MESSAGE) {
            component = TextUtil.mm(message);
        } else if (serializationType == SerializationType.LEGACY) {
            component = TextUtil.legacy(message);
        } else {
            component = TextUtil.plain(message);
        }
        if (isCacheable && !personal) deserialized = component;
        return component;
    }


    public enum Type {
        CHAT, ACTION_BAR, BOSS_BAR, TOAST
    }

    public enum SerializationType {
        MINI_MESSAGE, LEGACY, PLAIN
    }

}


