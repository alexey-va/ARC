package ru.arc.ai;

import ru.arc.configs.Config;
import ru.arc.util.Common;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Slf4j
public class GPTEntity {

    Config config;
    Cache<UUID, ChatHistory> chatHistoryCache;
    String archetype;
    String id;
    private static final HttpClient client = HttpClient.newHttpClient();

    public GPTEntity(Config config, String archetype, String id, boolean useHistory) {
        this.config = config;
        this.archetype = archetype;
        if (useHistory) {
            chatHistoryCache = CacheBuilder.newBuilder()
                    .expireAfterAccess(config.integer("ai." + archetype + ".cache-ttl-minutes", 10), TimeUnit.MINUTES)
                    .maximumSize(config.integer("ai." + archetype + ".max-history-length", 100))
                    .build();
        }
    }

    @SneakyThrows
    public CompletableFuture<Optional<ModerResponse>> getModerResponse(String message) {
        String apiKey = config.string("api-key", "none");
        if (apiKey.equals("none")) {
            log.error("API key is not set");
            return CompletableFuture.completedFuture(Optional.empty());
        }
        List<Map<String, String>> playerMessage = new ArrayList<>();
        playerMessage.add(Map.of("role", "user", "content", message));

        String okMarker = config.string("ai." + archetype + ".moderation-ok-marker", "OK");
        String badMarker = config.string("ai." + archetype + ".moderation-bad-marker", "BAD");
        String commentMarker = config.string("ai." + archetype + ".moderation-comment-marker", "COMMENT: ");

        return CompletableFuture.supplyAsync(() -> {
            Map<String, Object> map = new HashMap<>();

            map.put("model", config.string("model", config.string("ai." + archetype + ".model", "gpt-4o-mini")));

            StringBuilder systemMessageBuilder = new StringBuilder();
            for (String s : config.stringList("ai.common-system-messages", List.of())) {
                systemMessageBuilder.append(s).append("\n");
            }
            for (String s : config.stringList("ai." + archetype + ".system", List.of())) {
                systemMessageBuilder.append(s).append("\n");
            }

            List<Map<String, String>> messages = new ArrayList<>();
            messages.add(Map.of(
                    "role", "system",
                    "content", systemMessageBuilder.toString())
            );
            messages.addAll(playerMessage);

            map.put("messages", messages);
            map.put("max_tokens", config.integer("ai." + archetype + ".max-tokens", 250));
            map.put("temperature", config.real("ai." + archetype + ".temperature", 0.7));
            String json = Common.gson.toJson(map);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.proxyapi.ru/openai/v1/chat/completions"))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            try {
                var resp = client.send(request, HttpResponse.BodyHandlers.ofString());
                JsonResponse jsonResponse = Common.gson.fromJson(resp.body(), JsonResponse.class);
                String gptResponse = jsonResponse.choices.getFirst().message.content;

                ModerationResponse response = gptResponse.contains(okMarker)
                        ? ModerationResponse.OK :
                        gptResponse.contains(badMarker) ? ModerationResponse.BAD : null;

                String comment = gptResponse.contains(commentMarker)
                        ? gptResponse.substring(gptResponse.indexOf(commentMarker) + commentMarker.length()).trim()
                        : null;
                return Optional.of(new ModerResponse(response, comment));
            } catch (Exception e) {
                log.error("Failed to get response from GPT", e);
                return Optional.empty();
            }
        });
    }

    @SneakyThrows
    public CompletableFuture<Optional<String>> getResponse(UUID playerUuid, String playerName, String message) {
        if (config.string("api-key", "none").equals("none")) {
            log.error("API key is not set");
            return CompletableFuture.completedFuture(Optional.empty());
        }

        List<Map<String, String>> messages = new ArrayList<>();

        StringBuilder systemMessageBuilder = new StringBuilder();
        for (String s : config.stringList("common-system-messages", List.of())) {
            systemMessageBuilder.append(s.replace("%player_name%", playerName)).append("\n");
        }
        for (String s : config.stringList("ai." + archetype + ".system", List.of())) {
            systemMessageBuilder.append(s.replace("%player_name%", playerName)).append("\n");
        }

        messages.add(Map.of(
                "role", "system",
                "content", systemMessageBuilder.toString())
        );

        ChatHistory chatHistory;
        if (chatHistoryCache != null) {
            chatHistory = chatHistoryCache.get(playerUuid, () ->
                    new ChatHistory(playerUuid, config.integer("ai." + archetype + ".max-history-length", 100)));
            chatHistory.addPlayerMessage(message);
            chatHistory.entries().forEach(entry ->
                    messages.add(Map.of("role", entry.isPlayer() ? "user" : "assistant", "content", entry.text())));
        } else {
            chatHistory = null;
            messages.add(Map.of("role", "user", "content", message));
        }


        return CompletableFuture.supplyAsync(() -> {
            Map<String, Object> map = new HashMap<>();

            map.put("model", config.string("model", config.string("ai." + archetype + ".model", "gpt-4o-mini")));
            map.put("messages", messages);
            map.put("max_tokens", config.integer("ai." + archetype + ".max-tokens", 250));
            map.put("temperature", config.real("ai." + archetype + ".temperature", 0.7));
            String json = Common.gson.toJson(map);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.proxyapi.ru/openai/v1/chat/completions"))
                    .header("Authorization", "Bearer " + config.string("api-key"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            try {
                var resp = client.send(request, HttpResponse.BodyHandlers.ofString());
                JsonResponse jsonResponse = Common.gson.fromJson(resp.body(), JsonResponse.class);
                String gptResponse = jsonResponse.choices.getFirst().message.content;
                if (chatHistory != null) chatHistory.addBotMessage(gptResponse);
                return Optional.of(gptResponse);
            } catch (Exception e) {
                log.error("Failed to get response from GPT", e);
                return Optional.empty();
            }
        });
    }
}


