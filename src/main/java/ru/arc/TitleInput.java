package ru.arc;

import ru.arc.board.guis.Inputable;
import lombok.Data;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Data
public class TitleInput {

    private static final Map<Player, TitleInput> activeInputs = new ConcurrentHashMap<>();
    private static BukkitTask clearTask;


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

        if (activeInputs.containsKey(player)) {
            System.out.println("Player" + player.getName() + " already has title input!");
        }

        activeInputs.put(player, this);
        sendStartMessage();
        sendTitle();
    }

    public static void setupTask(long period) {
        if (clearTask != null && !clearTask.isCancelled()) clearTask.cancel();
        clearTask = new BukkitRunnable() {
            @Override
            public void run() {
                for (var input : activeInputs.entrySet()) {
                    if (input.getKey() == null || !input.getKey().isOnline() || input.getValue().isExpired()) {
                        activeInputs.remove(input.getKey());
                        if (input.getKey() != null && input.getKey().isOnline())
                            input.getValue().sendTimeoutMessage();
                    }
                }
            }
        }.runTaskTimer(ARC.plugin, period, period);
    }

    public static boolean hasInput(Player player) {
        return activeInputs.containsKey(player);
    }

    public static void processMessage(Player player, String message) {
        TitleInput titleInput = activeInputs.get(player);
        if (titleInput == null) return;
        if (!titleInput.inputable.satisfy(message, titleInput.id)) {
            titleInput.sendDenyMessage(message);
            titleInput.setTimestamp(System.currentTimeMillis());
            titleInput.sendTitle();
            return;
        }
        titleInput.inputable.setParameter(titleInput.id, message);
        titleInput.inputable.proceed();
        titleInput.remove();

        player.clearTitle();
    }

    private void remove() {
        activeInputs.remove(this.player);
    }

    private void sendTitle() {
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
