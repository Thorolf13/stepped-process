package io.github.thorolf13.steppedprocess.exception;


public class ProcessIllegalStateException extends AbstractRuntimeException {
    public ProcessIllegalStateException(String message, String code) {
        super(message, code);
    }

    public ProcessIllegalStateException(String message, String code, Throwable cause) {
        super(message, code, cause);
    }
}
