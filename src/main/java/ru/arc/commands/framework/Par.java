package ru.arc.commands.framework;

import lombok.*;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class Par {

    String name;
    ArgType argType;
    Object defaultValue;

    public static Par of(String name, ArgType argType, Object defaultValue) {
        return new Par(name, argType, defaultValue);
    }
}
