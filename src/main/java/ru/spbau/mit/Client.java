package ru.spbau.mit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class Client extends ClientBase {
    public Client(ClientInfo info) {
        super(info);
    }

    public List<FileDescriptor> list() throws IOException {
        try (TrackerConnection connection = connectToTracker()) {
            connection.sendListRequest();
            return connection.readListResponse();
        }
    }

    public boolean get(int id) throws Exception {
        try (MyLock myLock = MyLock.lock(info.lock.readLock())) {
            if (info.files.containsKey(id)) {
                return false;
            }
        }
        FileDescriptor serverDescriptor = list().stream()
                .filter(descriptor -> descriptor.getFileId() == id)
                .findAny().orElse(null);
        if (serverDescriptor == null) {
            return false;
        }
        try (MyLock myLock = MyLock.lock(info.lock.writeLock())) {
            info.files.put(id, new ClientInfo.FileInfo(serverDescriptor, null, info.workingDirectory));
        }
        return true;
    }

    public FileDescriptor newFile(Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("File not exists or is not a regular file.");
        }

        FileDescriptor newDescriptor = new FileDescriptor(Files.size(path), path.getFileName().toString());
        try (TrackerConnection connection = connectToTracker()) {
            connection.sendUploadRequest(newDescriptor);
            int newId = connection.readUploadResponse();
            newDescriptor = newDescriptor.setId(newId);
        }
        ClientInfo.FileInfo newInfo = new ClientInfo.FileInfo(newDescriptor, path, null);
        try (MyLock myLock = MyLock.lock(info.lock.writeLock())) {
            info.files.put(newDescriptor.getFileId(), newInfo);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return newDescriptor;
    }

}
