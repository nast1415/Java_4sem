package ru.spbau.mit;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.locks.Lock;

/**
 * Created by Анастасия on 11.04.2016.
 */

//Wrapper over class Lock
public final class MyLock implements AutoCloseable {
    private Lock lock;
    private static final Queue<MyLock> locks = new ArrayDeque<>();

    private MyLock() {};

    //Create our own function lock returns MyLock
    public static MyLock lock(Lock lock) {
        MyLock res;
        synchronized (locks) {
            res = locks.poll(); //Set the head of the locks queue as a result (or null if the queue is empty)
        }

        //If the result is null, we'll create new MyLock
        if (res == null) {
            res = new MyLock();
        }
        res.lock = lock;
        //Acquires the lock
        lock.lock();
        return res;
    }

    @Override
    public void close() throws Exception {
        lock.unlock();
        synchronized (locks) {
            locks.add(this); //When we release the lock, we add current MyLock object to the locks queue
        }
    }
}
