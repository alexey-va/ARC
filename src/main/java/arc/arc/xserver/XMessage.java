package arc.arc.xserver;

import java.util.Collection;

import arc.arc.hooks.HookRegistry;
import arc.arc.util.TextUtil;
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
    boolean isCacheable = true;

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
    int bossBarSeconds;

    transient Component deserialized;

    @Override
    protected void runInternal(Collection<Player> players) {
        switch (type) {
            case CHAT -> players.forEach(p -> p.sendMessage(component()));
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
                                serializedMessage, p, barColor, bossBarSeconds));
            }
        }
    }

    private Component component() {
        if (deserialized != null) return deserialized;
        Component component;
        if (serializationType == SerializationType.MINI_MESSAGE)
            component = TextUtil.mm(serializedMessage);
        else if (serializationType == SerializationType.LEGACY)
            component = TextUtil.legacy(serializedMessage);
        else
            component = TextUtil.plain(serializedMessage);
        if (isCacheable) deserialized = component;
        return component;
    }


    public enum Type {
        CHAT, ACTION_BAR, BOSS_BAR, TOAST
    }

    public enum SerializationType {
        MINI_MESSAGE, LEGACY, PLAIN
    }

}


