package io.github.thorolf13.steppedprocess.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@AllArgsConstructor
@Builder
public class Job{

    private String uuid;
    private String typeCode;
    private String key;
    @Setter
    private String data;
    @Setter
    private Status status;
    @Setter
    private String step;
    @Setter
    private String message;
    @Setter
    private Integer retry;
    @Setter
    private LocalDateTime nextExecution;

    public enum Status {
        PENDING,
        RUNNING,
        WAITING,
        RESUMING,
        SUCCESS,
        ERROR,
        CANCELED
    }

    @Override
    public String toString() {
        return typeCode+"-"+key+"-"+uuid;
    }
}
