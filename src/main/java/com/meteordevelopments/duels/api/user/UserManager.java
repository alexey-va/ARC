package com.meteordevelopments.duels.api.user;

import java.util.UUID;

public interface UserManager {
    User get(UUID uuid);
}

