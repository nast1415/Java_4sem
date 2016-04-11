package ru.spbau.mit;

import java.io.*;
import java.net.Socket;

/**
 * Created by Анастасия on 11.04.2016.
 */
public class PeerToPeerConnection extends Connection {
    public static final int STAT_REQUEST = 1;
    public static final int GET_REQUEST = 2;
    private static final int BUFFER_SIZE = 4096;

    protected PeerToPeerConnection(Socket socket) throws IOException {
        super(socket);
    }

    //Methods related to stat() function

    public void statRequest(int fileId) throws IOException {
        DataOutputStream outputStream = getOutputStream();
        outputStream.writeByte(STAT_REQUEST);
        outputStream.writeInt(fileId);
        outputStream.flush();
    }

    public int readStatRequest() throws IOException {
        return getInputStream().readInt();
    }

    public void getStatResponse(PartsBitset parts) throws IOException {
        DataOutputStream outputStream = getOutputStream();
        parts.writeDataToOutputStream(outputStream);
        outputStream.flush();
    }

    public PartsBitset readStatResponse(int size) throws IOException {
        return PartsBitset.readDataFromInputStream(getInputStream(), size);
    }

    //Methods related to get() function

    public void getRequest(Request request) throws IOException {
        DataOutputStream outputStream = getOutputStream();
        outputStream.writeByte(GET_REQUEST);
        request.writeRequestToOutputStream(outputStream);
        outputStream.flush();
    }

    public Request readGetRequest() throws IOException {
        return Request.readRequestFromInputStream(getInputStream());
    }

    public void writeGetResponse(RandomAccessFile from, int partId, FileDescriptor descriptor) throws IOException {
        from.seek(FileDescriptor.PART_SIZE * partId);
        int amount = descriptor.getPartSize(partId);

        DataOutputStream outputStream = getOutputStream();
        byte[] buffer = new byte[BUFFER_SIZE];
        while (amount > 0) {
            int read = from.read(buffer, 0, Math.min(amount, BUFFER_SIZE));
            if (read == -1) {
                throw new EOFException("File is shorter than recorded size.");
            }
            amount -= read;
            outputStream.write(buffer, 0, read);
        }
        outputStream.flush();
    }

    public void readGetResponse(RandomAccessFile to, int partId, FileDescriptor descriptor) throws IOException {
        to.seek(FileDescriptor.PART_SIZE * partId);
        int amount = descriptor.getPartSize(partId);

        DataInputStream dis = getInputStream();
        byte[] buffer = new byte[BUFFER_SIZE];
        while (amount > 0) {
            int read = dis.read(buffer, 0, Math.min(amount, BUFFER_SIZE));
            if (read == -1) {
                throw new EOFException("Cannot read the end of the file from socket.");
            }
            amount -= read;
            to.write(buffer, 0, read);
        }
    }

}
