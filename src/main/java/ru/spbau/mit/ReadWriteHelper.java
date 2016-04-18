package ru.spbau.mit;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.Writer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Collection;

/**
 * Created by Анастасия on 10.04.2016.
 */

/**
 * This class contains functions that help us to read and write to/from output/input stream address and collections
 */
public abstract class ReadWriteHelper {
    private static final int IP_LENGTH = 4; //We need it to read IP from the DataInputStream while reading address

    public static void writeAddress(DataOutputStream outputStream, InetSocketAddress address) throws IOException {
        outputStream.write(address.getAddress().getAddress()); //Write IP address to the output stream
        outputStream.writeShort(address.getPort()); //Write port number to the output stream
    }

    public static InetSocketAddress readAddress(DataInputStream inputStream) throws IOException {
        //Read IP address
        byte[] buffer = new byte[IP_LENGTH];
        for (int i = 0; i < IP_LENGTH; i++) {
            buffer[i] = inputStream.readByte();
        }
        //Read port number
        int port = inputStream.readUnsignedShort();
        //Create InetSocketAddress object using InetSocketAddress(InetAddress, int) constructor
        return new InetSocketAddress(InetAddress.getByAddress(buffer), port);
    }

    //Next interfaces are needed in reading/writing collections with parameter T
    public interface Writer<T> {
        void write(DataOutputStream outputStream, T value) throws IOException;
    }

    public interface Reader<T> {
        T read(DataInputStream inputStream) throws IOException;
    }

    public static <T> void writeCollection(DataOutputStream outputStream, Collection<T> collection,
                                           Writer<? super T> writer) throws IOException {
        //Write size of collection
        outputStream.writeInt(collection.size());
        //Write all elements of collection
        for (T element : collection) {
            writer.write(outputStream, element);
        }
    }

    public static <T, R extends Collection<T>> R readCollection(DataInputStream inputStream, R collection,
                                                                Reader<? extends T> reader) throws IOException {
        //Read size of collection
        int collectionSize = inputStream.readInt();
        //Read all elements and add to collection
        for (int i = 0; i < collectionSize; i++) {
            collection.add(reader.read(inputStream));
        }
        return collection;
    }

}
