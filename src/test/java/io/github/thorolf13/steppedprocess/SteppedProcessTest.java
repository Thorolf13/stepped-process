package io.github.thorolf13.steppedprocess;

import io.github.thorolf13.steppedprocess.exception.ProcessDuplicateJobException;
import io.github.thorolf13.steppedprocess.listener.JobListener;
import io.github.thorolf13.steppedprocess.listener.StepListener;
import io.github.thorolf13.steppedprocess.model.Job;
import io.github.thorolf13.steppedprocess.model.Status;
import io.github.thorolf13.steppedprocess.model.Step;
import io.github.thorolf13.steppedprocess.model.SteppedProcess;
import io.github.thorolf13.steppedprocess.provided.JobRepository;
import io.github.thorolf13.steppedprocess.utils.MockTime;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
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
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@Slf4j
public class SteppedProcessTest {

    @InjectMocks
    private SteppedProcessService steppedProcessService;

    @Mock
    private JobRepository jobRepository;

    @Mock
    private JobListener<Data> jobListener;

    @Mock
    private StepListener<Data> stepListener;

    private void verifyListeners(
        int onJobStart,
        int onJobRetry,
        int onJobResume,
        int onJobSuccess,
        int onJobError,
        int onStepStart,
        int onStepSuccess,
        int onStepError
    ){
        verify(jobListener, times(onJobStart)).onJobStart(any());
        verify(jobListener, times(onJobRetry)).onJobRetry(any());
        verify(jobListener, times(onJobResume)).onJobResume(any());
        verify(jobListener, times(onJobSuccess)).onJobSuccess(any());
        verify(jobListener, times(onJobError)).onJobError(any(), any());

        verify(jobListener, times(onStepStart)).onStepStart(any());
        verify(jobListener, times(onStepSuccess)).onStepSuccess(any());
        verify(jobListener, times(onStepError)).onStepError(any(), any());

        verify(stepListener, times(onStepStart)).onStepStart(any());
        verify(stepListener, times(onStepSuccess)).onStepSuccess(any());
        verify(stepListener, times(onStepError)).onStepError(any(), any());
    }

    private void onProcessingJobsMock(String typeCode, Integer count){

    }

    @BeforeEach
    public void setUp() {
        ReflectionTestUtils.setField(steppedProcessService, "onProcessingJobs", (BiConsumer<String, Integer>)this::onProcessingJobsMock);
        mockJobRepository();
        clearInvocations(jobListener, stepListener);
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
                .stepListener(stepListener)
                .build()
            )
            .addStep(Step.<Data>builder()
                .code("STEP_2")
                .action(context -> {
                    context.getData().waypoints.add(context.getStep());
                    return null;
                })
                .stepListener(stepListener)
                .build()
            )
            .jobListener(jobListener)
            .build();

        steppedProcessService.registerProcess(steppedProcess);
        Job job = steppedProcessService.createJob("PROCESS_1", "JOB_1", new Data());
        steppedProcessService.processingJob("uuid-1");

        assertThat(job.getStatus()).isEqualTo(Status.SUCCESS);
        assertThat(job.getData()).isEqualTo("[STEP_1,STEP_2]");

        verifyListeners(1, 0, 0, 1, 0, 2, 2, 0);
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
                .stepListener(stepListener)
                .build()
            )
            .addStep(Step.<Data>builder()
                .code("STEP_2")
                .action(context -> {
                    throw new RuntimeException("ERROR", new RuntimeException("CAUSE1", new RuntimeException("CAUSE2")));
                })
                .stepListener(stepListener)
                .build()
            )
            .jobListener(jobListener)
            .build();

        steppedProcessService.registerProcess(steppedProcess);
        Job job = steppedProcessService.createJob("PROCESS_1", "JOB_1", new Data());
        steppedProcessService.processingJob("uuid-1");


        assertThat(job.getStatus()).isEqualTo(Status.ERROR);
        assertThat(job.getData()).isEqualTo("[STEP_1]");
        assertThat(job.getMessage()).isEqualTo("java.lang.RuntimeException: ERROR\n" +
            "Caused by : java.lang.RuntimeException: CAUSE1\n" +
            "Caused by : java.lang.RuntimeException: CAUSE2");

        verifyListeners(1, 0, 0, 0, 1, 2, 1, 1);
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
                .stepListener(stepListener)
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
                .stepListener(stepListener)
                .build()
            )
            .jobListener(jobListener)
            .build();


        steppedProcessService.registerProcess(steppedProcess);
        Job job = steppedProcessService.createJob("PROCESS_1", "JOB_1", new Data());
        steppedProcessService.processingJob("uuid-1");

        assertThat(job.getStatus()).isEqualTo(Status.RESUMING);
        assertThat(job.getData()).isEqualTo("[STEP_1]");
        assertThat(job.getMessage()).isEqualTo("java.lang.RuntimeException: ERROR");

        verifyListeners(1, 0, 0, 0, 0, 2, 1, 1);

        steppedProcessService.processingJob("uuid-1");

        assertThat(job.getStatus()).isEqualTo(Status.SUCCESS);
        assertThat(job.getData()).isEqualTo("[STEP_1,STEP_2]");
        assertThat(job.getMessage()).isEqualTo(null);

        verifyListeners(1, 1, 0, 1, 0, 3, 2, 1);
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
                .stepListener(stepListener)
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
                .stepListener(stepListener)
                .build()
            )
            .jobListener(jobListener)
            .build();


        steppedProcessService.registerProcess(steppedProcess);
        Job job = steppedProcessService.createJob("PROCESS_1", "JOB_1", new Data());

        //error -> resuming
        MockTime.excecuteAtTime(
            LocalDateTime.of(2021, 1, 1, 10, 0),
            () -> steppedProcessService.processingJob("uuid-1")
        );

        assertThat(job.getStatus()).isEqualTo(Status.RESUMING);
        assertThat(job.getData()).isEqualTo("[STEP_1]");
        assertThat(job.getMessage()).isEqualTo("java.lang.RuntimeException: ERROR");

        verifyListeners(1, 0, 0, 0, 0, 2, 1, 1);

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
        assertThat(job.getStatus()).isEqualTo(Status.RESUMING);
        assertThat(job.getData()).isEqualTo("[STEP_1]");
        assertThat(job.getMessage()).isEqualTo("java.lang.RuntimeException: ERROR");

        verifyListeners(1, 0, 0, 0, 0, 2, 1, 1);

        //retry after delay
        MockTime.excecuteAtTime(
            LocalDateTime.of(2021, 1, 1, 11, 0),
            () -> steppedProcessService.processingJob("uuid-1")
        );

        assertThat(job.getStatus()).isEqualTo(Status.SUCCESS);
        assertThat(job.getData()).isEqualTo("[STEP_1,STEP_2]");
        assertThat(job.getMessage()).isEqualTo(null);

        verifyListeners(1, 1, 0, 1, 0, 3, 2, 1);
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
                .stepListener(stepListener)
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
                .stepListener(stepListener)
                .build()
            )
            .jobListener(jobListener)
            .build();

        steppedProcessService.registerProcess(steppedProcess);
        Job job = steppedProcessService.createJob("PROCESS_1", "JOB_1", new Data());
        steppedProcessService.processingJob("uuid-1");

        assertThat(job.getStatus()).isEqualTo(Status.WAITING);
        assertThat(job.getData()).isEqualTo("[STEP_1]");
        assertThat(job.getMessage()).isEqualTo(null);

        verifyListeners(1, 0, 0, 0, 0, 1, 1, 0);

        steppedProcessService.processingJob("uuid-1");

        assertThat(job.getStatus()).isEqualTo(Status.SUCCESS);
        assertThat(job.getData()).isEqualTo("[STEP_1,STEP_2]");
        assertThat(job.getMessage()).isEqualTo(null);

        verifyListeners(1, 0, 1, 1, 0, 2, 2, 0);
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
                .stepListener(stepListener)
                .build()
            )
            .addStep(Step.<Data>builder()
                .code("STEP_2")
                .action(context -> {
                    context.getData().waypoints.add(context.getStep());
                    return null;
                })
                .maxRetry(1)
                .stepListener(stepListener)
                .build()
            )
            .jobListener(jobListener)
            .build();

        steppedProcessService.registerProcess(steppedProcess);
        Job job = steppedProcessService.createJob("PROCESS_1", "JOB_1", new Data(), LocalDateTime.of(2021, 1, 1, 10, 0));
        MockTime.excecuteAtTime(
            LocalDateTime.of(2021, 1, 1, 9, 0),
            () -> steppedProcessService.processingJob("uuid-1")
        );

        assertThat(job.getStatus()).isEqualTo(Status.PENDING);
        assertThat(job.getData()).isEqualTo("[]");
        assertThat(job.getMessage()).isEqualTo(null);

        verifyListeners(0, 0, 0, 0, 0, 0, 0, 0);

        MockTime.excecuteAtTime(
            LocalDateTime.of(2021, 1, 1, 11, 0),
            () -> steppedProcessService.processingJob("uuid-1")
        );

        assertThat(job.getStatus()).isEqualTo(Status.SUCCESS);
        assertThat(job.getData()).isEqualTo("[STEP_1,STEP_2]");
        assertThat(job.getMessage()).isEqualTo(null);

        verifyListeners(1, 0, 0, 1, 0, 2, 2, 0);
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

        assertThat(job.getStatus()).isEqualTo(Status.PENDING);

        steppedProcessService.processingJobs("PROCESS_1");

        assertThat(job.getStatus()).isEqualTo(Status.SUCCESS);
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

        assertThat(job.getStatus()).isEqualTo(Status.PENDING);

        scheduler.triggerTasks();

        assertThat(job.getStatus()).isEqualTo(Status.SUCCESS);
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

        assertThat(job.getStatus()).isEqualTo(Status.PENDING);

        scheduler.triggerTasks();

        assertThat(job.getStatus()).isEqualTo(Status.PENDING);

        concordiaIsMaster.set(true);
        scheduler.triggerTasks();

        assertThat(job.getStatus()).isEqualTo(Status.SUCCESS);
    }

    @Test
    public void test_multipleJobs_multiplesStatus() throws ProcessDuplicateJobException {
        List<String> processingorder = new ArrayList<>();
        SteppedProcess<Data> steppedProcess = SteppedProcess.<Data>builder()
            .typeCode("PROCESS_1")
            .serializer(Data::serialize)
            .deserializer(Data::deserialize)
            .addStep(Step.<Data>builder()
                .code("STEP_1")
                .isStart(true)
                .action(context -> {
                    context.getData().waypoints.add(context.getStep());
                    processingorder.add(context.getJobKey());
                    return null;
                })
                .build()
            )
            .build();
        steppedProcessService.registerProcess(steppedProcess);

        List<Status> statusList = List.of(
            Status.PENDING,
            Status.WAITING,
            Status.PENDING,
            Status.RESUMING,
            Status.RESUMING,
            Status.PENDING,
            Status.RESUMING,
            Status.WAITING,
            Status.RESUMING,
            Status.WAITING,
            Status.SUCCESS
        );
        List<Job> jobs = new ArrayList<>();
        for (int i=0 ; i < statusList.size(); i++) {
            List<String> initialWaypoints = new ArrayList<>();
            initialWaypoints.add("__"+i);
            Job job = steppedProcessService.createJob("PROCESS_1", "JOB_"+i, new Data(initialWaypoints));
            job.setStatus(statusList.get(i));
            job.setStep("STEP_1");
            jobs.add(job);
        }

        steppedProcessService.processingJobs("PROCESS_1");

        List<String> processingorderExpected = List.of( //RESUMING before WAITING before PENDING, SUCCESS not processed
            "JOB_3",
            "JOB_4",
            "JOB_6",
            "JOB_8",
            "JOB_1",
            "JOB_7",
            "JOB_9",
            "JOB_0",
            "JOB_2",
            "JOB_5"
        );

        List<Status> statusListExpected = List.of(
            Status.SUCCESS,
            Status.SUCCESS,
            Status.SUCCESS,
            Status.SUCCESS,
            Status.SUCCESS,
            Status.SUCCESS,
            Status.SUCCESS,
            Status.SUCCESS,
            Status.SUCCESS,
            Status.SUCCESS,
            Status.SUCCESS
        );

        List<String> dataExpected = List.of(
            "[__0,STEP_1]",
            "[__1,STEP_1]",
            "[__2,STEP_1]",
            "[__3,STEP_1]",
            "[__4,STEP_1]",
            "[__5,STEP_1]",
            "[__6,STEP_1]",
            "[__7,STEP_1]",
            "[__8,STEP_1]",
            "[__9,STEP_1]",
            "[__10]"
        );

        assertThat(jobs.stream().map(Job::getStatus).toList()).isEqualTo(statusListExpected);
        assertThat(jobs.stream().map(Job::getData).toList()).isEqualTo(dataExpected);
        assertThat(processingorder).isEqualTo(processingorderExpected);
    }

    //############################################
    // utils
    //############################################

    private void mockJobRepository(){
        List<Job> jobs = new ArrayList<>();
        AtomicInteger jobCounter = new AtomicInteger(0);

        lenient().when(jobRepository.createJob(any(), any(), any(), any())).thenAnswer(invocation -> {
            Job job = new JobImpl(
                "uuid-"+jobCounter.incrementAndGet(),
                invocation.getArgument(0),
                invocation.getArgument(1),
                invocation.getArgument(2),
                Status.PENDING,
                null,
                null,
                null,
                invocation.getArgument(3)
            );

            jobs.add(job);

            return job;
        });
        lenient().when(jobRepository.findOneByUuid(any())).thenAnswer(invocation ->
            jobs.stream().filter(job -> job.getUuid().equals(invocation.getArgument(0))).findFirst()
        );
        lenient().when(jobRepository.saveJob(any())).thenAnswer(invocation ->
            invocation.getArgument(0)
        );
        lenient().when(jobRepository.findAllByTypeCodeAndKey(any(), any())).thenAnswer(invocation ->
            jobs.stream()
            .filter(job -> job.getTypeCode().equals(invocation.getArgument(0)) && job.getKey().equals(invocation.getArgument(1)))
            .toList()
        );
        lenient().when(jobRepository.countByTypeCodeAndStatus(any(), any())).thenAnswer(invocation ->
            (int)jobs.stream()
            .filter(job -> job.getTypeCode().equals(invocation.getArgument(0)) && job.getStatus().equals(invocation.getArgument(1)))
            .count()
        );
        lenient().when(jobRepository.findAllByTypeCodeAndStatus(any(), any())).thenAnswer(invocation ->
            jobs.stream()
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

    @Getter
    @Setter
    @AllArgsConstructor
    class JobImpl extends Job{
        private String uuid;
        private String typeCode;
        private String key;
        private String data;
        private Status status;
        private String step;
        private String message;
        private Integer retry;
        private LocalDateTime nextExecution;
    }
}
