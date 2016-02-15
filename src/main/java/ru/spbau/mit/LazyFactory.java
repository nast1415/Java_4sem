package ru.spbau.mit;

import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.Supplier;

public class LazyFactory<T> {
    public static <T> Lazy<T> createLazyOneThread(final Supplier<T> supplier) {
        return new Lazy<T>() {
            private boolean isCalled = false;
            private T result;

            public T get() {
                if (!isCalled) {
                    result = supplier.get();
                    isCalled = true;
                }
                return result;
            }
        };
    }

    public static <T> Lazy<T> createLazyMultiThread(final Supplier<T> supplier) {
        return new Lazy<T>() {
            private volatile boolean isCalled = false;
            private volatile T result;

            public T get() {
                if (!isCalled) {
                    synchronized (this) {
                        if (!isCalled) {
                            result = supplier.get();
                            isCalled = true;
                        }
                    }
                }
                return result;
            }
        };
    }

    private static class LockFreeLazy<T> implements Lazy<T> {
        private volatile T result;
        private volatile Supplier<T> mySupplier;
        private static final AtomicReferenceFieldUpdater<LockFreeLazy, Object> updater =
                AtomicReferenceFieldUpdater.newUpdater(LockFreeLazy.class, Object.class, "result");

        public LockFreeLazy(Supplier<T> supplier) {
            mySupplier = supplier;
        }


        public T get() {
            Supplier<T> supplier = mySupplier;
            if (supplier != null) {
                if (updater.compareAndSet(this, null, supplier.get())) {
                    mySupplier = null;
                }
            }
            return result;
        }
    }

    public static <T> Lazy<T> createLazyMultiThreadLockFree(Supplier<T> supplier) {
        return new LockFreeLazy<T>(supplier);
    }
}
