package ru.arc.xserver;

import lombok.Data;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitRunnable;
import ru.arc.ARC;
import ru.arc.network.adapters.JsonSubtype;
import ru.arc.network.adapters.JsonType;

import static ru.arc.util.Logging.error;

@JsonType(
        property = "type",
        subtypes = {
                @JsonSubtype(clazz = XMessage.class, name = "xmessage"),
                @JsonSubtype(clazz = XCommand.class, name = "xcommand"),
                @JsonSubtype(clazz = XPay.class, name = "xpay"),
        }
)
@Data
public abstract class XAction {

    Long afterTimestamp;
    Boolean async;

    protected abstract void runInternal();

    public void run() {
        try {
            afterTimestamp = afterTimestamp == null ? System.currentTimeMillis() : afterTimestamp;
            long delta = afterTimestamp - System.currentTimeMillis();
            int ticksDelay = Math.max(0, (int) (delta / 50 + (delta % 50 != 0 ? 1 : 0)));
            if (async == null || !async) run(ticksDelay);
            else runAsync(ticksDelay);
        } catch (Exception e) {
            error("Error executing action: {}", this, e);
        }
    }

    private void runAsync(int ticksDelay) {
        new BukkitRunnable() {
            @Override
            public void run() {
                runInternal();
            }
        }.runTaskLaterAsynchronously(ARC.plugin, ticksDelay);
    }

    private void run(int ticksDelay) {
        if (Bukkit.isPrimaryThread()) {
            if (ticksDelay == 0) {
                runInternal();
                return;
            }
        }
        new BukkitRunnable() {
            @Override
            public void run() {
                runInternal();
            }
        }.runTaskLater(ARC.plugin, ticksDelay);
    }
}


