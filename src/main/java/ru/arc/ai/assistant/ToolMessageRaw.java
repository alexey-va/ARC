package ru.arc.ai.assistant;

import com.google.gson.JsonElement;
import lombok.Builder;
import lombok.Data;

import java.util.UUID;

@Data
@Builder
public class ToolMessageRaw {
    public UUID uuid;
    public String toolName;
    public JsonElement toolDto;
    public long timestamp;
}
