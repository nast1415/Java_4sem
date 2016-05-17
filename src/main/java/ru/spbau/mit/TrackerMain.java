package ru.spbau.mit;

import java.io.IOException;
import java.nio.file.Paths;

public abstract class TrackerMain {
        public static void main(String[] args) {
            try {
                Tracker tracker = new Tracker(Paths.get(""));

                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    try {
                        tracker.close();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }));
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
}
