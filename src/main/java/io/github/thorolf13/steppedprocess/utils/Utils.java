package io.github.thorolf13.steppedprocess.utils;

public class Utils {

    public static <T> T defaultValue(T value, T defaultValue) {
        return value == null ? defaultValue : value;
    }
}
