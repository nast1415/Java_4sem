package ru.spbau.mit;

import java.util.function.Supplier;

public interface FactoryFromSupplier {
    <T> Lazy<T> createLazy(Supplier<T> supplier);
}
