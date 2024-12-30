package io.github.thorolf13.steppedprocess.provided;


import io.github.thorolf13.steppedprocess.model.Job;
import io.github.thorolf13.steppedprocess.model.Status;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface JobRepository<I extends Job>{
    Optional<I> findOneByUuid(String uuid);
    List<I> findAllByTypeCodeAndKey(String typeCode, String key);
    int countByTypeCodeAndStatus(String type, Status status);
    List<I> findAllByTypeCodeAndStatus(String type, Status status);
    I saveJob(I job);
    I createJob(String typeCode, String key, String data, LocalDateTime executionTime);
}
