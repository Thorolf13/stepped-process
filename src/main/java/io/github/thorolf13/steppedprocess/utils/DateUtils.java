package io.github.thorolf13.steppedprocess.utils;

import java.time.LocalDateTime;

public class DateUtils {

    public static boolean isBeforeOrEqual(LocalDateTime value, LocalDateTime compare){
        return !value.isAfter(compare); // = isBefore || isEqual
    }
}
