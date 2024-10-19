package arc.arc.commands.framework;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

import java.util.List;

@Slf4j
class CommandContextTest {

    @Test
    void test() {
        String[] args = new String[]{"-a:true", "-b:2.13", "-c:lolkek", "-d", "asd"};
        List<Par> pars = List.of(
                Par.of("a", ArgType.BOOLEAN, false),
                Par.of("b", ArgType.DOUBLE, 0.0),
                Par.of("c", ArgType.STRING, ""),
                Par.of("d", ArgType.INTEGER, 0)
        );
        CommandContext context = CommandContext.of(args, pars);
        System.out.println(context);
    }

}