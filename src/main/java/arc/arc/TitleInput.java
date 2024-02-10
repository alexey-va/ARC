package arc.arc;

import arc.arc.board.guis.Inputable;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TitleInput {

    private static final Map<Player, TitleInput> activeInputs = new HashMap<>();
    private static BukkitTask clearTask = null;


    long timestamp;
    Player player;
    Inputable inputable;
    int id;

    public TitleInput(Player player, Inputable inputable, int id) {
        if (clearTask == null) setupTask(5);

        this.player = player;
        this.inputable = inputable;
        this.id = id;
        this.timestamp = System.currentTimeMillis();

        activeInputs.put(player, this);
        sendStartMessage();
        sendTitle();
    }

    public static void setupTask(long period) {
        if (clearTask != null && !clearTask.isCancelled()) clearTask.cancel();
        clearTask = new BukkitRunnable() {
            @Override
            public void run() {
                synchronized (activeInputs) {
                    List<Player> toRemove = new ArrayList<>();
                    for (var input : activeInputs.entrySet()) {
                        if (input.getKey() == null || !input.getKey().isOnline() || input.getValue().isExpired()) {
                            toRemove.add(input.getKey());
                            if (input.getKey() != null && input.getKey().isOnline())
                                input.getValue().sendTimeoutMessage();
                        }
                    }
                    toRemove.forEach(activeInputs::remove);
                }
            }
        }.runTaskTimer(ARC.plugin, period, period);
    }

    public static boolean hasInput(Player player) {
        synchronized (activeInputs) {
            return activeInputs.containsKey(player);
        }
    }

    public static void processMessage(Player player, String message) {
        synchronized (activeInputs) {
            TitleInput titleInput = activeInputs.get(player);
            if (titleInput == null) return;
            if (!titleInput.inputable.satisfy(message, titleInput.id)) {
                titleInput.sendDenyMessage(message);
                return;
            }
            titleInput.inputable.setParameter(titleInput.id, message);
            titleInput.inputable.proceed();
            player.clearTitle();
        }
    }

    private void sendTitle(){
        player.showTitle(Title.title(
                Component.text("Введите в чате...", NamedTextColor.GREEN),
                Component.text(" "),
                Title.Times.times(
                        Duration.ofMillis(1000),
                        Duration.ofMillis(58000),
                        Duration.ofMillis(1000)
                )
        ));
    }
    public boolean isExpired() {
        return (System.currentTimeMillis() - timestamp > 120000);
    }

    public void sendDenyMessage(String message) {
        Component text = inputable.denyMessage(message, id);
        if (text != null) player.sendMessage(text);
    }

    public void sendStartMessage() {
        Component text = inputable.startMessage(id);
        if (text != null) player.sendMessage(text);
    }

    public void sendTimeoutMessage() {
        player.sendMessage(Component.text("Вы не успели ввести текст!", NamedTextColor.RED));
    }

}
