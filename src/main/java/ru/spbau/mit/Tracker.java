package ru.spbau.mit;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
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
public class Tracker implements AutoCloseable {
    private static final String STATE_FILE = "tracker-state.dat";

    private Path workingDirectory;
    private ExecutorService threadPool;
    private ScheduledExecutorService scheduler;
    private ServerSocket serverSocket;
    private List<FileDescriptor> files;
    private Map<Integer, Set<ClientDescriptor>> seeders;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    public Tracker(Path workingDirectory) throws Exception {
        try (MyLock myLock = MyLock.lock(lock.writeLock())) {
            this.workingDirectory = workingDirectory;
            serverSocket = new ServerSocket(TrackerConnection.PORT);
            threadPool = Executors.newCachedThreadPool();
            scheduler = Executors.newScheduledThreadPool(1);
            load();
        }
        threadPool.submit(this::work);
    }

    @Override
    public void close() throws Exception {
        try (MyLock myLock = MyLock.lock(lock.writeLock())) {
            serverSocket.close();
        }
        threadPool.shutdown();
        scheduler.shutdown();
        store();
    }

    private void work() {
        while (true) {
            try {
                Socket socket = serverSocket.accept();
                if (socket == null) {
                    return;
                }
                //We listen the connection for a request byte
                threadPool.submit(() -> listenConnection(socket));
            } catch (IOException e) {
                e.printStackTrace();
                break;
            }
        }
    }

    private void listenConnection(Socket socket) {
        try (TrackerConnection connection = new TrackerConnection(socket)) {
            //We read request byte and choose right function
            int request = connection.readRequest();
            switch (request) {
                case TrackerConnection.LIST_REQUEST:
                    list(connection);
                    break;
                case TrackerConnection.UPLOAD_REQUEST:
                    upload(connection);
                    break;
                case TrackerConnection.SOURCES_REQUEST:
                    sources(connection);
                    break;
                case TrackerConnection.UPDATE_REQUEST:
                    update(connection);
                    break;
                default:
                    System.err.printf("Request: %d is not correct!\n", request);
                    break;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void load() throws IOException {
        Path path = workingDirectory.resolve(STATE_FILE);
        if (Files.exists(path)) {
            try (DataInputStream inputStream = new DataInputStream(Files.newInputStream(path))) {
                files = ReadWriteHelper.readCollection(inputStream, new ArrayList<>(),
                        inputStream1 -> FileDescriptor.readInfoFromInputStream(inputStream1, true));
            }
        } else {
            files = new ArrayList<>();
        }
        seeders = new HashMap<>();
    }

    private void store() throws IOException {
        Path path = workingDirectory.resolve(STATE_FILE);
        if (!Files.exists(path)) {
            Files.createDirectories(workingDirectory);
            Files.createFile(path);
        }
        try (DataOutputStream outputStream = new DataOutputStream(Files.newOutputStream(path))) {
            ReadWriteHelper.writeCollection(outputStream, files,
                    (outputStream1, w) -> w.writeInfoToOutputStream(outputStream1));
        }
    }

    private void list(TrackerConnection connection) throws Exception {
        try (MyLock myLock = MyLock.lock(lock.readLock())) {
            connection.writeListResponse(files);
        }
    }

    private void upload(TrackerConnection connection) throws Exception {
        FileDescriptor fileDescriptor = connection.readUploadRequest();
        try (MyLock myLock = MyLock.lock(lock.writeLock())) {
            int newId = files.size();
            fileDescriptor.setId(newId);
            files.add(fileDescriptor);
        }
        connection.writeUploadResponse(fileDescriptor.getFileId());
    }

    private void sources(TrackerConnection connection) throws Exception {
        List<Integer> request = connection.readSourcesRequest();
        List<InetSocketAddress> result;
        try (MyLock myLock = MyLock.lock(lock.readLock())) {
            result = request.stream()
                    .flatMap(i -> seeders
                            .getOrDefault(i, Collections.emptySet())
                            .stream()
                    )
                    .distinct()
                    .map(ClientDescriptor::getAddress)
                    .collect(Collectors.toList());
        }
        connection.writeSourcesResponse(result);
    }

    private void update(TrackerConnection connection) throws Exception {
        ClientDescriptor receivedClientDescriptor = connection.readUpdateRequest();
        ClientDescriptor clientDescriptor = new ClientDescriptor(
                new InetSocketAddress(connection.getHost(), receivedClientDescriptor.getAddress().getPort()),
                receivedClientDescriptor.getIdList()
        );
        try (MyLock myLock = MyLock.lock(lock.writeLock())) {
            for (int id : clientDescriptor.getIdList()) {
                if (seeders.get(id) == null) {
                    seeders.put(id, new HashSet<>());
                }
                seeders.get(id).add(clientDescriptor);
            }
        }

        scheduler.schedule(() -> {
            try (MyLock myLock = MyLock.lock(lock.writeLock())) {
                for (int id : clientDescriptor.getIdList()) {
                    seeders.get(id).remove(clientDescriptor);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, TrackerConnection.UPDATE_DELAY, TimeUnit.MILLISECONDS);

        connection.writeUpdateResponse(true);
    }


}
