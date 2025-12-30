package com.meteordevelopments.duels.api.user;

public interface User {
    int getWins();

    int getLosses();

    boolean canRequest();

    void setRequests(boolean accept);

    void setWins(int wins);

    void setLosses(int losses);
}

