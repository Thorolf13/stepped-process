package io.github.thorolf13.steppedprocess.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
@Builder
public class JobContext<T> {
    private final String jobUuid;
    private final String jobKey;
    private final String step;
    private final Integer nbRetry;

    private T data;
}
