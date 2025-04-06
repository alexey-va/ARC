package ru.arc.ai;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.bukkit.Location;

import java.util.UUID;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class Conversation {

    UUID playerUuid;
    Location location;
    double radius;
    String gptId;
    String archetype;
    long lastMessageTime;
    long lifeTime;
    String talkerName;
    Integer npcId;
    String endMessage;
    @Builder.Default
    boolean privateConversation = true;

}
