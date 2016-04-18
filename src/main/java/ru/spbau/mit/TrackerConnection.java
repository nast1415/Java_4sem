package ru.spbau.mit;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Created by Анастасия on 11.04.2016.
 */
public class TrackerConnection extends Connection {

    public static final int PORT = 8081;
    public static final int UPDATE_DELAY = 60 * 1000;

    public static final int LIST_REQUEST = 1;
    public static final int UPLOAD_REQUEST = 2;
    public static final int SOURCES_REQUEST = 3;
    public static final int UPDATE_REQUEST = 4;

    //Constructor for our class same as constructor for Connection class
    protected TrackerConnection(Socket socket) throws IOException {
        super(socket);
    }

    //Methods related to list() function

    public void sendListRequest() throws IOException {
        DataOutputStream outputStream = getOutputStream();
        outputStream.writeByte(LIST_REQUEST);
        outputStream.flush();
    }

    public void writeListResponse(Collection<FileDescriptor> files) throws IOException {
        writeCollection(files, (outputStream, fileDescriptor) -> {
            if (!fileDescriptor.hasFileGotId()) {
                throw new IllegalStateException("Uploaded files must have id.");
            }
            fileDescriptor.writeInfoToOutputStream(outputStream);
        });
        getOutputStream().flush();
    }

    public List<FileDescriptor> readListResponse() throws IOException {
        return readCollection(new ArrayList<>(),
                (inputStream) -> FileDescriptor.readInfoFromInputStream(inputStream, true));
    }

    //Methods related to upload() function

    public void sendUploadRequest(FileDescriptor file) throws IOException {
        if (file.hasFileGotId()) {
            throw new IllegalStateException("File, we want to upload, can't have id!");
        }
        DataOutputStream outputStream = getOutputStream();
        outputStream.writeByte(UPLOAD_REQUEST);
        file.writeInfoToOutputStream(outputStream);
        outputStream.flush();
    }

    public FileDescriptor readUploadRequest() throws IOException {
        return FileDescriptor.readInfoFromInputStream(getInputStream(), false);
    }

    public void writeUploadResponse(int fileId) throws IOException {
        DataOutputStream outputStream = getOutputStream();
        outputStream.writeInt(fileId);
        outputStream.flush();
    }

    public int readUploadResponse() throws IOException {
        return getInputStream().readInt();
    }

    //Methods related to sources() function

    public void sendSourcesRequest(Collection<Integer> idList) throws IOException {
        if (idList.size() == 0) {
            throw new IllegalStateException("Error! idList is empty!");
        }
        DataOutputStream outputStream = getOutputStream();
        outputStream.writeByte(SOURCES_REQUEST);
        writeCollection(idList, DataOutputStream::writeInt);
        outputStream.flush();
    }

    public List<Integer> readSourcesRequest() throws IOException {
        return readCollection(new ArrayList<>(), DataInputStream::readInt);
    }

    public void writeSourcesResponse(Collection<InetSocketAddress> addresses) throws IOException {
        writeCollection(addresses, ReadWriteHelper::writeAddress);
    }

    public List<InetSocketAddress> readSourcesResponse() throws IOException {
        return readCollection(new ArrayList<>(), ReadWriteHelper::readAddress);
    }

    //Methods related to update() function

    public void sendUpdateRequest(ClientDescriptor clientDescriptor) throws IOException {
        if (clientDescriptor.getIdList().size() == 0) {
            throw new IllegalStateException("Error! idList is empty!");
        }
        DataOutputStream outputStream = getOutputStream();
        outputStream.writeByte(UPDATE_REQUEST);
        clientDescriptor.writeInfoToOutputStream(outputStream);
        outputStream.flush();
    }

    public ClientDescriptor readUpdateRequest() throws IOException {
        return ClientDescriptor.readInfoFromInputStream(getInputStream());
    }

    public void writeUpdateResponse(boolean isSuccessful) throws IOException {
        DataOutputStream outputStream = getOutputStream();
        outputStream.writeBoolean(isSuccessful);
        outputStream.flush();
    }

    public boolean readUpdateResponse() throws IOException {
        return getInputStream().readBoolean();
    }
}
