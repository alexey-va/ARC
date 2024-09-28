package arc.arc.ai;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import arc.arc.configs.Config;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.bukkit.entity.Player;

@Slf4j
public class GPTEntity {

    Config config;
    Cache<UUID, ChatHistory> chatHistoryCache;
    String id;
    long maxCacheSize =
    private static final Gson gson = new Gson();
    private static final HttpClient client = HttpClient.newHttpClient();

    public GPTEntity gptService(Config config, String id) {
        this.config = config;
        this.id = id;
        reload();
    }

    public void reload() {
        long cacheMinutes = config.integer("entities." + id + ".cache-ttl-minutes", 10);
        chatHistoryCache = CacheBuilder.newBuilder()
                .expireAfterAccess(cacheMinutes, TimeUnit.MINUTES)
                .maximumSize(200)
                .build();
    }

    @SneakyThrows
    public CompletableFuture<String> getResponse(UUID uuid, String message) {
        if (config.string("api-key", "null").equals("null")) {
            System.out.println("GPT API key is not set");
            return null;
        }
        ChatHistory chatHistory = chatHistoryCache.get(uuid, () ->
                new ChatHistory(uuid, config.integer("entities." + id + ".max-history-length", 100)));
        chatHistory.addPlayerMessage(message);

        return CompletableFuture.supplyAsync(() -> {
            Map<String, Object> map = new HashMap<>();
            List<Map<String, String>> messages = new ArrayList<>();

            map.put("model", config.string("model", config.string("entities." + id + ".model", "gpt-4o-mini")));

            StringBuilder systemMessageBuilder = new StringBuilder();
            systemMessageBuilder.append(config.string("system-message", ""));
            messages.add(Map.of(
                    "role", "system",
                    "content", systemMessageBuilder.toString())
            );

            messages.add(Map.of("role", "user", "content", "История последних собщений чата: " + chatHistory.asString(true)));
            messages.add(Map.of("role", "user", "content", message));
            map.put("messages", messages);
            map.put("max_tokens", config.integer("jippity-max-tokens", 150));
            map.put("temperature", config.realNumber("jippity-temperature", 0.7));
            String json = gson.toJson(map);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.proxyapi.ru/openai/v1/chat/completions"))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            var resp = client.send(request, HttpResponse.BodyHandlers.ofString());
            JsonResponse jsonResponse = gson.fromJson(resp.body(), JsonResponse.class);
            if (messageHistories.size() > config.integer("jippity-history-size", 30)) {
                messageHistories.poll();
            }
            String mes = jsonResponse.choices.get(0).message.content;
            messageHistories.add(new MessageHistory("user", message));
            messageHistories.add(new MessageHistory("assistant", mes));
            return Utils.legacy(config.string("jippity-prefix", "&6%name% &7» ")
                    .replace("%name%", config.string("jippity-name", "&6ИИ")) + mes);
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
