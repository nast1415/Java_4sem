package ru.spbau.mit;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Created by Анастасия on 10.04.2016.
 */
public class FileDescriptor {
    public static final int PART_SIZE = 1024 * 1024 * 10; //Size of one part of the file - 10M

    private boolean hasFileGotId;
    private int fileId;
    private long fileSize;
    private String fileName;

    //Constructor for our class with id
    public FileDescriptor(int id, long size, String name) {
        fileId = id;
        hasFileGotId = true;
        fileSize = size;
        fileName = name;
    }

    //Constructor for our class without id
    public FileDescriptor(long size, String name) {
        hasFileGotId = false;
        fileSize = size;
        fileName = name;
    }

    public boolean hasFileGotId() {
        return hasFileGotId;
    }

    public int getFileId() {
        return fileId;
    }

    public long getFileSize() {
        return fileSize;
    }

    public String getFileName() {
        return fileName;
    }

    public void setId(int id) {
        fileId = id;
        hasFileGotId = true;
    }

    public int getNumberOfTheParts() {
        /*
         * We need to add PART_SIZE to fileSize because our last part can be less than 10M
         * and we need to subtract 1 because our last part can be 10M
         */
        return (int) (fileSize + PART_SIZE - 1) / PART_SIZE;
    }

    public int getPartSize(int partId) {
        /*
         * If we want to get size of the any part except last part, we return 10M
         * or if PART_SIZE is divisor of fileSize, we also return 10M
         */
        if ((partId < getNumberOfTheParts() - 1) || (fileSize % PART_SIZE == 0)) {
            return PART_SIZE;
        }
        return (int) fileSize % PART_SIZE;
    }

    public void writeInfoToOutputStream(DataOutputStream outputStream) throws IOException {
        //If the file has id, we'll write it
        if (hasFileGotId) {
            outputStream.writeInt(fileId);
        }
        //Write size of file and it's name
        outputStream.writeLong(fileSize);
        outputStream.writeUTF(fileName);
    }

    public static FileDescriptor readInfoFromInputStream(DataInputStream inputStream, boolean hasFileGotId)
            throws IOException {
        //We create FileDescriptor object in two different ways, depends on has our file got id or not
        if (hasFileGotId) {
            int fileId = inputStream.readInt();
            long fileSize = inputStream.readLong();
            String fileName = inputStream.readUTF();
            return new FileDescriptor(fileId, fileSize, fileName);
        } else {
            long fileSize = inputStream.readLong();
            String fileName = inputStream.readUTF();
            return new FileDescriptor(fileSize, fileName);
        }
    }

}
