package ru.arc.ai

import com.google.gson.annotations.SerializedName

internal class JsonResponse {
    @JvmField var id: String? = null
    @JvmField var `object`: String? = null
    @JvmField var created: Long = 0
    @JvmField var model: String? = null
    @JvmField var choices: List<Choice>? = null
    @JvmField var usage: Usage? = null

    @SerializedName("system_fingerprint")
    @JvmField var systemFingerprint: String? = null
}

internal class Choice {
    @JvmField var index: Int = 0
    @JvmField var message: Message? = null
    @JvmField var logprobs: Any? = null

    @SerializedName("finish_reason")
    @JvmField var finishReason: String? = null
}

internal class Message {
    @JvmField var role: String? = null
    @JvmField var content: String? = null
}

internal class Usage {
    @SerializedName("prompt_tokens")
    @JvmField var promptTokens: Int = 0

    @SerializedName("completion_tokens")
    @JvmField var completionTokens: Int = 0

    @SerializedName("total_tokens")
    @JvmField var totalTokens: Int = 0
}
