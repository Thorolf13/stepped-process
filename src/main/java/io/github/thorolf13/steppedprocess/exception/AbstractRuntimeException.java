package io.github.thorolf13.steppedprocess.exception;


import lombok.Getter;

public class AbstractRuntimeException extends RuntimeException {
    @Getter
    protected String code;
    public AbstractRuntimeException(String message, String code) {
        super(message);
        this.code = code;
    }

    public AbstractRuntimeException(String message, String code, Throwable cause) {
        super(message, cause);
        this.code = code;
    }
}
