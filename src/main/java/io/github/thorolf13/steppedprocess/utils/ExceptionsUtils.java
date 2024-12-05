package io.github.thorolf13.steppedprocess.utils;

import java.util.Optional;

import static io.github.thorolf13.steppedprocess.utils.StringUtils.defaultIfBlank;

public class ExceptionsUtils {
    public static Optional<Throwable> getFirstCause(Throwable error) {
        if (error == null || error.getCause() == null) {
            return Optional.empty();
        }

        Throwable cause = error;

        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return Optional.of(cause);
    }

    public static String asString(Throwable t) {
        return defaultIfBlank(t.getMessage(), t.toString());
    }

    public static String buildErrorMesage(Throwable t) {
        return asString(t)
            + getFirstCause(t).map(cause -> "\ncaused by : " + asString(cause)).orElse("");
    }
}
