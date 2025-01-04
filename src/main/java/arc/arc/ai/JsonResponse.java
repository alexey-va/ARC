package arc.arc.ai;

import com.google.gson.annotations.SerializedName;

import java.util.List;

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
