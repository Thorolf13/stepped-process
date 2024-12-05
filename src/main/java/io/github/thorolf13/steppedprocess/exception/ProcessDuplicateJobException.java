package io.github.thorolf13.steppedprocess.exception;


import static io.github.thorolf13.steppedprocess.exception.ExceptionCode.DUPLICATE_JOB_FOUND;

public class ProcessDuplicateJobException extends AbstractException {

    public ProcessDuplicateJobException(String message) {
        super(message, DUPLICATE_JOB_FOUND);
    }
}
