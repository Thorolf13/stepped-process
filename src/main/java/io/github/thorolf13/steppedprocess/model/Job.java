package io.github.thorolf13.steppedprocess.model;

import java.time.LocalDateTime;

public abstract class Job{

    public abstract String getUuid();
    public abstract String getTypeCode();
    public abstract String getKey();
    public abstract String getData();
    public abstract Status getStatus();
    public abstract String getStep();
    public abstract String getMessage();
    public abstract Integer getRetry();
    public abstract LocalDateTime getNextExecution();

    public abstract void setData(String data);
    public abstract void setStatus(Status status);
    public abstract void setStep(String step);
    public abstract void setMessage(String message);
    public abstract void setRetry(Integer retry);
    public abstract void setNextExecution(LocalDateTime nextExecution);

    public String toString() {
        return getTypeCode()+"-"+getKey()+"-"+getUuid();
    }
}
