package io.github.thorolf13.steppedprocess.function;

@FunctionalInterface
public interface CheckedFunction<T, R> {
    R apply(T t) throws Exception;
}
