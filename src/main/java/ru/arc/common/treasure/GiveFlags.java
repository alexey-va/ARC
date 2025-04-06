package ru.arc.common.treasure;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class GiveFlags {
    public static final GiveFlags DEFAULT = GiveFlags.builder().build();

    @Builder.Default
    boolean sendMessage = true;
    @Builder.Default
    boolean sendGlobalMessage = true;

    @Builder.Default
    boolean entireServerAnnounce = false;
    @Builder.Default
    boolean worldAnnounce = true;
    @Builder.Default
    double radiusAnnounce = 100;
}
