package ru.spbau.mit;

import com.sun.xml.internal.ws.policy.privateutil.PolicyUtils;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;
import java.util.stream.Collectors;

public class ClientInfo implements AutoCloseable {
    private static final String INFO_FILE = "client-info.dat";
    private static final String DOWNLOADS_DIR = "downloads";

    Path workingDirectory;
    ReadWriteLock lock = new ReentrantReadWriteLock();
    Map<Integer, FileInfo> files;
    String host;

    public ClientInfo(String host, Path workingDirectory) throws IOException {
        this(workingDirectory);
        this.host = host;
    }

    public ClientInfo(Path workingDirectory) throws IOException {
        this.workingDirectory = workingDirectory;
        load();
    }

    public static void removeInfo(Path workingDirectory) {
        Path info = workingDirectory.resolve(INFO_FILE);
        if (Files.exists(info)) {
            try {
                Files.delete(info);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void close() throws Exception {
        store();
    }

    static final class FileInfo {
        ReadWriteLock fileLock = new ReentrantReadWriteLock();
        FileDescriptor descriptor;
        PartsBitset parts;
        Path localPath;

        FileInfo(FileDescriptor descriptor, Path localPath, Path workingDirectory) throws IOException {
            this(descriptor, new PartsBitset(descriptor.getNumberOfTheParts(), localPath != null),
                    localPath, workingDirectory);
        }

        FileInfo(FileDescriptor descriptor, PartsBitset parts, Path localPath,
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

    private void store() throws IOException {
        Path info = workingDirectory.resolve(INFO_FILE);
        if (!Files.exists(info)) {
            Files.createDirectories(workingDirectory);
            Files.createFile(info);
        }
        try (DataOutputStream outputStream = new DataOutputStream(Files.newOutputStream(info))) {
            outputStream.writeUTF(host);
            ReadWriteHelper.writeCollection(outputStream, files.values(),
                    (outputStream1, w) -> w.writeDataToOutputStream(outputStream1));
        }
    }

    private void load() throws IOException {
        Path info = workingDirectory.resolve(INFO_FILE);
        if (Files.exists(info)) {
            try (DataInputStream inputStream = new DataInputStream(Files.newInputStream(info))) {
                host = inputStream.readUTF();
                files = ReadWriteHelper.readCollection(inputStream, new HashSet<>(), FileInfo::readDataFromInputStream)
                        .stream()
                        .collect(Collectors.toMap(
                                fileInfo -> fileInfo.descriptor.getFileId(),
                                Function.identity()

                        ));
            }
        } else {
            host = "";
            files = new HashMap<>();
        }
    }
}
