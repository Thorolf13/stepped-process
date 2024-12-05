package io.github.thorolf13.steppedprocess.provided;


import io.github.thorolf13.steppedprocess.model.Job;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface JobRepository {
    Optional<Job> getByUuid(String uuid);
    List<Job> getByTypeCodeAndKey(String typeCode, String key);
    int countByTypeCodeAndStatus(String type, Job.Status status);
    List<Job> getByTypeCodeAndStatus(String type, Job.Status status);
    Job save(Job job);
    default Job createJob(String typeCode, String key, String data, LocalDateTime executionTime){
        Job job = new Job(
            UUID.randomUUID().toString(),
            typeCode,
            key,
            data,
            Job.Status.PENDING,
            null,
            null,
            null,
            executionTime
        );

        return save(job);
    }
}
