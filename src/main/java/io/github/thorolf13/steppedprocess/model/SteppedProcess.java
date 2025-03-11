package io.github.thorolf13.steppedprocess.model;

import io.github.thorolf13.steppedprocess.exception.ProcessIllegalStateException;
import io.github.thorolf13.steppedprocess.function.CheckedFunction;
import io.github.thorolf13.steppedprocess.listener.JobListener;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Function;
import java.util.stream.Collectors;

@Getter
public class SteppedProcess<T> {
    private final String typeCode;
    private final CheckedFunction<String, T> deserializer;
    private final CheckedFunction<T, String> serializer;
    private final Map<String, Step<T>> steps;
    private final DuplicatePolicy duplicatePolicy;
    private Function<Runnable, ScheduledFuture<?>> scheduleSupplier;

    private final JobListener<T> jobListener;

    public Optional<Step<T>> getStepByCode(String code){
        return Optional.ofNullable(steps.get(code));
    }

    public Step<T> getStartStep(){
        return steps.values().stream()
            .filter(Step::getIsStart)
            .findFirst()
            .orElseThrow(() -> new ProcessIllegalStateException("Process must have a start step", "PROCESS_START_STEP_MANDATORY"));
    }

    //############################################
    // enum
    //############################################

    public enum DuplicatePolicy {
        DENY, ALLOW, DENY_PENDING
    }


    //############################################
    // builder
    //############################################

    public static <T> Builder<T> builder() {
        return new Builder<>();
    }

    private SteppedProcess(Builder<T> builder) {
        if( builder.typeCode == null){
            throw new ProcessIllegalStateException("Process type is mandatory", "PROCESS_TYPE_MANDATORY");
        }
        if( builder.steps == null || builder.steps.isEmpty()){
            throw new ProcessIllegalStateException("Process steps are mandatory", "PROCESS_STEPS_MANDATORY");
        }
        List<Step> startSteps = builder.steps.stream()
            .filter(Step::getIsStart)
            .collect(Collectors.toList());
        if( startSteps.isEmpty()){
            throw new ProcessIllegalStateException("Process must have a start step", "PROCESS_START_STEP_MANDATORY");
        }
        if( startSteps.size() > 1){
            throw new ProcessIllegalStateException("Process must have only one start step", "PROCESS_START_STEP_UNIQUE");
        }

        if( builder.deserializer == null){
            throw new ProcessIllegalStateException("Process deserializer is mandatory", "PROCESS_DESERIALIZER_MANDATORY");
        }
        if( builder.serializer == null){
            throw new ProcessIllegalStateException("Process serializer is mandatory", "PROCESS_SERIALIZER_MANDATORY");
        }

        this.typeCode = builder.typeCode;
        this.steps = builder.steps.stream()
            .collect(Collectors.toMap(Step::getCode, Function.identity()));
        this.deserializer = builder.deserializer;
        this.serializer = builder.serializer;
        this.duplicatePolicy = builder.duplicatePolicy;
        this.scheduleSupplier = builder.scheduleSupplier;
        this.jobListener = builder.jobListener == null ? new JobListener<>() : builder.jobListener;
    }

    public static class  Builder<T> {
        private String typeCode;
        private CheckedFunction<String, T> deserializer;
        private CheckedFunction<T, String> serializer;
        private List<Step<T>> steps;
        private DuplicatePolicy duplicatePolicy = DuplicatePolicy.ALLOW;
        private Function<Runnable, ScheduledFuture<?>> scheduleSupplier;
        private JobListener<T> jobListener;

        public Builder<T> typeCode(String code) {
            this.typeCode = code;
            return this;
        }

        public Builder<T> addStep(Step<T> step) {
            if (steps == null) {
                steps = new ArrayList<>();
            }
            steps.add(step);
            return this;
        }

        public Builder<T> deserializer(CheckedFunction<String, T> deserializer) {
            this.deserializer = deserializer;
            return this;
        }

        public Builder<T> serializer(CheckedFunction<T, String> serializer) {
            this.serializer = serializer;
            return this;
        }

        public Builder<T> duplicatePolicy(DuplicatePolicy duplicatePolicy) {
            this.duplicatePolicy = duplicatePolicy;
            return this;
        }

        public Builder<T> schedule(Function<Runnable, ScheduledFuture<?>> scheduleSupplier) {
            this.scheduleSupplier = scheduleSupplier;
            return this;
        }

        public Builder<T> jobListener(JobListener jobListener) {
            this.jobListener = jobListener;
            return this;
        }

        public SteppedProcess<T> build() {
            return new SteppedProcess<T>(this);
        }
    }
}
