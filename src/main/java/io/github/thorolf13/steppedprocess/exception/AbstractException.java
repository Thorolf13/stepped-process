package io.github.thorolf13.steppedprocess.exception;


import lombok.Getter;

public class AbstractException extends Exception {
    @Getter
    protected String code;
    public AbstractException(String message, String code) {
        super(message);
        this.code = code;
    }

    public AbstractException(String message, String code, Throwable cause) {
        super(message, cause);
        this.code = code;
    }
}
