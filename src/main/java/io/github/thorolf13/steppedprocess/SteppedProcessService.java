package io.github.thorolf13.steppedprocess;

import io.github.thorolf13.steppedprocess.exception.ProcessDuplicateJobException;
import io.github.thorolf13.steppedprocess.exception.ProcessIllegalStateException;
import io.github.thorolf13.steppedprocess.model.JobContext;
import io.github.thorolf13.steppedprocess.model.SteppedProcess;
import io.github.thorolf13.steppedprocess.provided.JobRepository;
import io.github.thorolf13.steppedprocess.model.Job;
import io.github.thorolf13.steppedprocess.model.Step;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;

import java.time.LocalDateTime;
import java.util.*;

import static io.github.thorolf13.steppedprocess.exception.ExceptionCode.*;
import static io.github.thorolf13.steppedprocess.utils.DateUtils.isBeforeOrEqual;
import static io.github.thorolf13.steppedprocess.utils.ExceptionsUtils.buildErrorMesage;
import static io.github.thorolf13.steppedprocess.utils.ListUtils.concat;

@Slf4j
public class SteppedProcessService {

    public static final String LOG_MARKER_PREFIX = "stepped-process.";

    private final JobRepository jobRepository;
    private final Map<String, SteppedProcess<?>> processMap;


    public SteppedProcessService(JobRepository jobRepository){
        this.jobRepository = jobRepository;
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

        List<Job> existingJobs = jobRepository.getByTypeCodeAndKey(typeCode, key)
            .stream()
            .filter(job -> !Job.Status.CANCELED.equals(job.getStatus()))
            .toList();
        if(  !existingJobs.isEmpty() ){
            switch (steppedProcess.getDuplicatePolicy()){
                case DENY:
                    throw new ProcessDuplicateJobException("Job already exists for process : " + typeCode + " key : " + key);
                case DENY_PENDING:
                    if( existingJobs.stream().anyMatch(job -> Job.Status.PENDING.equals(job.getStatus())) ){
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
        Job job = jobRepository.getByUuid(uuid)
            .orElseThrow(() -> new ProcessIllegalStateException("Job not found for uuid : " + uuid, JOB_NOT_FOUND));

        job.setStatus(Job.Status.CANCELED);
        jobRepository.save(job);
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
        Job job = jobRepository.getByUuid(uuid)
            .orElseThrow(() -> new ProcessIllegalStateException("Job not found for uuid : " + uuid, JOB_NOT_FOUND));

        if( !Job.Status.ERROR.equals(job.getStatus())){
            throw new ProcessIllegalStateException("Job not in error status : " + job.getUuid(), JOB_STATUS_ERROR);
        }

        job.setStatus(Job.Status.RESUMING);
        if( overrideData != null ){
            SteppedProcess<T> steppedProcess = getProcess(job.getTypeCode());
            try {
                job.setData(steppedProcess.getSerializer().apply(overrideData));
            } catch (Exception e) {
                throw new ProcessIllegalStateException("Error on serialize data", SERIALIZE_ERROR, e);
            }
        }
        return jobRepository.save(job);
    }

    public Job updatePendingJob(Job job, String data, LocalDateTime executionTime){
        return updatePendingJob(job.getUuid(), data, executionTime);
    }
    public Job updatePendingJob(String uuid, String data, LocalDateTime executionTime){
        Job job = jobRepository.getByUuid(uuid)
            .orElseThrow(() -> new ProcessIllegalStateException("Job not found for uuid : " + uuid, JOB_NOT_FOUND));

        if( !Job.Status.PENDING.equals(job.getStatus())){
            throw new ProcessIllegalStateException("Job not in pending status : " + job.getUuid(), JOB_STATUS_ERROR);
        }

        job.setData(data);
        job.setNextExecution(executionTime);
        jobRepository.save(job);

        return job;
    }

    public List<Job> getJobsByTypeAndKey(String typeCode, String key){
        return jobRepository.getByTypeCodeAndKey(typeCode, key);
    }

    public Job getJobByUuid(String uuid){
        return jobRepository.getByUuid(uuid)
            .orElseThrow(() -> new ProcessIllegalStateException("Job not found for uuid : " + uuid, JOB_NOT_FOUND));
    }

    public void processingJobs(String typeCode){
        int count = countJobs(typeCode);
        if( count == 0 ){
            return;
        }

        log.info("Processing jobs for type : " + typeCode + " count : " + count);


        List<Job> jobs = concat(
            jobRepository.getByTypeCodeAndStatus(typeCode, Job.Status.RESUMING),
            jobRepository.getByTypeCodeAndStatus(typeCode, Job.Status.WAITING),
            jobRepository.getByTypeCodeAndStatus(typeCode, Job.Status.PENDING)
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
            jobRepository.getByUuid(uuid)
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
        return jobRepository.countByTypeCodeAndStatus(typeCode, Job.Status.RESUMING)
            + jobRepository.countByTypeCodeAndStatus(typeCode, Job.Status.WAITING)
            + jobRepository.countByTypeCodeAndStatus(typeCode, Job.Status.PENDING);
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
        if( !Job.Status.PENDING.equals(job.getStatus())
            && !Job.Status.RESUMING.equals(job.getStatus())
            && !Job.Status.WAITING.equals(job.getStatus())
        ){
            throw new ProcessIllegalStateException("Job already processed : " + job.getUuid(), JOB_ALREADY_PROCESSED);
        }

        SteppedProcess<?> steppedProcess = getProcess(job.getTypeCode());

        if( Job.Status.PENDING.equals(job.getStatus()) ){
            startProcess(job, steppedProcess);
        } else {
            resumeProcess(job, steppedProcess);
        }
    }

    private void resumeProcess(Job job, SteppedProcess<?> steppedProcess) {
        if( job.getNextExecution() != null && job.getNextExecution().isAfter(LocalDateTime.now())){
            return;
        }

        job.setRetry(job.getRetry() + 1);
        job.setStatus(Job.Status.RUNNING);
        jobRepository.save(job);

        enhanceMdc(job);
        log.info("Resuming job : " + job);

        processing(job, steppedProcess, job.getStep());
    }

    private void startProcess(Job job, SteppedProcess<?> steppedProcess) {
        if( job.getNextExecution() != null && job.getNextExecution().isAfter(LocalDateTime.now())){
            return;
        }

        job.setStatus(Job.Status.RUNNING);
        jobRepository.save(job);

        enhanceMdc(job);
        log.info("Starting job : " + job);

        processing(job, steppedProcess, steppedProcess.getStartStep().getCode());
    }

    private <T> JobContext<T> buildContext(Job job, SteppedProcess<T> steppedProcess) throws Exception {
        return new JobContext<>(
            job.getUuid(),
            job.getKey(),
            job.getStep(),
            steppedProcess.getDeserializer().apply(job.getData())
        );
    }

    private <T> void processing(Job job, SteppedProcess<T> steppedProcess, String stepCode) {
        try {
            executeStep(job, steppedProcess, stepCode);
        } catch (Throwable t) {
            onError(job, steppedProcess, t);
        } finally {
            clearMdc();
        }
    }
    private <T> void executeStep(Job job, SteppedProcess<T> steppedProcess, String stepCode) throws Exception {
        job.setStep(stepCode);
        jobRepository.save(job);
        enhanceMdc(job);

        log.info("Job : " + job + " execute step : " + stepCode);

        Step<T> step = steppedProcess.getStepByCode(stepCode)
            .orElseThrow(() -> new ProcessIllegalStateException("Step not found. process : " + steppedProcess.getTypeCode() + " step : " + stepCode, STEP_NOT_FOUND));
        JobContext<T> context = buildContext(job, steppedProcess);

        String nextStepCode = null;
        try {
            if (!step.getCondition().test(context)) {
                job.setStatus(Job.Status.WAITING);
                jobRepository.save(job);
                return;
            }

            nextStepCode = step.getAction().apply(context);
        } catch (Throwable t) {
            onExecutionError(job, steppedProcess, step, t);
            return;
        }

        job.setData(steppedProcess.getSerializer().apply(context.getData()));
        job.setRetry(0);
        job.setMessage(null);
        jobRepository.save(job);

        if (nextStepCode != null) {
            executeStep(job, steppedProcess, nextStepCode);
        } else {
            onSuccess(job, steppedProcess);
        }
    }

    //############################################
    // on event
    //############################################

    private <T> void onSuccess(Job job, SteppedProcess<T> steppedProcess) {
        job.setStatus(Job.Status.SUCCESS);
        job.setStep(null);
        jobRepository.save(job);

        log.info("Job : " + job + " success");

        try{
            steppedProcess.getOnSuccess().accept(buildContext(job, steppedProcess));
        } catch (Throwable t) {
            log.error("Error on success action", t);
        }
    }

    private <T> void onExecutionError(Job job, SteppedProcess<T> steppedProcess, Step<T> step, Throwable t) {
        if( step.getMaxRetry() != null && job.getRetry() < step.getMaxRetry() ) {
            //retry

            log.warn("Error on job : " + job + " step : " + step.getCode() + " set to resuming", t);

            job.setStatus(Job.Status.RESUMING);
            job.setNextExecution(LocalDateTime.now().plus(step.getRetryDelay()));
            job.setMessage(buildErrorMesage(t));
            jobRepository.save(job);
        } else {
            //error

            onError(job, steppedProcess, t);
        }
    }

    private <T> void onError(Job job, SteppedProcess<T> steppedProcess, Throwable t) {
        log.error("Error on job : " + job, t);

        job.setStatus(Job.Status.ERROR);
        job.setMessage(buildErrorMesage(t));
        jobRepository.save(job);

        try{
            steppedProcess.getOnError().accept(buildContext(job, steppedProcess), t);
        } catch (Throwable t2) {
            log.error("Error on error action", t2);
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
}