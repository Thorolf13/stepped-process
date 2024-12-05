package io.github.thorolf13.steppedprocess.utils;

import org.mockito.Answers;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.time.*;
import java.util.function.Supplier;

public class MockTime {
    public MockTime() {
    }

    public static void excecuteAtTime(LocalDateTime at, Runnable funct) {
        execute(at, () -> {
            funct.run();
            return null;
        });
    }

    public static <T> T excecuteAtTime(LocalDateTime at, Supplier<T> funct) {
        return execute(at, funct);
    }

    public static <T> T execute(LocalDateTime at, Supplier<T> func) {
        Clock fixedClock = Clock.fixed(at.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);

        MockedStatic<Clock> clockMock = Mockito.mockStatic(Clock.class, Answers.CALLS_REAL_METHODS);

        T result;
        try {
            clockMock.when(Clock::systemDefaultZone).thenReturn(fixedClock);
            result = func.get();
            clockMock.close();
        } catch (Throwable err) {
            if (clockMock != null) {
                try {
                    clockMock.close();
                } catch (Throwable err2) {
                    err.addSuppressed(err2);
                }
            }

            throw err;
        }

        return result;
    }
}
