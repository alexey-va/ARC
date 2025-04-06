package ru.arc.hooks.lands;

import ru.arc.common.ServerLocation;
import lombok.*;

import java.util.UUID;

@Getter @Setter @ToString
@NoArgsConstructor
@AllArgsConstructor
public class LandsRequest {

    UUID uuid;
    UUID playerUuid;
    ServerLocation serverLocation = null;


}
