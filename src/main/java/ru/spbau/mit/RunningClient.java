package ru.spbau.mit;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class RunningClient extends ClientBase {
    private static final long DELAY = 1000;

    public interface StatusCallbacks {
        void onTrackerUpdated(boolean result, Throwable e);

        void onDownloadIssue(FileDescriptor descriptor, String message, Throwable e);

        void onDownloadStart(FileDescriptor descriptor);

        void onDownloadPart(FileDescriptor descriptor, int partId);

        void onDownloadComplete(FileDescriptor descriptor);

        void onPeerToPeerServerIssue(Throwable e);
    }

    private volatile StatusCallbacks callbacks = null;
    private boolean isRunning = false;
    private ServerSocket serverSocket;
    private ExecutorService threadPool;
    private ScheduledExecutorService scheduler;

    public RunningClient(ClientInfo info) {
        super(info);
    }

    public void startingRun(StatusCallbacks callbacks) throws Exception {
        try {
            isRunning = true;
            this.callbacks = callbacks;

            threadPool = Executors.newCachedThreadPool();
            scheduler = Executors.newScheduledThreadPool(1);

            // Starting download
            try (MyLock myLock = MyLock.lock(info.lock.readLock())) {
                for (ClientInfo.FileInfo fileInfo : info.files.values()) {
                    if (fileInfo.parts.getCount() == fileInfo.descriptor.getNumberOfTheParts()) {
                        continue;
                    }
                    threadPool.submit(() -> {
                        try {
                            download(fileInfo);
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    });
                }
            }

            // Seeding server starts
            serverSocket = new ServerSocket(0);
            threadPool.submit(this::server);

            // Tracking update loop starts
            scheduler.scheduleAtFixedRate(this::updateTracker, 0, DELAY, TimeUnit.MILLISECONDS);

            Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown));
        } catch (IOException e) {
            threadPool.shutdownNow();
            scheduler.shutdownNow();
            isRunning = false;
            throw e;
        }
    }

    public void shutdown() {
        try {
            if (!isRunning) {
                return;
            }
            serverSocket.close();
            threadPool.shutdown();
            scheduler.shutdown();
            info.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // Protocol requests

    private List<InetSocketAddress> sources(Collection<Integer> files) throws IOException {
        try (TrackerConnection connection = connectToTracker()) {
            connection.sendSourcesRequest(files);
            return connection.readSourcesResponse();
        }
    }

    private PartsBitset stat(InetSocketAddress seeder, ClientInfo.FileInfo info) throws IOException {
        try (PeerToPeerConnection connection = connectToSeeder(seeder)) {
            connection.sendStatRequest(info.descriptor.getFileId());
            return connection.readStatResponse(info.descriptor.getNumberOfTheParts());
        }
    }

    private void get(InetSocketAddress seeder, ClientInfo.FileInfo info, int partId) throws IOException {
        try (PeerToPeerConnection connection = connectToSeeder(seeder)) {
            connection.sendGetRequest(new Request(info.descriptor.getFileId(), partId));
            try (RandomAccessFile file = new RandomAccessFile(info.localPath.toString(), "rw")) {
                connection.readGetResponse(file, partId, info.descriptor);
            }
        }
    }

    // Seeding part

    private boolean update(int port) throws Exception {
        List<Integer> availableFiles;
        try (MyLock myLock = MyLock.lock(info.lock.readLock())) {
            availableFiles = info.files
                    .values()
                    .stream()
                    .filter(fileInfo -> {
                        try (MyLock myLock1 = MyLock.lock(fileInfo.fileLock.readLock())) {

                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                        return fileInfo.parts.getCount() > 0;
                    })
                    .map(fileInfo -> fileInfo.descriptor.getFileId())
                    .collect(Collectors.toList());
        }
        ClientDescriptor descriptor = new ClientDescriptor(new InetSocketAddress("", port), availableFiles);
        try (TrackerConnection trackerConnection = connectToTracker()) {
            trackerConnection.sendUpdateRequest(descriptor);
            return trackerConnection.readUpdateResponse();
        }
    }

    private void server() {
        while (true) {
            try {
                Socket socket = serverSocket.accept();
                threadPool.submit(() -> listen(socket));
            } catch (IOException e) {
                notifyPeerToPeerServerIssue(e);
                break;
            }
        }
    }

    private void listen(Socket socket) {
        try (PeerToPeerConnection connection = new PeerToPeerConnection(socket)) {
            int request = connection.readRequest();
            switch (request) {
                case PeerToPeerConnection.STAT_REQUEST:
                    statRequestFunction(connection);
                    break;
                case PeerToPeerConnection.GET_REQUEST:
                    getRequestFunction(connection);
                    break;
                default:
                    throw new IllegalArgumentException(
                            String.format("Request %d from connection is incorrect!.", request)
                    );
            }
        } catch (Exception e) {
            notifyPeerToPeerServerIssue(e);
        }
    }

    private void statRequestFunction(PeerToPeerConnection connection) throws Exception {
        int fileId = connection.readStatRequest();
        ClientInfo.FileInfo fileInfo;
        try (MyLock myLock = MyLock.lock(info.lock.readLock())) {
            fileInfo = info.files.get(fileId);
        }
        try (MyLock myLock = MyLock.lock(fileInfo.fileLock.readLock())) {
            connection.writeStatResponse(fileInfo.parts);
        }
    }

    private void getRequestFunction(PeerToPeerConnection connection) throws Exception {
        Request request = connection.readGetRequest();
        ClientInfo.FileInfo fileInfo;
        try (MyLock myLock = MyLock.lock(info.lock.readLock())) {
            fileInfo = info.files.get(request.getFileId());
        }
        try (MyLock myLock = MyLock.lock(fileInfo.fileLock.readLock())) {
            if (!fileInfo.parts.get(request.getPartId())) {
                throw new IllegalArgumentException("Cannot get on missing file part.");
            }
        }
        // We already checked that file has requested part, just read it without locking
        try (RandomAccessFile file = new RandomAccessFile(fileInfo.localPath.toString(), "r")) {
            connection.writeGetResponse(file, request.getPartId(), fileInfo.descriptor);
        }
    }

    // Leeching part

    private void updateTracker(){
        try (MyLock myLock = MyLock.lock(info.lock.readLock())) {
            if (!isRunning) {
                return;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        try {
            boolean result = update(serverSocket.getLocalPort());
            notifyTrackerUpdated(result, null);
        } catch (Exception e) {
            notifyTrackerUpdated(false, e);
        }
    }

    private void download(ClientInfo.FileInfo info) throws Exception {
        List<InetSocketAddress> seeders = null;
        int currentSeeder = 0;
        PartsBitset seederParts = null;
        int canOffer = 0;
        notifyDownloadStart(info.descriptor);
        while (true) {
            try (MyLock myLock = MyLock.lock(info.fileLock.readLock())) {
                if (!isRunning) {
                    return;
                }
                if (info.parts.getCount() == info.descriptor.getNumberOfTheParts()) {
                    notifyDownloadComplete(info.descriptor);
                    return;
                }
            }

            if (seeders == null || seeders.size() == 0) {
                try {
                    seeders = sources(Collections.singletonList(info.descriptor.getFileId()));
                    currentSeeder = -1;
                    canOffer = 0;
                } catch (IOException e) {
                    notifyDownloadIssue(info.descriptor, "Failed to fetch seeders.", e);
                    delay(DELAY);
                    continue;
                }
            }
            if (seeders == null || seeders.size() == 0) {
                notifyDownloadIssue(info.descriptor, "No seeders.", null);
                delay(DELAY);
                continue;
            }

            if (canOffer == 0 && currentSeeder + 1 < seeders.size()) {
                currentSeeder++;
                try {
                    seederParts = stat(seeders.get(currentSeeder), info);
                } catch (IOException e) {
                    notifyDownloadIssue(info.descriptor, String.format(
                            "Failed to stat seeder %s",
                            seeders.get(currentSeeder).toString()
                    ), e);
                    continue;
                }
                try (MyLock myLock = MyLock.lock(info.fileLock.readLock())) {
                    seederParts.subtract(info.parts);
                }
                canOffer = seederParts.getCount();
            }

            if (canOffer == 0) {
                if (currentSeeder == seeders.size() - 1) {
                    seeders = null;
                }
                notifyDownloadIssue(info.descriptor, "No one seed remaining parts.", null);
                delay(DELAY);
                continue;
            }

            int partId = 0;
            if (canOffer > 0) {
                partId = seederParts.getFirstBitAtLeast(partId);
                try {
                    get(seeders.get(currentSeeder), info, partId);
                } catch (IOException e) {
                    notifyDownloadIssue(info.descriptor, String.format(
                            "Downloading error: part %d from seeder %s.",
                            partId,
                            seeders.get(currentSeeder).toString()
                    ), e);
                    delay(DELAY);
                }
                boolean needUpdateTracker = false;
                try (MyLock myLock = MyLock.lock(info.fileLock.writeLock())) {
                    info.parts.set(partId, true);
                    if (info.parts.getCount() == 1) {
                        needUpdateTracker = true;
                    }
                }
                seederParts.set(partId, false);
                canOffer--;
                if (needUpdateTracker) {
                    updateTracker();
                }
                notifyDownloadPart(info.descriptor, partId);
            }
        }
    }

    private PeerToPeerConnection connectToSeeder(InetSocketAddress seeder) throws IOException {
        return new PeerToPeerConnection(new Socket(seeder.getAddress(), seeder.getPort()));
    }

    private void delay(long time) {
        try {
            Thread.sleep(time);
        } catch (InterruptedException ignored) {
        }
    }

    private void notifyTrackerUpdated(boolean result, Throwable e) {
        if (callbacks != null) {
            callbacks.onTrackerUpdated(result, e);
        }
    }

    private void notifyDownloadIssue(FileDescriptor descriptor, String message, Throwable e) {
        if (callbacks != null) {
            callbacks.onDownloadIssue(descriptor, message, e);
        }
    }

    private void notifyDownloadComplete(FileDescriptor descriptor) {
        if (callbacks != null) {
            callbacks.onDownloadComplete(descriptor);
        }
    }

    private void notifyPeerToPeerServerIssue(Throwable e) {
        if (callbacks != null) {
            callbacks.onPeerToPeerServerIssue(e);
        }
    }

    private void notifyDownloadStart(FileDescriptor descriptor) {
        if (callbacks != null) {
            callbacks.onDownloadStart(descriptor);
        }
    }

    private void notifyDownloadPart(FileDescriptor descriptor, int partId) {
        if (callbacks != null) {
            callbacks.onDownloadPart(descriptor, partId);
        }
    }

}
