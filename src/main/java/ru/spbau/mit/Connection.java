package ru.spbau.mit;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Collection;

/**
 * Created by Анастасия on 10.04.2016.
 */
public abstract class Connection implements AutoCloseable {
    private Socket socket;
    private DataInputStream inputStream;
    private DataOutputStream outputStream;

    //Constructor for our abstract class
    protected Connection(Socket socket) throws IOException {
        this.socket = socket;
        //Creating new input and output streams using socket
        inputStream = new DataInputStream(socket.getInputStream());
        outputStream = new DataOutputStream(socket.getOutputStream());
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException ignoredException) {
        }
    }

    public DataOutputStream getOutputStream() {
        return outputStream;
    }

    public DataInputStream getInputStream() {
        return inputStream;
    }

    //We need this function in Tracker class in doUpdate method
    public String getHost() {
        return ((InetSocketAddress) socket.getRemoteSocketAddress()).getHostName();
    }

    /*
     * writeCollection function for the connection, using ReadWriteHelper writeConnection function
     * we need it in methods of class TrackerConnection
     */
    protected <T> void writeCollection(Collection<T> collection,
                                           ReadWriteHelper.Writer<? super T> writer) throws IOException {
        ReadWriteHelper.writeCollection(outputStream, collection, writer);
    }

    //readCollection function for the connection, using ReadWriteHelper readConnection function
    protected <T, R extends Collection<T>> R readCollection(R collection,
                                                                ReadWriteHelper.Reader<? extends T> reader)
            throws IOException {
        return ReadWriteHelper.readCollection(inputStream, collection, reader);
    }

    //We need this function in listenConnection function in class Tracker
    public int readRequest() throws IOException {
        return inputStream.readUnsignedByte();
    }
}
