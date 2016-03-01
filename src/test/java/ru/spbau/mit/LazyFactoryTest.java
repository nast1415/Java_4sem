package ru.spbau.mit;

import org.junit.Test;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.Assert.*;

public class LazyFactoryTest {

    private static final int NUMBER_OF_THREADS = 50;

    //Create suppliers

    private final Supplier<String> nullSupplier = new Supplier<String>() {
        private boolean isCalled = false;

        @Override
        public String get() {
            assertFalse(isCalled);
            isCalled = true;
            return null;
        }
    };

    private final Supplier<String> supplier = new Supplier<String>() {
        private boolean isCalled = false;

        @Override
        public String get() {
            assertFalse(isCalled);
            isCalled = true;
            return "I've done!";
        }
    };

    //Special functions for tests

    private void checkForNullTests(Lazy<String> lazy) {
        assertNull(lazy.get());
        assertNull(lazy.get());
    }

    private void checkForEqualTests(Lazy<String> lazy) {
        String firstGet = lazy.get();
        String secondGet = lazy.get();
        assertEquals(firstGet, secondGet);
        assertEquals(firstGet, "I've done!");
    }

    private Supplier<Integer> createMultiThreadSupplier(final Random random) {
        return new Supplier<Integer>() {
            @Override
            public Integer get() {
                return random.nextInt(500);
            }
        };
    }

    private static class SupplCounter implements Supplier<Object> {
        private AtomicInteger cnt = new AtomicInteger(0);
        private Object result;

        SupplCounter(Object result) {
            this.result = result;
        }

        @Override
        public Object get() {
            Thread.yield();
            cnt.incrementAndGet();
            return result;
        }

        public Integer getCount() {
            return cnt.get();
        }
    }

    private void checkForMultiThread(FactoryFromSupplier factory, boolean isSupplierCalledOnce,
                                     Object returnValue) {
        SupplCounter supplier = new SupplCounter(returnValue);
        Lazy<Object> lazy = factory.createLazy(supplier);

        List<Thread> tasks = new ArrayList<>();
        List<Object> results = Collections.synchronizedList(new ArrayList<>());
        CyclicBarrier barrier = new CyclicBarrier(NUMBER_OF_THREADS);

        for (int i = 0; i < NUMBER_OF_THREADS; i++) {
            Thread task = new Thread(() -> {
                try {
                    barrier.await();
                } catch (Exception e) {
                    e.printStackTrace();
                }
                results.add(lazy.get());
            });
            tasks.add(task);
            task.start();
        }

        for (Thread task : tasks) {
            try {
                task.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        assertEquals(NUMBER_OF_THREADS, results.size());

        for (Object result : results) {
            assertTrue(returnValue == result);
        }

        assertTrue(supplier.getCount() > 0);
        if (isSupplierCalledOnce) {
            assertTrue(1 == supplier.getCount());
        }
    }

    //Tests for one thread
    @Test
    public void nullTestLazyForOneThread() {
        Lazy<String> lazy = LazyFactory.createLazyOneThread(nullSupplier);
        checkForNullTests(lazy);
    }

    @Test
    public void equalsTestLazyForOneThread() {
        Lazy<String> lazy = LazyFactory.createLazyOneThread(supplier);
        checkForEqualTests(lazy);
    }

    private static class classWrapper<T> {
        public volatile T content;

        public classWrapper(T content) {
            this.content = content;
        }
    }

    @Test
    public void lazinessTestLazyForOneThread() {
        classWrapper<Boolean> isAsked = new classWrapper<>(false);

        Lazy<String> lazy = LazyFactory.createLazyOneThread(() -> {
            assertTrue(isAsked.content);
            return "It's OK!";
        });

        isAsked.content = true;
    }

    //MultiThread tests

    @Test
    public void nullTestLazyMultiThread() {
        Lazy<String> lazy = LazyFactory.createLazyMultiThread(nullSupplier);
        checkForNullTests(lazy);
    }

    @Test
    public void equalsTestLazyMultiThread() {
        Lazy<String> lazy = LazyFactory.createLazyMultiThread(supplier);
        checkForEqualTests(lazy);
    }

    @Test
    public void dataRaceTestLazyMultiThread() {
        checkForMultiThread(LazyFactory::createLazyMultiThread, true, new Object());
    }

    //MultiThread lock-free tests

    @Test
    public void nullTestLazyMultiThreadLockFree() {
        Lazy<String> lazy = LazyFactory.createLazyMultiThreadLockFree(nullSupplier);
        checkForNullTests(lazy);
    }

    @Test
    public void equalsTestLazyMultiThreadLockFree() {
        Lazy<String> myLazy = LazyFactory.createLazyMultiThreadLockFree(supplier);
        checkForEqualTests(myLazy);
    }

    @Test
    public void dataRaceTestLazyMultiThreadLockFree() {
        checkForMultiThread(LazyFactory::createLazyMultiThreadLockFree, false, new Object());
    }
}
