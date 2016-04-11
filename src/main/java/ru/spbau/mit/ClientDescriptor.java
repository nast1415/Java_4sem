package ru.spbau.mit;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * Created by Анастасия on 10.04.2016.
 */
public class ClientDescriptor {
    private InetSocketAddress address;
    private List<Integer> idList;

    ClientDescriptor(InetSocketAddress address, List<Integer> idList) {
        this.address = address;
        this.idList = idList;
    }

    InetSocketAddress getAddress() {
        return address;
    }

    List<Integer> getIdList() {
        return idList;
    }

    public void writeInfoToOutputStream(DataOutputStream outputStream) throws IOException {
        //Write address to output stream
        ReadWriteHelper.writeAddress(outputStream, address);
        //Write idList to output stream
        ReadWriteHelper.writeCollection(outputStream, idList, DataOutputStream::writeInt);
    }

    public static ClientDescriptor readInfoFromInputStream(DataInputStream inputStream) throws IOException {
        return new ClientDescriptor(
                //Read address from input stream
                ReadWriteHelper.readAddress(inputStream),
                //Read idList from input stream
                ReadWriteHelper.readCollection(inputStream, new ArrayList<>(), DataInputStream::readInt));
    }

}
