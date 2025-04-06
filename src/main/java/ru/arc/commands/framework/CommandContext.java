package ru.arc.commands.framework;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

import static ru.arc.commands.framework.ArgType.*;

@Slf4j
@Data
public class CommandContext {

    String[] args;
    List<String> trimmedArgs = new ArrayList<>();
    Map<String, Object> map = new HashMap<>();
    List<ParseError> parseErrorList = new ArrayList<>();

    private CommandContext() {
    }

    @SuppressWarnings("unchecked")
    public <T> T get(String key) {
        return (T) map.get(key);
    }

    public static CommandContext of(String[] args, List<Par> expected) {
        CommandContext context = new CommandContext();
        context.args = args;
        Map<String, Par> expectedMap = new HashMap<>();
        for (Par par : expected) expectedMap.put(par.getName(), par);

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("-")) {
                context.trimmedArgs.add(arg);
                continue;
            }
            arg = arg.substring(1);
            String[] split = arg.split(":");

            String next;

            boolean withSemicolon = true;
            if (split.length == 1) {
                withSemicolon = false;
                next = args.length > i + 1 ? args[i + 1] : null;
            } else {
                arg = split[0];
                next = split[1];
            }


            if (next == null || next.startsWith("-")) {
                context.parseErrorList.add(new ParseError("Invalid argument", arg, i));
                //log.warn("Invalid argument in: {}, at arg: {}", Arrays.toString(args), i);
                continue;
            }
            if (expectedMap.containsKey(arg)) {
                Par par = expectedMap.get(arg);
                ArgType argType = par.getArgType();
                if (argType.isInstance(next)) context.map.put(arg, argType.cast(next));
                else {
                    context.parseErrorList.add(
                            new ParseError("Invalid argument type. Expected " + par.getName(), arg, i)
                    );
                    //log.warn("Invalid argument type in: {}, at arg: {}", Arrays.toString(args), i);
                    context.map.put(arg, par.getDefaultValue());
                }
            } else {
                Object guess = guessAndCast(next);
                //log.warn("Unknown argument in: {}, at arg: {}", Arrays.toString(args), i);
                context.map.put(arg, guess);
            }
            if (!withSemicolon) i++;
        }
        for (Par par : expected) {
            if (!context.map.containsKey(par.getName())) {
                context.map.put(par.getName(), par.getDefaultValue());
            }
        }
        return context;
    }

    @Data
    @AllArgsConstructor
    public static class ParseError {
        String message;
        String arg;
        int index;
    }


}
