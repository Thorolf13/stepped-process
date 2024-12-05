package io.github.thorolf13.steppedprocess.exception;


public class ProcessExecutionException extends AbstractRuntimeException {

    public ProcessExecutionException(String message, String code) {
        super(message, code);
    }

    public ProcessExecutionException(String message, String code, Throwable cause) {
        super(message, code, cause);
    }
}
