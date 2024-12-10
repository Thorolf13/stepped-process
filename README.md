# Stepped Process

Provide an API to declare a process with a list of steps, with options to retry after failure, hooks, conditions, and more.

## Installation

```xml
<dependency>
    <groupId>io.github.thorolf13.stepped-process</groupId>
    <artifactId>stepped-process</artifactId>
    <version>1.0.6</version>
</dependency>
```

## Usage

### Create bean

```java
@Configuration
@Slf4j
public class Config {
    @Bean
    public SteppedProcessService steppedProcessService(JobDataRepository jobRepository) {
        return new SteppedProcessService(jobRepository, log);
    }
}
```

```java
@Service
@RequiredArgsConstructor
public class JobDataRepository implements JobRepository {
    public Optional<Job> getByUuid(String uuid) {
        //...
    }

    @Override
    public List<Job> getByTypeCodeAndKey(String typeCode, String key) {
        //...
    }

    @Override
    public int countByTypeCodeAndStatus(String type, Job.Status status) {
        //...
    }

    @Override
    public List<Job> getByTypeCodeAndStatus(String type, Job.Status status) {
        //...
    }

    @Override
    public Job save(Job job) {
        //...
    }
}
```

### Create a process

```java
SteppedProcess<Data> steppedProcess = SteppedProcess.<Data>builder()
    //required
    .typeCode("PROCESS_1")
    .serializer(Data::serialize)
    .deserializer(Data::deserialize)
    .addStep(/*...*/)
    //optional
    .schedule(/*...*/)
    .duplicatePolicy(SteppedProcess.DuplicatePolicy.DENY_PENDING)
    .onSuccess(onSuccess)
    .onError(onError)
    .build();
```

### Add steps

```java
@Autowired
SteppedProcessService steppedProcessService;

void createProcess() {
    SteppedProcess<Data> steppedProcess = SteppedProcess.<Data>builder()
        //...
        .addStep(Step.<Data>builder()
            .code("STEP_1")
            .isStart(true)
            .action(context -> {
                //do some stuff
                return "STEP_2"; //return next step code
            })
            .condition(/*...*/)
            .maxRetry(3)
            .retryDelay(Duration.ofSeconds(10))
            .build()
        )
        .addStep(Step.<Data>builder()
            .code("STEP_2")
            .action(context -> {
                //do some stuff
                return null; //return null to finish the process
            })
            .build()
        )
        //...
        .build();

    steppedProcessService.registerProcess(steppedProcess);
}
```

### Create and execute job

```java
@Autowired
SteppedProcessService steppedProcessService;

void executeProcess() {
    Job job = steppedProcessService.createJob("PROCESS_1", "key1", new Data(), date);

    steppedProcessService.processingJob(job);
    //or
    steppedProcessService.processingJob(job.getUuid());
    //or
    steppedProcessService.processingJobs("PROCESS_1");
}
``` 

## Job lifecycle

Status :
- **_PENDING_** : job is created
- **_RUNNING_** : job is processing
- **_WAITING_** : step condition is not satisfied, retry on next schedule
- **_RESUMING_** : job is resuming after failure
- **_SUCCESS_** : job is finished successfully
- **_ERROR_** : job is finished with error
- **_CANCELED_** : job is canceled

## API

### SteppedProcess
Parameters:
- `typeCode` _String_ - unique identifier of the process
- `serializer` _Function<String, T>_ - function to serialize Job data to string
- `deserializer` _Function<String, T>_ - function to deserialize Job data from string
- `addStep` Step - add steps to the process
- `schedule` _Function<Runnable, ScheduledFuture<?>>_ - (optional) scheduler
- `duplicatePolicy` _SteppedProcess.DuplicatePolicy_ - (optional) policy to handle duplicate jobs (duplicate 'key' and 'typeCode')
- `onSuccess` _Consumer<JobContext<T>>_ - (optional) hook to execute on success
- `onError` _BiConsumer<JobContext<T>, Throwable>_ - (optional) hook to execute on error

### Step
Parameters:
- `code` _String_ - unique identifier of the step
- `isStart` _boolean_ - (optional) flag to mark the step as the first step - process must have exactly one start step
- `action` _Function<JobContext<T>, String>_ - action to execute
- `condition` _Predicate<JobContext<T>>_ - (optional) predicate to check before executing the action
- `maxRetry` _int_ - (optional) maximum number of retries
- `retryDelay` _Duration_ - (optional) delay between retries

### JobContext
Parameters:
- `jobUuid` _String_ - job uuid
- `jobKey` _String_ - job key
- `step` _Sting_ - current step code
- `data` _T_ - job data

### SteppedProcessService
Methods:
- `void registerProcess(SteppedProcess<T> process)` - register a process
- `Job createJob(String typeCode, String key, T data, Date executionDate)` - create a delayed job
- `Job createJob(String typeCode, String key, T data)` - create a job'
- `void processingJob(Job job)` - start processing a job
- `void processingJob(String jobUuid)` - start processing a job
- `void processingJobs(String typeCode)` - start processing all jobs of a process (all jobs with status in : PENDING, WAITING, RESUMING)
- `void cancelJob(Job job)` - cancel a job
- `void cancelJob(String jobUuid)` - cancel a job
- `Job resumeErrorJob(Job job)` - resume a job in error status
- `Job resumeErrorJob(String jobUuid)` - resume a job in error status
- `Job updatePendingJob(job job, T data, LocalDateTime executionDate)` - update a pending job
- `Job updatePendingJob(String jobUuid, T data, LocalDateTime executionDate)` - update a pending job
- `List<Job> getJobsByTypeAndKey(String typeCode, String key)` - get all jobs by type and key
- `Job getJobByUuid(String uuid)` - get a job by uuid

## Advanced

### Schedule process

```java
@Configuration

public class SchedulerConfig {
    @Bean
    public TaskScheduler taskScheduler() {
        return new ThreadPoolTaskScheduler();
    }
}
```

```java
SteppedProcess<Data> steppedProcess = SteppedProcess.<Data>builder()
    .schedule(task -> taskScheduler.schedule(task, new CronTrigger("0 0 10 * * *")))
    //...
    .build();
```
or

```java
@Configuration
@EnableScheduling
public class SchedulerConfig {
    @Scheduled(cron = "0 0 10 * * *")
    public void schedule() {
        steppedProcessService.processingJobs("PROCESS_1");
    }
}
```

### Hooks

```java
SteppedProcess<Data> steppedProcess = SteppedProcess.<Data>builder()
    .onSuccess(context -> {
        //do some stuff
    })
    .onError((context, throwable) -> {
        //do some stuff
    })
    //...
    .build();
```

### Conditions

```java
Step<Data> step = Step.<Data>builder()
    .condition(context -> {
        //return true or false
    })
    //...
    .build();
```
### Duplicate policy

```java
SteppedProcess<Data> steppedProcess = SteppedProcess.<Data>builder()
    .duplicatePolicy(SteppedProcess.DuplicatePolicy.DENY_PENDING)
    //...
    .build();
```

Possible values:
- **_DENY_PENDING_** : deny job creation if there is a pending job with the same 'key' and 'typeCode'
- **_DENY_** : deny job creation if there is a job with the same 'key' and 'typeCode'
- **_ALLOW_** : allow job creation

ProcessDuplicateJobException will be thrown by createJob method if the policy is DENY_PENDING or DENY and there is a duplicate job.