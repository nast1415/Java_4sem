package ru.spbau.mit;

import org.junit.Test;

import java.util.Random;
import java.util.function.Supplier;

import static org.junit.Assert.*;

public class LazyFactoryTest {

    //Create suppliers

    private Supplier<String> nullSupplier = new Supplier<String>() {
        private boolean isCalled = false;

        @Override
        public String get() {
            assertFalse(isCalled);
            isCalled = true;
            return null;
        }
    };

    private Supplier<String> supplier = new Supplier<String>() {
        private boolean isCalled = false;

        @Override
        public String get() {
            assertFalse(isCalled);
            isCalled = true;
            return "I've done!";
        }
    };

    //Special functions for tests

    public void checkForNullTests(Lazy<String> lazy) {
        assertNull(lazy.get());
        assertNull(lazy.get());
    }

    public void checkForEqualTests(Lazy<String> lazy) {
        String firstGet = lazy.get();
        String secondGet = lazy.get();
        assertEquals(firstGet, secondGet);
        assertEquals(firstGet, "I've done!");
    }

    public Supplier<Integer> createMultiThreadSupplier(final Random random) {
        return new Supplier<Integer>() {
            @Override
            public Integer get() {
                return random.nextInt(500);
            }
        };
    }

    public void createAndCheckThreads(final Lazy<Integer> lazy) {
        Thread[] threads = new Thread[5];
        final Integer[] result = new Integer[5];

        for (int i = 0; i < 5; i++) {
            final int currentNumber = i;
            threads[currentNumber] = new Thread(new Runnable() {
                @Override
                public void run() {
                    result[currentNumber] = lazy.get();
                }
            });
        }

        for (int i = 0; i < 5; i++) {
            threads[i].start();
        }

        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        for (int i = 0; i < 4; i++) {
            assertEquals(result[i], result[i + 1]);
        }
    }

    //Tests for one thread

    @Test
    public void nullTestLazyForOneThread() {
        Lazy<String> myLazy = LazyFactory.createLazyOneThread(nullSupplier);
        checkForNullTests(myLazy);
    }

    @Test
    public void equalsTestLazyForOneThread() {
        Lazy<String> myLazy = LazyFactory.createLazyOneThread(supplier);
        checkForEqualTests(myLazy);
    }

    //MultiThread tests

    @Test
    public void nullTestLazyMultiThread() {
        Lazy<String> myLazy = LazyFactory.createLazyMultiThread(nullSupplier);
        checkForNullTests(myLazy);
    }

    @Test
    public void equalsTestLazyMultiThread() {
        Lazy<String> myLazy = LazyFactory.createLazyMultiThread(supplier);
        checkForEqualTests(myLazy);
    }

    @Test
    public void dataRaceTestLazyMultiThread() {
        final Random myRandom = new Random();
        Supplier<Integer> mySupplier = createMultiThreadSupplier(myRandom);
        Lazy<Integer> myLazy = LazyFactory.createLazyMultiThread(mySupplier);

        createAndCheckThreads(myLazy);

    }

    //MultiThread lock-free tests

    @Test
    public void nullTestLazyMultiThreadLockFree() {
        Lazy<String> myLazy = LazyFactory.createLazyMultiThreadLockFree(nullSupplier);
        checkForNullTests(myLazy);
    }

    @Test
    public void equalsTestLazyMultiThreadLockFree() {
        Lazy<String> myLazy = LazyFactory.createLazyMultiThreadLockFree(supplier);
        checkForEqualTests(myLazy);
    }

    @Test
    public void dataRaceTestLazyMultiThreadLockFree() {
        final Random myRandom = new Random();
        Supplier<Integer> mySupplier = createMultiThreadSupplier(myRandom);
        Lazy<Integer> myLazy = LazyFactory.createLazyMultiThreadLockFree(mySupplier);

        createAndCheckThreads(myLazy);

    }
}
