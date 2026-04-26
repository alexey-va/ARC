package ru.arc.board.guis;

import net.kyori.adventure.text.Component;

public interface Inputable {

    void setParameter(int n, String s);

    void proceed();

    boolean satisfy(String input, int id);

    Component denyMessage(String input, int id);

    Component startMessage(int id);
}
