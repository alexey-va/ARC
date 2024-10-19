package arc.arc.ai;

import arc.arc.ARC;
import arc.arc.configs.Config;
import arc.arc.configs.ConfigManager;
import arc.arc.hooks.HookRegistry;
import arc.arc.hooks.citizens.CitizensHook;
import arc.arc.util.CooldownManager;
import arc.arc.util.TextUtil;
import lombok.extern.slf4j.Slf4j;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;

@Slf4j
public class GPTManager {

    private static final Map<String, GPTEntity> entities = new ConcurrentHashMap<>();
    private static final Config config = ConfigManager.of(ARC.plugin.getDataPath(), "gpt.yml");
    private static final Map<UUID, List<Conversation>> conversations = new ConcurrentHashMap<>();
    private static final Set<UUID> awaitingResponse = new ConcurrentSkipListSet<>();
    private static BukkitTask cleanupTask;

    public static void init() {
        entities.clear();
        conversations.clear();
        cancel();
        cleanupTask = new BukkitRunnable() {
            @Override
            public void run() {
                long now = System.currentTimeMillis();
                conversations.forEach((uuid, convs) -> {
                    boolean removed = convs.removeIf(c -> now - c.lastMessageTime > c.lifeTime);
                    if (removed) log.info("Removed expired conversations for player {}", uuid);
                    if (convs.isEmpty()) conversations.remove(uuid);
                });
            }
        }.runTaskTimerAsynchronously(ARC.plugin, 0, 20 * 30L);
    }

    public static void cancel() {
        if (cleanupTask != null && !cleanupTask.isCancelled()) cleanupTask.cancel();
    }

    public static CompletableFuture<Optional<String>> getResponse(Player player, String message,
                                                                  String id, String archetype) {
        String playerName = player.getName();
        GPTEntity entity = entities.computeIfAbsent(id, key -> new GPTEntity(config, archetype, id));
        if (!entity.archetype.equals(archetype)) {
            log.warn("Entity {} has different archetype {} than expected {}", id, entity.archetype, archetype);
        }
        return entity.getResponse(player.getUniqueId(), playerName, message);
    }

    public static void processMessage(AsyncPlayerChatEvent chatEvent) {
        if (awaitingResponse.contains(chatEvent.getPlayer().getUniqueId())) {
            return;
        }
        Player player = chatEvent.getPlayer();
        String message = chatEvent.getMessage();
        processMessage(message, player, true);
    }

    public static CompletableFuture<Void> processMessage(String message, Player player, boolean appendCancel) {
        List<Conversation> conv = conversations.get(player.getUniqueId());
        if (conv == null) return CompletableFuture.completedFuture(null);
        if (conv.isEmpty()) return CompletableFuture.completedFuture(null);

        Location playerLocation = player.getLocation();
        log.info("Looking for conversation for player {} at location {}", player.getName(), playerLocation);
        Conversation conversation;
        long now = System.currentTimeMillis();
        conversation = conv.stream()
                .filter(c ->
                        ((c.location.getWorld().getName().equals(playerLocation.getWorld().getName()) &&
                                c.location.distance(playerLocation) < c.radius) || (c.radius < 0))
                                && now - c.lastMessageTime < c.lifeTime)
                .findFirst()
                .orElse(null);
        if (conversation == null) {
            log.info("Player {} is not in range of any conversation", player.getName());
            return CompletableFuture.completedFuture(null);
        }
        log.info("Player {} is in range of conversation with entity {}", player.getName(), conversation.gptId);
        if (message.startsWith("!")) message = message.substring(1);
        return getResponseAndSend(player, message, conversation, appendCancel);
    }

    private static CompletableFuture<Void> getResponseAndSend(Player player, String message, Conversation conversation, boolean appendCancel) {
        awaitingResponse.add(player.getUniqueId());
        return getResponse(player, message, conversation.gptId, conversation.getArchetype())
                .thenAccept(response -> {
                    conversation.lastMessageTime = System.currentTimeMillis();
                    if (response.isEmpty()) {
                        log.warn("Empty response from GPT for player {}", player.getName());
                        awaitingResponse.remove(player.getUniqueId());
                        return;
                    }
                    Component responseMessage = formatMessage(response.get(), conversation, appendCancel);
                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            displayChatBubble(response.get(), conversation);
                            if (conversation.privateConversation) {
                                player.sendMessage(responseMessage);
                            } else {
                                conversation.getLocation()
                                        .getNearbyPlayers(conversation.getRadius())
                                        .forEach(p -> p.sendMessage(responseMessage));
                            }
                            awaitingResponse.remove(player.getUniqueId());
                        }
                    }.runTask(ARC.plugin);
                });
    }

    private static void displayChatBubble(String message, Conversation conversation) {
        if (HookRegistry.citizensHook == null || conversation.npcId == null) return;
        if (message.length() > config.integer("max-bubble-length", 50)) {
            log.info("Message is too long for bubble: {}", message);
            return;
        }
        String s = TextUtil.mmToLegacy(message);
        List<CitizensHook.HologramLine> list = new ArrayList<>();
        String[] split = s.split("\n");
        for (String string : split) {
            list.add(new CitizensHook.HologramLine(string, config.integer("bubble-duration-ticks", 20 * 20)));
        }
        HookRegistry.citizensHook.addChatBubble(conversation.npcId, list);
    }

    private static Component formatMessage(String message, Conversation conversation, boolean appendCancel) {
        String format = config.string("message-format", "<gray><gold>%gpt_name%<gray> » <white>%message%");
        if (appendCancel)
            format += config.string("cancel-appendix", "\n<red><hover:show_text:'Нажмите, чтобы закончить'><click:run_command:/arc ai stop %id%>[Нажмите, чтобы закончить разговор]</click></hover>");
        format = format.replace("%gpt_name%", conversation.getTalkerName());
        format = format.replace("%message%", message);
        format = format.replace("%id%", conversation.gptId);
        return TextUtil.mm(format);
    }

    public static void startConversation(Player player, String id, String archetype, String talkerName,
                                         Location location, double radius, long lifeTime, String initialMessage,
                                         String endMessage, Integer npcId, boolean privateConversation) {
        String playerName = player.getName();
        entities.computeIfAbsent(id, key -> new GPTEntity(config, archetype, id));
        List<Conversation> convs = conversations.computeIfAbsent(player.getUniqueId(), key -> new ArrayList<>());
        if (convs.stream().anyMatch(c -> c.gptId.equals(id))) {
            log.info("Player {} already has conversation with entity {}", playerName, id);
            return;
        }
        convs.add(Conversation.builder()
                .playerUuid(player.getUniqueId())
                .location(location)
                .archetype(archetype)
                .radius(radius)
                .gptId(id)
                .lastMessageTime(System.currentTimeMillis())
                .lifeTime(lifeTime)
                .talkerName(talkerName)
                .npcId(npcId)
                .endMessage(endMessage)
                .privateConversation(privateConversation)
                .build());
        log.info("Player {} started conversation with entity {}", playerName, id);
        if (initialMessage != null) {
            getResponseAndSend(player, initialMessage, convs.getLast(), false);
        }
    }

    public static void endConversation(Player player, String id) {
        List<Conversation> convs = conversations.get(player.getUniqueId());
        if (convs == null) {
            return;
        }
        Conversation conversation = null;
        for (Conversation c : convs) {
            if (c.gptId.equals(id)) {
                conversation = c;
                break;
            }
        }
        log.info("Player {} ended conversation with entity {}", player.getName(), id);
        if (conversation == null) {
            log.warn("Player {} tried to end conversation with entity {} but it was not found", player.getName(), id);
            return;
        }
        if (conversation.endMessage != null) {
            processMessage(conversation.endMessage, player, false)
                    .thenAccept(a -> player.sendMessage(config.componentDef("end-message", "<red>Вы закончили разговор")));
        } else {
            player.sendMessage(config.componentDef("end-message", "<red>Вы закончили разговор"));
        }
        convs.remove(conversation);
    }

    public static void endAllConversations(Player player) {
        List<Conversation> convs = conversations.remove(player.getUniqueId());
        player.sendMessage(config.componentDef("end-all-message", "<red>Вы закончили все разговоры"));
        if (convs == null) {
            return;
        }
        log.info("Player {} ended all conversations", player.getName());
    }


    public static List<Conversation> getConversations(Player commandSender) {
        return conversations.getOrDefault(commandSender.getUniqueId(), List.of());
    }
}
