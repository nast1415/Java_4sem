package ru.spbau.mit;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

/**
 * Created by Анастасия on 11.04.2016.
 */
public class Client implements AutoCloseable {
    private static final long DELAY = 1000;
    private static final String info_FILE = "client-info.dat";
    private static final String DOWNLOADS_DIR = "downloads";

    private Path workingDirectory;
    private ReadWriteLock lock = new ReentrantReadWriteLock();
    private Map<Integer, FileInfo> files;
    private String host;

    private StatusCallbacks callbacks = null;

    // For run() function
    private boolean isRunning = false;
    private ServerSocket serverSocket;
    private ExecutorService threadPool;
    private ScheduledExecutorService scheduler;

    public Client(String host, Path workingDirectory) throws IOException {
        this.host = host;
        this.workingDirectory = workingDirectory;
        load();
    }

    @Override
    public void close() throws Exception {
        if (isRunning) {
            try (MyLock myLock = MyLock.lock(lock.writeLock())) {
                isRunning = false;
                serverSocket.close();
            }
            threadPool.shutdown();
            scheduler.shutdown();
        }
        store();
    }

    private static final class FileInfo {
        private ReadWriteLock fileLock = new ReentrantReadWriteLock();
        private FileDescriptor descriptor;
        private PartsBitset parts;
        private Path localPath;

        private FileInfo(FileDescriptor descriptor, Path localPath, Path workingDirectory) throws IOException {
            this(descriptor, new PartsBitset(descriptor.getNumberOfTheParts(), localPath != null),
                    localPath, workingDirectory);
        }

        private FileInfo(FileDescriptor descriptor, PartsBitset parts, Path localPath,
                         Path workingDirectory) throws IOException {
            this.descriptor = descriptor;
            this.parts = parts;
            if (localPath == null) {
                this.localPath = workingDirectory.resolve(Paths.get(
                        DOWNLOADS_DIR,
                        Integer.toString(descriptor.getFileId()),
                        descriptor.getFileName()
                ));
                Files.createDirectories(this.localPath.getParent());
                try (RandomAccessFile file = new RandomAccessFile(this.localPath.toString(), "rw")) {
                    file.setLength(descriptor.getFileSize());
                }
            } else {
                this.localPath = localPath;
            }
        }

        private void writeDataToOutputStream(DataOutputStream outputStream) throws IOException {
            descriptor.writeInfoToOutputStream(outputStream);
            parts.writeDataToOutputStream(outputStream);
            outputStream.writeUTF(localPath.toString());
        }

        private static FileInfo readDataFromInputStream(DataInputStream inputStream) throws IOException {
            FileDescriptor fileDescriptor = FileDescriptor.readInfoFromInputStream(inputStream, true);
            PartsBitset parts = PartsBitset.readDataFromInputStream(inputStream, fileDescriptor.getNumberOfTheParts());
            String localPath = inputStream.readUTF();
            return new FileInfo(fileDescriptor, parts, Paths.get(localPath), null);
        }
    }

    public interface StatusCallbacks {
        void onTrackerUpdated(boolean result, Throwable e);

        void onDownloadIssue(FileDescriptor descriptor, String message, Throwable e);

        void onDownloadStart(FileDescriptor descriptor);

        void onDownloadPart(FileDescriptor descriptor, int partId);

        void onDownloadComplete(FileDescriptor descriptor);

        void onPeerToPeerServerIssue(Throwable e);
    }

    public void setCallbacks(StatusCallbacks callbacks) {
        this.callbacks = callbacks;
    }

    public List<FileDescriptor> list() throws IOException {
        try (TrackerConnection connection = connectToTracker()) {
            connection.listOfFilesRequest();
            return connection.readListOfFilesResponse();
        }
    }

    public boolean get(int id) throws Exception {
        if (files.containsKey(id)) {
            return false;
        }
        FileDescriptor serverDescriptor = list().stream()
                .filter(descriptor -> descriptor.getFileId() == id)
                .findAny().orElse(null);
        if (serverDescriptor == null) {
            return false;
        }
        try (MyLock myLock = MyLock.lock(lock.writeLock())) {
            files.put(id, new FileInfo(serverDescriptor, null, workingDirectory));
        }
        return true;
    }

    public FileDescriptor newFile(Path path) throws Exception {
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("File not exists or is not a regular file.");
        }

        FileDescriptor newDescriptor = new FileDescriptor(Files.size(path), path.getFileName().toString());
        try (TrackerConnection connection = connectToTracker()) {
            connection.uploadRequest(newDescriptor);
            int newId = connection.readUploadResponse();
            newDescriptor.setId(newId);
        }
        FileInfo newinfo = new FileInfo(newDescriptor, path, null);
        try (MyLock myLock = MyLock.lock(lock.writeLock())) {
            files.put(newDescriptor.getFileId(), newinfo);
        }
        return newDescriptor;
    }

    public void run() throws Exception {
        try (MyLock myLock = MyLock.lock(lock.writeLock())) {
            isRunning = true;

            threadPool = Executors.newCachedThreadPool();
            scheduler = Executors.newScheduledThreadPool(1);

            // Starting download
            for (FileInfo info : files.values()) {
                if (info.parts.getCount() == info.descriptor.getNumberOfTheParts()) {
                    continue;
                }
                threadPool.submit(() -> {
                    try {
                        download(info);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                });
            }

            // Seeding server starts
            serverSocket = new ServerSocket(0);
            threadPool.submit(this::server);

            // Tracking update loop starts
            scheduler.scheduleAtFixedRate(this::updateTracker, 0, DELAY, TimeUnit.MILLISECONDS);
        } catch (IOException e) {
            threadPool.shutdownNow();
            scheduler.shutdownNow();
            isRunning = false;
            throw e;
        }
    }

    // Protocol requests

    private List<InetSocketAddress> sources(Collection<Integer> files) throws IOException {
        try (TrackerConnection connection = connectToTracker()) {
            connection.sourcesRequest(files);
            return connection.readSourcesResponse();
        }
    }

    private PartsBitset stat(InetSocketAddress seeder, FileInfo info) throws IOException {
        try (PeerToPeerConnection connection = connectToSeeder(seeder)) {
            connection.statRequest(info.descriptor.getFileId());
            return connection.readStatResponse(info.descriptor.getNumberOfTheParts());
        }
    }

    private void get(InetSocketAddress seeder, FileInfo info, int partId) throws IOException {
        try (PeerToPeerConnection connection = connectToSeeder(seeder)) {
            connection.getRequest(new Request(info.descriptor.getFileId(), partId));
            try (RandomAccessFile file = new RandomAccessFile(info.localPath.toString(), "rw")) {
                connection.readGetResponse(file, partId, info.descriptor);
            }
        }
    }

    // Seeding part

    private boolean update(int port) throws Exception {
        List<Integer> availableFiles;
        try (MyLock myLock = MyLock.lock(lock.readLock())) {
            availableFiles = files
                    .values()
                    .stream()
                    .filter(fileinfo -> {
                        try (MyLock myLock1 = MyLock.lock(fileinfo.fileLock.readLock())) {

                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                        return fileinfo.parts.getCount() > 0;
                    })
                    .map(fileInfo -> fileInfo.descriptor.getFileId())
                    .collect(Collectors.toList());
        }
        ClientDescriptor descriptor = new ClientDescriptor(new InetSocketAddress("", port), availableFiles);
        try (TrackerConnection trackerConnection = connectToTracker()) {
            trackerConnection.updateRequest(descriptor);
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
        FileInfo info;
        try (MyLock myLock = MyLock.lock(lock.readLock())) {
            info = files.get(fileId);
        }
        try (MyLock myLock = MyLock.lock(info.fileLock.readLock())) {
            connection.getStatResponse(info.parts);
        }
    }

    private void getRequestFunction(PeerToPeerConnection connection) throws Exception {
        Request request = connection.readGetRequest();
        FileInfo info;
        try (MyLock myLock = MyLock.lock(lock.readLock())) {
            info = files.get(request.getFileId());
        }
        try (MyLock myLock = MyLock.lock(info.fileLock.readLock())) {
            if (!info.parts.get(request.getPartId())) {
                throw new IllegalArgumentException("Cannot get on missing file part.");
            }
        }
        // We already checked that file has requested part, just read it without locking
        try (RandomAccessFile file = new RandomAccessFile(info.localPath.toString(), "r")) {
            connection.writeGetResponse(file, request.getPartId(), info.descriptor);
        }
    }

    // Leeching part

    private void updateTracker(){
        try (MyLock myLock = MyLock.lock(lock.readLock())) {
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

    private void download(FileInfo info) throws Exception {
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

    private void store() throws IOException {
        Path info = workingDirectory.resolve(info_FILE);
        if (!Files.exists(info)) {
            Files.createDirectories(workingDirectory);
            Files.createFile(info);
        }
        try (DataOutputStream outputStream = new DataOutputStream(Files.newOutputStream(info))) {
            ReadWriteHelper.writeCollection(outputStream, files.values(),
                    (outputStream1, w) -> w.writeDataToOutputStream(outputStream1));
        }
    }

    private void load() throws IOException {
        Path info = workingDirectory.resolve(info_FILE);
        if (Files.exists(info)) {
            try (DataInputStream inputStream = new DataInputStream(Files.newInputStream(info))) {
                int size = inputStream.readInt();
                files = new HashMap<>(size);
                while (size > 0) {
                    --size;
                    FileInfo fs = FileInfo.readDataFromInputStream(inputStream);
                    files.put(fs.descriptor.getFileId(), fs);
                }
            }
        } else {
            files = new HashMap<>();
        }
    }

    private TrackerConnection connectToTracker() throws IOException {
        return new TrackerConnection(new Socket(host, TrackerConnection.PORT));
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
