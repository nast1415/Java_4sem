package ru.spbau.mit;

import org.apache.commons.io.FileUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * Created by Анастасия on 11.04.2016.
 */
public class Tests {
    private static final Path EXAMPLE_PATH = Paths.get("src", "test", "mainFile");
    private static final Path TRACKER_DIR = Paths.get("test", "tracker");
    private static final Path CLIENT1_DIR = Paths.get("test", "client-01");
    private static final Path CLIENT2_DIR = Paths.get("test", "client-02");
    private static final Path CLIENT3_DIR = Paths.get("test", "client-03");
    private static final long TIME_LIMIT = 70 * 1000L;

    @Test
    public void listAndUploadTest() throws Throwable {
        try (
                Tracker tracker = new Tracker(TRACKER_DIR);
                ClientInfo clientInfo1 = new ClientInfo("localhost", CLIENT1_DIR);
                ClientInfo clientInfo2 = new ClientInfo("localhost", CLIENT2_DIR)
        ) {
            Client client1 = new Client(clientInfo1);
            Client client2 = new Client(clientInfo2);
            assertAllCollectionEquals(Collections.emptyList(), client1.list(), client2.list());

            FileDescriptor descriptor1 = client1.newFile(EXAMPLE_PATH);
            FileDescriptor descriptor2 = client2.newFile(EXAMPLE_PATH);
            assertNotEquals("Ids should be different", descriptor1.getFileId(), descriptor2.getFileId());

            assertAllCollectionEquals(Arrays.asList(descriptor1, descriptor2), client1.list(), client2.list());
        }
    }

    @Test
    public void listConsistencyTest() throws Throwable {
        try (ClientInfo clientInfo1 = new ClientInfo("localhost", CLIENT1_DIR)) {
            Client client1 = new Client(clientInfo1);
            FileDescriptor descriptor;
            try (Tracker tracker = new Tracker(TRACKER_DIR)) {
                descriptor = client1.newFile(EXAMPLE_PATH);
            }

            List<FileDescriptor> list;
            try (Tracker tracker = new Tracker(TRACKER_DIR)) {
                list = client1.list();
            }
            assertEquals(Collections.singletonList(descriptor), list);
        }
    }

    @Test(timeout = TIME_LIMIT)
    public void testDownload() throws Throwable {
        final DownloadWaiter waiter2 = new DownloadWaiter();
        final DownloadWaiter waiter3 = new DownloadWaiter();
        FileDescriptor descriptor;
        try (
                Tracker tracker = new Tracker(TRACKER_DIR);
                ClientInfo clientInfo2 = new ClientInfo("localhost", CLIENT2_DIR)
        ) {
            Client client2 = new Client(clientInfo2);
            RunningClient runningClient2 = new RunningClient(clientInfo2);

            try (ClientInfo clientInfo1 = new ClientInfo("localhost", CLIENT1_DIR)) {
                Client client1 = new Client(clientInfo1);
                descriptor = client1.newFile(EXAMPLE_PATH);
                assertTrue(client2.get(descriptor.getFileId()));
                RunningClient runningClient1 = new RunningClient(clientInfo1);
                // Seeding
                runningClient1.startingRun(null);
                // Leeching
                runningClient2.startingRun(waiter2);

                //System.err.println("after starting run");

                synchronized (waiter2) {
                    while (!waiter2.ready) {
                        waiter2.wait();
                    }
                }
                //System.err.println("before first shutdown");
                runningClient1.shutdown();
                //System.err.println("after first shutdown");
            }

            //Testing that now client2 seeding
            try (ClientInfo clientInfo3 = new ClientInfo("localhost", CLIENT3_DIR)) {
                Client client3 = new Client(clientInfo3);
                assertTrue(client3.get(descriptor.getFileId()));

                RunningClient runningClient3 = new RunningClient(clientInfo3);
                //And leeching
                runningClient3.startingRun(waiter3);
                synchronized (waiter3) {
                    while (!waiter3.ready) {
                        waiter3.wait();
                    }
                }
            }

            runningClient2.shutdown();
        }

        //System.err.println("before downloaded path");
        Path downloadedPath = Paths.get(
                "downloads",
                Integer.toString(descriptor.getFileId()),
                EXAMPLE_PATH.getFileName().toString()
        );
        assertTrue("Downloaded file is different!", FileUtils.contentEquals(
                EXAMPLE_PATH.toFile(),
                CLIENT2_DIR.resolve(downloadedPath).toFile()
        ));
        /*assertTrue("Downloaded file is different!", FileUtils.contentEquals(
                EXAMPLE_PATH.toFile(),
                CLIENT3_DIR.resolve(downloadedPath).toFile()
        ));*/
    }

    @Before
    @After
    public void clear() throws IOException {
        clearDirectory("test");
    }

    private void clearDirectory(String name) throws IOException {
        Path path = Paths.get(name);
        if (!Files.exists(path)) {
            return;
        }

        Files.walkFileTree(path, new Deleter());
    }

    private void assertAllCollectionEquals(Collection<?> expected, Collection<?>... others) {
        for (Collection<?> other : others) {
            assertEquals(expected, other);
        }
    }

    private static class Deleter extends SimpleFileVisitor<Path> {
        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
            Files.delete(file);
            return super.visitFile(file, attrs);
        }

        @Override
        public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
            Files.delete(dir);
            return super.postVisitDirectory(dir, exc);
        }
    }

    private static final class DownloadWaiter implements RunningClient.StatusCallbacks {
        private boolean ready = false;

        private DownloadWaiter() {
        }

        @Override
        public void onTrackerUpdated(boolean result, Throwable e) {
        }

        @Override
        public void onDownloadIssue(FileDescriptor descriptor, String message, Throwable e) {
        }

        @Override
        public void onDownloadStart(FileDescriptor descriptor) {
        }

        @Override
        public void onDownloadPart(FileDescriptor descriptor, int partId) {
        }

        @Override
        public void onDownloadComplete(FileDescriptor descriptor) {
            synchronized (this) {
                ready = true;
                notify();
            }
        }

        @Override
        public void onPeerToPeerServerIssue(Throwable e) {
        }
    }
}
