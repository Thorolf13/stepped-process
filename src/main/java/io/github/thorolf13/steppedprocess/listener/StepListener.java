package io.github.thorolf13.steppedprocess.listener;

import io.github.thorolf13.steppedprocess.model.JobContext;

public class StepListener<T>{
    public void onStepStart(JobContext<T> context){}

    public void onStepSuccess(JobContext<T> context){}

    public void onStepError(JobContext<T> context, Throwable t){}
}
