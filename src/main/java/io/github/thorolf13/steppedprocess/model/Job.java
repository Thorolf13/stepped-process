package io.github.thorolf13.steppedprocess.model;

import java.time.LocalDateTime;

public interface Job{

    String getUuid();
    String getTypeCode();
    String getKey();
    String getData();
    Status getStatus();
    String getStep();
    String getMessage();
    Integer getRetry();
    LocalDateTime getNextExecution();

    void setData(String data);
    void setStatus(Status status);
    void setStep(String step);
    void setMessage(String message);
    void setRetry(Integer retry);
    void setNextExecution(LocalDateTime nextExecution);


//    default String toString() {
//        return getTypeCode()+"-"+getKey()+"-"+getUuid();
//    }
}
