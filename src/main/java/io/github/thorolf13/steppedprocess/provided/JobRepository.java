package io.github.thorolf13.steppedprocess.provided;


import io.github.thorolf13.steppedprocess.model.Job;
import io.github.thorolf13.steppedprocess.model.Status;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface JobRepository {
    Optional<Job> getByUuid(String uuid);
    List<Job> getByTypeCodeAndKey(String typeCode, String key);
    int countByTypeCodeAndStatus(String type, Status status);
    List<Job> getByTypeCodeAndStatus(String type, Status status);
    Job save(Job job);
    Job createJob(String typeCode, String key, String data, LocalDateTime executionTime);
}
