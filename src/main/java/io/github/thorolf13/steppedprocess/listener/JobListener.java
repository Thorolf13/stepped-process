package io.github.thorolf13.steppedprocess.listener;

import io.github.thorolf13.steppedprocess.model.JobContext;public class JobListener<T> {
    public void onJobStart(JobContext<T> jobContext){}

    public void onJobRetry(JobContext<T> jobContext){}

    public void onJobResume(JobContext<T> jobContext){}

    public void onJobSuccess(JobContext<T> jobContext){}

    public void onJobError(JobContext<T> jobContext, Throwable t){}

    public void onStepStart(JobContext<T> jobContext){}

    public void onStepSuccess(JobContext<T> jobContext){}

    public void onStepError(JobContext<T> jobContext, Throwable t){}
}
