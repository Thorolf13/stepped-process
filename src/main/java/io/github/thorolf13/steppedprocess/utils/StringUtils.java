package io.github.thorolf13.steppedprocess.utils;

public class StringUtils {
    public static String defaultIfBlank(String value, String defaultValue) {
        return org.apache.commons.lang3.StringUtils.isBlank(value) ? defaultValue : value;
    }
}
