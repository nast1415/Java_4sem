package ru.spbau.mit;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Created by Анастасия on 10.04.2016.
 */
public class Request {
    private int fileId;
    private int partId;

    public Request(int fileId, int partId) {
        this.fileId = fileId;
        this.partId = partId;
    }

    public int getFileId() {
        return fileId;
    }

    public int getPartId() {
        return partId;
    }

    public void writeRequestToOutputStream(DataOutputStream outputStream) throws IOException {
        outputStream.writeInt(fileId);
        outputStream.writeInt(partId);
    }

    public Request readRequestFromInputStream(DataInputStream inputStream) throws IOException {
        return new Request(
                inputStream.readInt(), //Read fileId
                inputStream.readInt() //Read partId
        );
    }
}
