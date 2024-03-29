package arc.arc.board.guis;

import net.kyori.adventure.text.Component;

public interface Inputable {

    public void setParameter(int n, String s);

    public void proceed();

    public boolean satisfy(String input, int id);

    public Component denyMessage(String input, int id);

    public Component startMessage(int id);
}
