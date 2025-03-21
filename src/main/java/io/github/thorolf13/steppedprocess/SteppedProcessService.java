package io.github.thorolf13.steppedprocess;

import io.github.thorolf13.steppedprocess.exception.ProcessDuplicateJobException;
import io.github.thorolf13.steppedprocess.exception.ProcessIllegalStateException;
import io.github.thorolf13.steppedprocess.model.*;
import io.github.thorolf13.steppedprocess.provided.JobRepository;
import org.slf4j.MDC;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

import static io.github.thorolf13.steppedprocess.exception.ExceptionCode.*;
import static io.github.thorolf13.steppedprocess.utils.DateUtils.isBeforeOrEqual;
import static io.github.thorolf13.steppedprocess.utils.ListUtils.concat;
import static io.github.thorolf13.steppedprocess.utils.Utils.defaultValue;

public class SteppedProcessService {

    public static final String LOG_MARKER_PREFIX = "stepped-process.";

    private final JobRepository<Job> jobRepository;

    private final Map<String, SteppedProcess<?>> processMap;

    private final BiConsumer<String, Integer> onProcessingJobs;


    public SteppedProcessService(JobRepository jobRepository){
        this(jobRepository, (typeCode, count) -> {});
    }
    public SteppedProcessService(JobRepository jobRepository, BiConsumer<String, Integer> onProcessingJobs){
        this.jobRepository = jobRepository;
        this.onProcessingJobs = onProcessingJobs;
        processMap = new HashMap<>();
    }

    //############################################
    // register
    //############################################

    public void registerProcess(SteppedProcess<?> steppedProcess){
        String code = steppedProcess.getTypeCode();

        if( processMap.containsKey(code)){
            throw new ProcessIllegalStateException("Process already registered for code : " + code, PROCESS_ALREADY_REGISTERED);
        }

        processMap.put(code, steppedProcess);

        if( steppedProcess.getScheduleSupplier() != null ){
            steppedProcess.getScheduleSupplier().apply(() -> processingJobs(code));
        }
    }

    //############################################
    // jobs
    //############################################

    public <T> Job createJob(String typeCode, String key, T data ) throws ProcessDuplicateJobException {
        return createJob(typeCode, key, data, null);
    }
    public <T> Job createJob(String typeCode, String key, T data, LocalDateTime executionTime ) throws ProcessDuplicateJobException {
        SteppedProcess<T> steppedProcess = getProcess(typeCode);
        String dataStr;
        try {
            dataStr = steppedProcess.getSerializer().apply(data);
        } catch (Exception e) {
            throw new ProcessIllegalStateException("Error on serialize data", SERIALIZE_ERROR, e);
        }

        List<Job> existingJobs = jobRepository.findAllByTypeCodeAndKey(typeCode, key)
            .stream()
            .filter(job -> !Status.CANCELED.equals(job.getStatus()))
            .toList();
        if(  !existingJobs.isEmpty() ){
            switch (steppedProcess.getDuplicatePolicy()){
                case DENY:
                    throw new ProcessDuplicateJobException("Job already exists for process : " + typeCode + " key : " + key);
                case DENY_PENDING:
                    if( existingJobs.stream().anyMatch(job -> Status.PENDING.equals(job.getStatus())) ){
                        throw new ProcessDuplicateJobException("Pending job already exists for process : " + typeCode + " key : " + key);
                    }
                    break;
                case ALLOW:
                    break;
            }
        }

        return jobRepository.createJob(typeCode, key, dataStr, executionTime);
    }

    public void cancelJob(Job job){
        cancelJob(job.getUuid());
    }
    public void cancelJob(String uuid){
        Job job = jobRepository.findOneByUuid(uuid)
            .orElseThrow(() -> new ProcessIllegalStateException("Job not found for uuid : " + uuid, JOB_NOT_FOUND));

        job.setStatus(Status.CANCELED);
        jobRepository.saveJob(job);
    }

    public Job resumeErrorJob(Job job){
        return resumeErrorJob(job.getUuid(), null);
    }
    public Job resumeErrorJob(String uuid){
        return resumeErrorJob(uuid, null);
    }

    public <T> Job resumeErrorJob(Job job, T overrideData){
        return resumeErrorJob(job.getUuid(), overrideData);
    }
    public <T> Job resumeErrorJob(String uuid, T overrideData){
        Job job = jobRepository.findOneByUuid(uuid)
            .orElseThrow(() -> new ProcessIllegalStateException("Job not found for uuid : " + uuid, JOB_NOT_FOUND));

        if( !Status.ERROR.equals(job.getStatus())){
            throw new ProcessIllegalStateException("Job not in error status : " + job.getUuid(), JOB_STATUS_ERROR);
        }

        job.setStatus(Status.RESUMING);
        if( overrideData != null ){
            SteppedProcess<T> steppedProcess = getProcess(job.getTypeCode());
            try {
                job.setData(steppedProcess.getSerializer().apply(overrideData));
            } catch (Exception e) {
                throw new ProcessIllegalStateException("Error on serialize data", SERIALIZE_ERROR, e);
            }
        }
        return jobRepository.saveJob(job);
    }

    public <T> Job updatePendingJob(String uuid, T data, LocalDateTime executionTime){
        Job job = jobRepository.findOneByUuid(uuid)
            .orElseThrow(() -> new ProcessIllegalStateException("Job not found for uuid : " + uuid, JOB_NOT_FOUND));

        return updatePendingJob(job, data, executionTime);
    }
    public <T> Job updatePendingJob(Job job, T data, LocalDateTime executionTime){
        if( !Status.PENDING.equals(job.getStatus())){
            throw new ProcessIllegalStateException("Job not in pending status : " + job.getUuid(), JOB_STATUS_ERROR);
        }

        SteppedProcess<T> steppedProcess = getProcess(job.getTypeCode());
        String dataStr;
        try {
            dataStr = steppedProcess.getSerializer().apply(data);
        } catch (Exception e) {
            throw new ProcessIllegalStateException("Error on serialize data", "SERIALIZE_ERROR", e);
        }

        job.setData(dataStr);
        job.setNextExecution(executionTime);
        jobRepository.saveJob(job);

        return job;
    }

    public List<Job> getJobsByTypeAndKey(String typeCode, String key){
        return jobRepository.findAllByTypeCodeAndKey(typeCode, key);
    }

    public Job getJobByUuid(String uuid){
        return jobRepository.findOneByUuid(uuid)
            .orElseThrow(() -> new ProcessIllegalStateException("Job not found for uuid : " + uuid, JOB_NOT_FOUND));
    }

    public void processingJobs(String typeCode){
        int count = countJobs(typeCode);
        if( count == 0 ){
            return;
        }

        onProcessingJobs.accept(typeCode, count);

        List<Job> jobs = concat(
            jobRepository.findAllByTypeCodeAndStatus(typeCode, Status.RESUMING),
            jobRepository.findAllByTypeCodeAndStatus(typeCode, Status.WAITING),
            jobRepository.findAllByTypeCodeAndStatus(typeCode, Status.PENDING)
        ).stream()
            .filter(job -> job.getNextExecution() == null || isBeforeOrEqual(job.getNextExecution(), LocalDateTime.now()))
            .toList();

        for (Job job : jobs) {
            processingJobInt(job);
        }
    }

    public void processingJob(Job job){
        processingJob(job.getUuid());
    }

    public void processingJob(String uuid){
        processingJobInt(
            jobRepository.findOneByUuid(uuid)
                .orElseThrow(() -> new ProcessIllegalStateException("Job not found for uuid : " + uuid, JOB_NOT_FOUND))
        );
    }

    //############################################
    // private
    //############################################

    //############################################
    // read
    //############################################

    private int countJobs(String typeCode){
        return jobRepository.countByTypeCodeAndStatus(typeCode, Status.RESUMING)
            + jobRepository.countByTypeCodeAndStatus(typeCode, Status.WAITING)
            + jobRepository.countByTypeCodeAndStatus(typeCode, Status.PENDING);
    }

    private <T> SteppedProcess<T> getProcess(String typeCode){
        SteppedProcess steppedProcess = processMap.get(typeCode);
        if( steppedProcess == null){
            throw new ProcessIllegalStateException("Process not found for type code : " + typeCode, PROCESS_NOT_FOUND);
        }
        return steppedProcess;
    }

    //############################################
    // process
    //############################################


    private void processingJobInt(Job job) {
        if( !Status.PENDING.equals(job.getStatus())
            && !Status.RESUMING.equals(job.getStatus())
            && !Status.WAITING.equals(job.getStatus())
        ){
            throw new ProcessIllegalStateException("Job already processed : " + job.getUuid(), JOB_ALREADY_PROCESSED);
        }

        SteppedProcess<?> steppedProcess = getProcess(job.getTypeCode());

        startOrResumeJob(job, steppedProcess);
    }


    private <T> void startOrResumeJob(Job job, SteppedProcess<T> steppedProcess) {
        if( job.getNextExecution() != null && job.getNextExecution().isAfter(LocalDateTime.now())){
            return;
        }
        SteppedProcess<T> process = getProcess(job.getTypeCode());
        JobContext<T> context;
        try {
            context = buildContext(job, process);
        } catch (Throwable t) {
            onError(job, steppedProcess, t, null);
            return;
        }

        switch (job.getStatus()) {
            case PENDING:
                job.setStep(steppedProcess.getStartStep().getCode());
                process.getJobListener().onJobStart(context);
                break;
            case RESUMING:
                job.setRetry(defaultValue(job.getRetry(), 0) + 1);
                process.getJobListener().onJobRetry(context);
                break;
            case WAITING:
                process.getJobListener().onJobResume(context);
                break;
            default :
                throw new ProcessIllegalStateException("Job already processed : " + job.getUuid(), JOB_ALREADY_PROCESSED);
        };

        job.setStatus(Status.RUNNING);
        jobRepository.saveJob(job);
        enhanceMdc(job);

        processing(job, steppedProcess, job.getStep(), context);
    }

    private <T> JobContext<T> buildContext(Job job, SteppedProcess<T> steppedProcess) throws Exception {
        return new JobContext<>(
            job.getUuid(),
            job.getKey(),
            job.getStep(),
            job.getRetry(),
            steppedProcess.getDeserializer().apply(job.getData())
        );
    }

    private <T> JobContext<T> updateAndRebuildContext(Job job, SteppedProcess<T> steppedProcess, JobContext<T> context) throws Exception {
        job.setData(steppedProcess.getSerializer().apply(context.getData()));
        jobRepository.saveJob(job);
        return buildContext(job, steppedProcess);
    }

    private <T> void processing(Job job, SteppedProcess<T> steppedProcess, String stepCode, JobContext<T> context) {
        try {
            executeStep(job, steppedProcess, stepCode, context);
        } catch (Throwable t) {
            onError(job, steppedProcess, t, context);
        } finally {
            clearMdc();
        }
    }
    private <T> void executeStep(Job job, SteppedProcess<T> steppedProcess, String stepCode, JobContext<T> context) throws Exception {
        job.setStep(stepCode);
        jobRepository.saveJob(job);
        enhanceMdc(job);

        context = updateAndRebuildContext(job, steppedProcess, context);

        Step<T> step = steppedProcess.getStepByCode(stepCode)
            .orElseThrow(() -> new ProcessIllegalStateException("Step not found. process : " + steppedProcess.getTypeCode() + " step : " + stepCode, STEP_NOT_FOUND));

        String nextStepCode = null;
        try {
            if (!step.getCondition().test(context)) {
                job.setStatus(Status.WAITING);
                jobRepository.saveJob(job);
                return;
            }

            steppedProcess.getJobListener().onStepStart(context);
            step.getStepListener().onStepStart(context);

            nextStepCode = step.getAction().apply(context);

            step.getStepListener().onStepSuccess(context);
            steppedProcess.getJobListener().onStepSuccess(context);
        } catch (Throwable t) {
            onExecutionError(job, steppedProcess, step, t, context);
            return;
        }

        job.setData(steppedProcess.getSerializer().apply(context.getData()));
        job.setRetry(0);
        job.setMessage(null);
        jobRepository.saveJob(job);

        if (nextStepCode != null) {
            executeStep(job, steppedProcess, nextStepCode, context);
        } else {
            onSuccess(job, steppedProcess, context);
        }
    }

    //############################################
    // on event
    //############################################

    private <T> void onSuccess(Job job, SteppedProcess<T> steppedProcess, JobContext<T> context) {
        job.setStatus(Status.SUCCESS);
        job.setStep(null);
        jobRepository.saveJob(job);


        try{
            steppedProcess.getJobListener().onJobSuccess(context);
            job.setData(steppedProcess.getSerializer().apply(context.getData()));
            jobRepository.saveJob(job);
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    private <T> void onExecutionError(Job job, SteppedProcess<T> steppedProcess, Step<T> step, Throwable t, JobContext<T> context) {
        try {
            step.getStepListener().onStepError(context, t);
            steppedProcess.getJobListener().onStepError(context, t);
            job.setData(steppedProcess.getSerializer().apply(context.getData()));
            jobRepository.saveJob(job);
        } catch (Throwable t2) {
            t2.printStackTrace();
        }

        if( step.getMaxRetry() != null && defaultValue(job.getRetry(), 0) < step.getMaxRetry() ) {
            //retry
            job.setStatus(Status.RESUMING);
            job.setNextExecution(LocalDateTime.now().plus(step.getRetryDelay()));
            job.setMessage(serializeError(t));
            jobRepository.saveJob(job);
        } else {
            //error

            onError(job, steppedProcess, t, context);
        }
    }

    private <T> void onError(Job job, SteppedProcess<T> steppedProcess, Throwable t, JobContext<T> context) {

        job.setStatus(Status.ERROR);
        job.setMessage(serializeError(t));
        jobRepository.saveJob(job);

        try{
            steppedProcess.getJobListener().onJobError(context, t);
            job.setData(steppedProcess.getSerializer().apply(context.getData()));
            jobRepository.saveJob(job);
        } catch (Throwable t2) {
            t2.printStackTrace();
        }
    }

    //############################################
    // MDC
    //############################################

    public void enhanceMdc(Job job){
        MDC.put(LOG_MARKER_PREFIX + "type", job.getTypeCode());
        MDC.put(LOG_MARKER_PREFIX + "job.uuid", job.getUuid());
        MDC.put(LOG_MARKER_PREFIX + "job.key", job.getKey());
        MDC.put(LOG_MARKER_PREFIX + "job.status", job.getStatus().name());
        MDC.put(LOG_MARKER_PREFIX + "job.step", job.getStep());
    }

    public void clearMdc(){
        MDC.getCopyOfContextMap().keySet().stream()
            .filter(key -> key.startsWith(LOG_MARKER_PREFIX))
            .forEach(MDC::remove);
    }

    //############################################
    // error
    //############################################

    private String serializeError(Throwable t){
        StringBuilder sb = new StringBuilder();

        sb.append(t.toString());
        while (t.getCause() != null){
            t = t.getCause();
            sb.append("\nCaused by : ").append(t.toString());
        }

        return sb.toString();
    }
}
