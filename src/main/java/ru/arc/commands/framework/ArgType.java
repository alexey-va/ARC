package ru.arc.commands.framework;

import lombok.Getter;

@Getter
public enum ArgType {
    STRING(String.class, "text"),
    INTEGER(Integer.class, 0),
    DOUBLE(Double.class, 0.0),
    BOOLEAN(Boolean.class, false);

    final Class<?> clazz;
    Object defaultValue;

    ArgType(Class<?> clazz, Object defaultValue) {
        this.clazz = clazz;
        this.defaultValue = defaultValue;
    }

    public boolean isInstance(String unparse) {
        return switch (this) {
            case STRING -> true;
            case INTEGER -> unparse.matches("-?\\d+");
            case DOUBLE -> unparse.matches("-?\\d+(\\.\\d+)?");
            case BOOLEAN -> unparse.equalsIgnoreCase("true") || unparse.equalsIgnoreCase("false");
        };
    }

    public static Object guessAndCast(String unparse) {
        if (unparse.matches("-?\\d+")) return Integer.valueOf(unparse);
        if (unparse.matches("-?\\d+(\\.\\d+)?")) return Double.valueOf(unparse);
        if (unparse.equalsIgnoreCase("true") || unparse.equalsIgnoreCase("false")) return Boolean.valueOf(unparse);
        return unparse;
    }

    @SuppressWarnings("unchecked")
    public <T> T cast(Object object) {
        return switch (this) {
            case STRING -> (T) object.toString();
            case INTEGER -> (T) Integer.valueOf(object.toString());
            case DOUBLE -> (T) Double.valueOf(object.toString());
            case BOOLEAN -> (T) Boolean.valueOf(object.toString());
        };
    }

}
