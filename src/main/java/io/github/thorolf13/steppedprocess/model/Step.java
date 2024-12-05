package io.github.thorolf13.steppedprocess.model;

import io.github.thorolf13.steppedprocess.function.CheckedFunction;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.time.Duration;
import java.util.function.Predicate;

@Getter
@Builder
@AllArgsConstructor
public class Step<T> {
    private String code;
    private CheckedFunction<JobContext<T>, String> action;
    @Builder.Default
    private Boolean isStart = false;
    @Builder.Default
    private Predicate<JobContext<T>> condition = jobContext -> true;
    @Builder.Default
    private Integer maxRetry = 0;
    @Builder.Default
    private Duration retryDelay = Duration.ZERO;
}
