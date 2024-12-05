package io.github.thorolf13.steppedprocess;

import io.github.thorolf13.steppedprocess.exception.ProcessDuplicateJobException;
import io.github.thorolf13.steppedprocess.model.Job;
import io.github.thorolf13.steppedprocess.model.Step;
import io.github.thorolf13.steppedprocess.model.SteppedProcess;
import io.github.thorolf13.steppedprocess.provided.JobRepository;
import io.github.thorolf13.steppedprocess.utils.MockTime;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.support.CronTrigger;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class SteppedProcessTest {

    @InjectMocks
    private SteppedProcessService steppedProcessService;

    @Mock
    private JobRepository jobRepository;

    @Mock
    private AService aService;


    @BeforeEach
    public void setUp() {
        mockJobRepository();
    }

    @Test
    public void test_success() throws ProcessDuplicateJobException {
        SteppedProcess<Data> steppedProcess = SteppedProcess.<Data>builder()
            .typeCode("PROCESS_1")
            .serializer(Data::serialize)
            .deserializer(Data::deserialize)
            .addStep(Step.<Data>builder()
                .code("STEP_1")
                .isStart(true)
                .action(context -> {
                    context.getData().waypoints.add(context.getStep());
                    return "STEP_2";
                })
                .build()
            )
            .addStep(Step.<Data>builder()
                .code("STEP_2")
                .action(context -> {
                    context.getData().waypoints.add(context.getStep());
                    return null;
                })
                .build()
            )
            .onSuccess(context->aService.success("SUCCESS"))
            .onError((context, err)->aService.error("ERROR", err))
            .build();

        steppedProcessService.registerProcess(steppedProcess);
        Job job = steppedProcessService.createJob("PROCESS_1", "JOB_1", new Data());
        steppedProcessService.processingJob("uuid-1");

        assertThat(job.getStatus()).isEqualTo(Job.Status.SUCCESS);
        assertThat(job.getData()).isEqualTo("[STEP_1,STEP_2]");

        verify(aService, times(1)).success(any());
        verify(aService, never()).error(any(), any());
    }

    @Test
    public void test_error() throws ProcessDuplicateJobException {
        SteppedProcess<Data> steppedProcess = SteppedProcess.<Data>builder()
            .typeCode("PROCESS_1")
            .serializer(Data::serialize)
            .deserializer(Data::deserialize)
            .addStep(Step.<Data>builder()
                .code("STEP_1")
                .isStart(true)
                .action(context -> {
                    context.getData().waypoints.add(context.getStep());
                    return "STEP_2";
                })
                .build()
            )
            .addStep(Step.<Data>builder()
                .code("STEP_2")
                .action(context -> {
                    throw new RuntimeException("ERROR", new RuntimeException("CAUSE1", new RuntimeException("CAUSE2")));
                })
                .build()
            )
            .onSuccess(context->aService.success("SUCCESS"))
            .onError((context, err)->aService.error("ERROR", err))
            .build();

        steppedProcessService.registerProcess(steppedProcess);
        Job job = steppedProcessService.createJob("PROCESS_1", "JOB_1", new Data());
        steppedProcessService.processingJob("uuid-1");


        assertThat(job.getStatus()).isEqualTo(Job.Status.ERROR);
        assertThat(job.getData()).isEqualTo("[STEP_1]");
        assertThat(job.getMessage()).isEqualTo("ERROR\ncaused by : CAUSE2");

        verify(aService, never()).success(any());
        verify(aService, times(1)).error(any(), any());
    }

    @Test
    public void test_retry() throws ProcessDuplicateJobException {
        AtomicBoolean first = new AtomicBoolean(true);

        SteppedProcess<Data> steppedProcess = SteppedProcess.<Data>builder()
            .typeCode("PROCESS_1")
            .serializer(Data::serialize)
            .deserializer(Data::deserialize)
            .addStep(Step.<Data>builder()
                .code("STEP_1")
                .isStart(true)
                .action(context -> {
                    context.getData().waypoints.add(context.getStep());
                    return "STEP_2";
                })
                .build()
            )
            .addStep(Step.<Data>builder()
                .code("STEP_2")
                .action(context -> {
                    if(first.get()){
                        first.set(false);
                        throw new RuntimeException("ERROR");
                    } else {
                        context.getData().waypoints.add(context.getStep());
                        return null;
                    }
                })
                .maxRetry(1)
                .build()
            )
            .onSuccess(context->aService.success("SUCCESS"))
            .onError((context, err)->aService.error("ERROR", err))
            .build();


        steppedProcessService.registerProcess(steppedProcess);
        Job job = steppedProcessService.createJob("PROCESS_1", "JOB_1", new Data());
        steppedProcessService.processingJob("uuid-1");

        assertThat(job.getStatus()).isEqualTo(Job.Status.RESUMING);
        assertThat(job.getData()).isEqualTo("[STEP_1]");
        assertThat(job.getMessage()).isEqualTo("ERROR");
        verify(aService, never()).success(any());
        verify(aService, never()).error(any(), any());

        steppedProcessService.processingJob("uuid-1");

        assertThat(job.getStatus()).isEqualTo(Job.Status.SUCCESS);
        assertThat(job.getData()).isEqualTo("[STEP_1,STEP_2]");
        assertThat(job.getMessage()).isEqualTo(null);
        verify(aService, times(1)).success(any());
        verify(aService, never()).error(any(), any());
    }

    @Test
    public void test_retrydelay() throws ProcessDuplicateJobException {
        AtomicBoolean first = new AtomicBoolean(true);

        SteppedProcess<Data> steppedProcess = SteppedProcess.<Data>builder()
            .typeCode("PROCESS_1")
            .serializer(Data::serialize)
            .deserializer(Data::deserialize)
            .addStep(Step.<Data>builder()
                .code("STEP_1")
                .isStart(true)
                .action(context -> {
                    context.getData().waypoints.add(context.getStep());
                    return "STEP_2";
                })
                .build()
            )
            .addStep(Step.<Data>builder()
                .code("STEP_2")
                .action(context -> {
                    if(first.get()){
                        first.set(false);
                        throw new RuntimeException("ERROR");
                    } else {
                        context.getData().waypoints.add(context.getStep());
                        return null;
                    }
                })
                .maxRetry(1)
                .retryDelay(Duration.of(1, ChronoUnit.HOURS))
                .build()
            )
            .onSuccess(context->aService.success("SUCCESS"))
            .onError((context, err)->aService.error("ERROR", err))
            .build();


        steppedProcessService.registerProcess(steppedProcess);
        Job job = steppedProcessService.createJob("PROCESS_1", "JOB_1", new Data());

        //error -> resuming
        MockTime.excecuteAtTime(
            LocalDateTime.of(2021, 1, 1, 10, 0),
            () -> steppedProcessService.processingJob("uuid-1")
        );

        assertThat(job.getStatus()).isEqualTo(Job.Status.RESUMING);
        assertThat(job.getData()).isEqualTo("[STEP_1]");
        assertThat(job.getMessage()).isEqualTo("ERROR");
        verify(aService, never()).success(any());
        verify(aService, never()).error(any(), any());

        //retry before delay
        MockTime.excecuteAtTime(
            LocalDateTime.of(2021, 1, 1, 10, 20),
            () -> steppedProcessService.processingJob("uuid-1")
        );
        MockTime.excecuteAtTime(
            LocalDateTime.of(2021, 1, 1, 10, 40),
            () -> steppedProcessService.processingJob("uuid-1")
        );
        //nothing should happen
        assertThat(job.getStatus()).isEqualTo(Job.Status.RESUMING);
        assertThat(job.getData()).isEqualTo("[STEP_1]");
        assertThat(job.getMessage()).isEqualTo("ERROR");
        verify(aService, never()).success(any());
        verify(aService, never()).error(any(), any());

        //retry after delay
        MockTime.excecuteAtTime(
            LocalDateTime.of(2021, 1, 1, 11, 0),
            () -> steppedProcessService.processingJob("uuid-1")
        );

        assertThat(job.getStatus()).isEqualTo(Job.Status.SUCCESS);
        assertThat(job.getData()).isEqualTo("[STEP_1,STEP_2]");
        assertThat(job.getMessage()).isEqualTo(null);
        verify(aService, times(1)).success(any());
        verify(aService, never()).error(any(), any());
    }

    @Test
    public void test_condition_success() throws ProcessDuplicateJobException {
        AtomicBoolean first = new AtomicBoolean(true);

        SteppedProcess<Data> steppedProcess = SteppedProcess.<Data>builder()
            .typeCode("PROCESS_1")
            .serializer(Data::serialize)
            .deserializer(Data::deserialize)
            .addStep(Step.<Data>builder()
                .code("STEP_1")
                .isStart(true)
                .action(context -> {
                    context.getData().waypoints.add(context.getStep());
                    return "STEP_2";
                })
                .build()
            )
            .addStep(Step.<Data>builder()
                .code("STEP_2")
                .condition(context -> {
                    if(first.get()){
                        first.set(false);
                        return false;
                    } else {
                        return true;
                    }
                })
                .action(context -> {
                    context.getData().waypoints.add(context.getStep());
                    return null;
                })
                .maxRetry(1)
                .build()
            )
            .onSuccess(context->aService.success("SUCCESS"))
            .onError((context, err)->aService.error("ERROR", err))
            .build();

        steppedProcessService.registerProcess(steppedProcess);
        Job job = steppedProcessService.createJob("PROCESS_1", "JOB_1", new Data());
        steppedProcessService.processingJob("uuid-1");

        assertThat(job.getStatus()).isEqualTo(Job.Status.WAITING);
        assertThat(job.getData()).isEqualTo("[STEP_1]");
        assertThat(job.getMessage()).isEqualTo(null);
        verify(aService, never()).success(any());
        verify(aService, never()).error(any(), any());

        steppedProcessService.processingJob("uuid-1");

        assertThat(job.getStatus()).isEqualTo(Job.Status.SUCCESS);
        assertThat(job.getData()).isEqualTo("[STEP_1,STEP_2]");
        assertThat(job.getMessage()).isEqualTo(null);
        verify(aService, times(1)).success(any());
        verify(aService, never()).error(any(), any());
    }

    @Test
    public void test_delayedExecution() throws ProcessDuplicateJobException {
        AtomicBoolean first = new AtomicBoolean(true);

        SteppedProcess<Data> steppedProcess = SteppedProcess.<Data>builder()
            .typeCode("PROCESS_1")
            .serializer(Data::serialize)
            .deserializer(Data::deserialize)
            .addStep(Step.<Data>builder()
                .code("STEP_1")
                .isStart(true)
                .action(context -> {
                    context.getData().waypoints.add(context.getStep());
                    return "STEP_2";
                })
                .build()
            )
            .addStep(Step.<Data>builder()
                .code("STEP_2")
                .action(context -> {
                    context.getData().waypoints.add(context.getStep());
                    return null;
                })
                .maxRetry(1)
                .build()
            )
            .onSuccess(context->aService.success("SUCCESS"))
            .onError((context, err)->aService.error("ERROR", err))
            .build();

        steppedProcessService.registerProcess(steppedProcess);
        Job job = steppedProcessService.createJob("PROCESS_1", "JOB_1", new Data(), LocalDateTime.of(2021, 1, 1, 10, 0));
        MockTime.excecuteAtTime(
            LocalDateTime.of(2021, 1, 1, 9, 0),
            () -> steppedProcessService.processingJob("uuid-1")
        );

        assertThat(job.getStatus()).isEqualTo(Job.Status.PENDING);
        assertThat(job.getData()).isEqualTo("[]");
        assertThat(job.getMessage()).isEqualTo(null);
        verify(aService, never()).success(any());
        verify(aService, never()).error(any(), any());

        MockTime.excecuteAtTime(
            LocalDateTime.of(2021, 1, 1, 11, 0),
            () -> steppedProcessService.processingJob("uuid-1")
        );

        assertThat(job.getStatus()).isEqualTo(Job.Status.SUCCESS);
        assertThat(job.getData()).isEqualTo("[STEP_1,STEP_2]");
        assertThat(job.getMessage()).isEqualTo(null);
        verify(aService, times(1)).success(any());
        verify(aService, never()).error(any(), any());
    }

    @Test
    public void test_duplication_allow() {
        SteppedProcess<Data> steppedProcess = SteppedProcess.<Data>builder()
            .typeCode("PROCESS_1")
            .serializer(Data::serialize)
            .deserializer(Data::deserialize)
            .duplicatePolicy(SteppedProcess.DuplicatePolicy.ALLOW)
            .addStep(Step.<Data>builder()
                .code("STEP_1")
                .isStart(true)
                .action(context -> {
                    context.getData().waypoints.add(context.getStep());
                    return "STEP_2";
                })
                .build()
            )
            .build();

        steppedProcessService.registerProcess(steppedProcess);

        Job job = null;
        try {
            job = steppedProcessService.createJob("PROCESS_1", "JOB_1", new Data());
        } catch (ProcessDuplicateJobException e) {
            fail(e);
        }

        Job job2 = null;
        try {
            job2 = steppedProcessService.createJob("PROCESS_1", "JOB_1", new Data());
        } catch (ProcessDuplicateJobException e) {
            fail(e);
        }

        assertThat(job.getUuid()).isEqualTo("uuid-1");
        assertThat(job2.getUuid()).isEqualTo("uuid-2");
    }

    @Test
    public void test_duplication_deny() {
        SteppedProcess<Data> steppedProcess = SteppedProcess.<Data>builder()
            .typeCode("PROCESS_1")
            .serializer(Data::serialize)
            .deserializer(Data::deserialize)
            .duplicatePolicy(SteppedProcess.DuplicatePolicy.DENY)
            .addStep(Step.<Data>builder()
                .code("STEP_1")
                .isStart(true)
                .action(context -> {
                    context.getData().waypoints.add(context.getStep());
                    return "STEP_2";
                })
                .build()
            )
            .build();

        steppedProcessService.registerProcess(steppedProcess);

        Job job = null;
        try {
            job = steppedProcessService.createJob("PROCESS_1", "JOB_1", new Data());
        } catch (ProcessDuplicateJobException e) {
            fail(e);
        }
        assertThatThrownBy(() -> steppedProcessService.createJob("PROCESS_1", "JOB_1", new Data()))
            .isInstanceOf(ProcessDuplicateJobException.class)
            .hasMessage("Job already exists for process : PROCESS_1 key : JOB_1");

        Job job2 = null;
        try {
            job2 = steppedProcessService.createJob("PROCESS_1", "JOB_2", new Data());
        } catch (ProcessDuplicateJobException e) {
            fail(e);
        }

        assertThat(job.getUuid()).isEqualTo("uuid-1");
        assertThat(job2.getUuid()).isEqualTo("uuid-2");
    }

    @Test
    public void test_duplication_denyPending() {
        SteppedProcess<Data> steppedProcess = SteppedProcess.<Data>builder()
            .typeCode("PROCESS_1")
            .duplicatePolicy(SteppedProcess.DuplicatePolicy.DENY_PENDING)
            .serializer(Data::serialize)
            .deserializer(Data::deserialize)
            .addStep(Step.<Data>builder()
                .code("STEP_1")
                .isStart(true)
                .action(context -> {
                    context.getData().waypoints.add(context.getStep());
                    return "STEP_2";
                })
                .build()
            )
            .build();

        steppedProcessService.registerProcess(steppedProcess);
        Job job = null;
        try {
            job = steppedProcessService.createJob("PROCESS_1", "JOB_1", new Data());
        } catch (ProcessDuplicateJobException e) {
            fail(e);
        }

        assertThatThrownBy(() -> steppedProcessService.createJob("PROCESS_1", "JOB_1", new Data()))
            .isInstanceOf(ProcessDuplicateJobException.class)
            .hasMessage("Pending job already exists for process : PROCESS_1 key : JOB_1");

        steppedProcessService.processingJob("uuid-1");

        Job job2 = null;
        try {
            job2 = steppedProcessService.createJob("PROCESS_1", "JOB_1", new Data());
        } catch (ProcessDuplicateJobException e) {
            fail(e);
        }

        assertThat(job.getUuid()).isEqualTo("uuid-1");
        assertThat(job2.getUuid()).isEqualTo("uuid-2");
    }

    @Test
    public void test_processingJobs() throws ProcessDuplicateJobException {
        SteppedProcess<Data> steppedProcess = SteppedProcess.<Data>builder()
            .typeCode("PROCESS_1")
            .serializer(Data::serialize)
            .deserializer(Data::deserialize)
            .addStep(Step.<Data>builder()
                .code("STEP_1")
                .isStart(true)
                .action(context -> {
                    context.getData().waypoints.add(context.getStep());
                    return null;
                })
                .build()
            )
            .build();

        steppedProcessService.registerProcess(steppedProcess);
        Job job = steppedProcessService.createJob("PROCESS_1", "JOB_1", new Data());

        assertThat(job.getStatus()).isEqualTo(Job.Status.PENDING);

        steppedProcessService.processingJobs("PROCESS_1");

        assertThat(job.getStatus()).isEqualTo(Job.Status.SUCCESS);
    }

    @Test
    public void test_scheduled() throws ProcessDuplicateJobException {
        MockScheduler scheduler = new MockScheduler();

        SteppedProcess<Data> steppedProcess = SteppedProcess.<Data>builder()
            .typeCode("PROCESS_1")
            .schedule(processing -> scheduler.schedule(processing, new CronTrigger("0 0 10 * * *")))
            .serializer(Data::serialize)
            .deserializer(Data::deserialize)
            .addStep(Step.<Data>builder()
                .code("STEP_1")
                .isStart(true)
                .action(context -> {
                    context.getData().waypoints.add(context.getStep());
                    return null;
                })
                .build()
            )
            .build();

        steppedProcessService.registerProcess(steppedProcess);
        Job job = steppedProcessService.createJob("PROCESS_1", "JOB_1", new Data());

        assertThat(job.getStatus()).isEqualTo(Job.Status.PENDING);

        scheduler.triggerTasks();

        assertThat(job.getStatus()).isEqualTo(Job.Status.SUCCESS);
    }

    @Test
    public void test_scheduled_condition() throws ProcessDuplicateJobException {
        MockScheduler scheduler = new MockScheduler();

        AtomicBoolean concordiaIsMaster = new AtomicBoolean(false);

        SteppedProcess<Data> steppedProcess = SteppedProcess.<Data>builder()
            .typeCode("PROCESS_1")
            .schedule(processing -> scheduler.schedule(
                ()-> {
                    if(concordiaIsMaster.get()){
                        processing.run();
                    }
                },
                new CronTrigger("0 0 10 * * *"))
            )
            .serializer(Data::serialize)
            .deserializer(Data::deserialize)
            .addStep(Step.<Data>builder()
                .code("STEP_1")
                .isStart(true)
                .action(context -> {
                    context.getData().waypoints.add(context.getStep());
                    return null;
                })
                .build()
            )
            .build();

        steppedProcessService.registerProcess(steppedProcess);
        Job job = steppedProcessService.createJob("PROCESS_1", "JOB_1", new Data());

        assertThat(job.getStatus()).isEqualTo(Job.Status.PENDING);

        scheduler.triggerTasks();

        assertThat(job.getStatus()).isEqualTo(Job.Status.PENDING);

        concordiaIsMaster.set(true);
        scheduler.triggerTasks();

        assertThat(job.getStatus()).isEqualTo(Job.Status.SUCCESS);
    }

    //############################################
    // utils
    //############################################

    private void mockJobRepository(){
        Map<String, Job> jobs = new HashMap<>();
        AtomicInteger jobCounter = new AtomicInteger(0);

        lenient().when(jobRepository.createJob(any(), any(), any(), any())).thenAnswer(invocation -> {
            Job job = new Job(
                "uuid-"+jobCounter.incrementAndGet(),
                invocation.getArgument(0),
                invocation.getArgument(1),
                invocation.getArgument(2),
                Job.Status.PENDING,
                null,
                null,
                null,
                invocation.getArgument(3)
            );

            jobs.put(job.getUuid(), job);

            return job;
        });
        lenient().when(jobRepository.getByUuid(any())).thenAnswer(invocation ->
            Optional.of(jobs.get(invocation.getArgument(0)))
        );
        lenient().when(jobRepository.save(any())).thenAnswer(invocation ->
            invocation.getArgument(0)
        );
        lenient().when(jobRepository.getByTypeCodeAndKey(any(), any())).thenAnswer(invocation ->
            jobs.values().stream()
            .filter(job -> job.getTypeCode().equals(invocation.getArgument(0)) && job.getKey().equals(invocation.getArgument(1)))
            .toList()
        );
        lenient().when(jobRepository.countByTypeCodeAndStatus(any(), any())).thenAnswer(invocation ->
            (int)jobs.values().stream()
            .filter(job -> job.getTypeCode().equals(invocation.getArgument(0)) && job.getStatus().equals(invocation.getArgument(1)))
            .count()
        );
        lenient().when(jobRepository.getByTypeCodeAndStatus(any(), any())).thenAnswer(invocation ->
            jobs.values().stream()
            .filter(job -> job.getTypeCode().equals(invocation.getArgument(0)) && job.getStatus().equals(invocation.getArgument(1)))
            .toList()
        );
    }

    public static class MockScheduler implements TaskScheduler{
        public List<Runnable> tasks = new ArrayList<>();

        public void triggerTasks(){
            tasks.forEach(Runnable::run);
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable task, Trigger trigger) {
            tasks.add(task);
            return null;
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable task, Instant startTime) {
            return null;
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Instant startTime, Duration period) {
            return null;
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Duration period) {
            return null;
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Instant startTime, Duration delay) {
            return null;
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Duration delay) {
            return null;
        }
    }

    public static class AService{
        public void success(String arg1){

        }

        public void error(String arg1, Throwable t){

        }
    }

    @NoArgsConstructor
    @AllArgsConstructor
    public static class Data {
        List<String> waypoints = new ArrayList<>();

        public static String serialize(Data data) {
            //join with comma
            return "[" + StringUtils.join(data.waypoints, ",") + "]";
        }

        public static Data deserialize(String data) {
            Data d = new Data();
            if (StringUtils.isNotBlank(data)) {
                if( "[]".equals(data)){
                    d.waypoints = new ArrayList<>();
                } else {
                    String[] split = data.substring(1, data.length() - 1).split(",");

                    d.waypoints = new ArrayList<>(List.of(split));
                }
            } else {
                d.waypoints = null;
            }
            return d;
        }
    }
}
