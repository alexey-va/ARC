package arc.arc.ai;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import arc.arc.configs.Config;
import arc.arc.util.Common;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class GPTEntity {

    Config config;
    Cache<UUID, ChatHistory> chatHistoryCache;
    String archetype;
    String id;
    private static final Gson gson = Common.gson;
    private static final HttpClient client = HttpClient.newHttpClient();

    public GPTEntity(Config config, String archetype, String id) {
        this.config = config;
        this.archetype = archetype;
        chatHistoryCache = CacheBuilder.newBuilder()
                .expireAfterAccess(config.integer("archetypes." + archetype + ".cache-ttl-minutes", 10), TimeUnit.MINUTES)
                .maximumSize(config.integer("archetypes." + archetype + ".max-history-length", 100))
                .build();
    }

    @SneakyThrows
    public CompletableFuture<Optional<String>> getResponse(UUID playerUuid, String playerName, String message) {
        if (config.string("api-key", "null").equals("null")) {
            log.error("API key is not set");
            return CompletableFuture.completedFuture(Optional.empty());
        }
        ChatHistory chatHistory = chatHistoryCache.get(playerUuid, () ->
                new ChatHistory(playerUuid, config.integer("archetypes." + archetype + ".max-history-length", 100)));
        chatHistory.addPlayerMessage(message);

        return CompletableFuture.supplyAsync(() -> {
            Map<String, Object> map = new HashMap<>();
            List<Map<String, String>> messages = new ArrayList<>();

            map.put("model", config.string("model", config.string("archetypes." + archetype + ".model", "gpt-4o-mini")));

            StringBuilder systemMessageBuilder = new StringBuilder();
            for (String s : config.stringList("common-system-messages", List.of())) {
                systemMessageBuilder.append(s.replace("%player_name%", playerName)).append("\n");
            }
            for (String s : config.stringList("archetypes." + archetype + ".system", List.of())) {
                systemMessageBuilder.append(s.replace("%player_name%", playerName)).append("\n");
            }

            messages.add(Map.of(
                    "role", "system",
                    "content", systemMessageBuilder.toString())
            );

            chatHistory.entries().forEach(entry ->
                    messages.add(Map.of("role", entry.isPlayer() ? "user" : "assistant", "content", entry.text())));

            map.put("messages", messages);
            map.put("max_tokens", config.integer("archetypes." + archetype + ".max-tokens", 250));
            map.put("temperature", config.real("archetypes." + archetype + ".temperature", 0.7));
            String json = gson.toJson(map);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.proxyapi.ru/openai/v1/chat/completions"))
                    .header("Authorization", "Bearer " + config.string("api-key"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            try {
                var resp = client.send(request, HttpResponse.BodyHandlers.ofString());
                JsonResponse jsonResponse = gson.fromJson(resp.body(), JsonResponse.class);
                //log.info("Got response from GPT: {}", resp.body());
                String gptResponse = jsonResponse.choices.getFirst().message.content;
                chatHistory.addBotMessage(gptResponse);
                return Optional.of(gptResponse);
            } catch (Exception e) {
                log.error("Failed to get response from GPT", e);
                return Optional.empty();
            }
        });
    }
}


class JsonResponse {
    public String id;
    public String object;
    public long created;
    public String model;
    public List<Choice> choices;
    public Usage usage;

    @SerializedName("system_fingerprint")
    public String systemFingerprint;
}

class Choice {
    public int index;
    public Message message;
    public Object logprobs; // Use specific type if needed

    @SerializedName("finish_reason")
    public String finishReason;
}

class Message {
    public String role;
    public String content;
}

class Usage {
    @SerializedName("prompt_tokens")
    public int promptTokens;

    @SerializedName("completion_tokens")
    public int completionTokens;

    @SerializedName("total_tokens")
    public int totalTokens;
}
