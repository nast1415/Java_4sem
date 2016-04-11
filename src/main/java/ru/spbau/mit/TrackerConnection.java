package ru.spbau.mit;

import java.io.IOException;
import java.net.Socket;

/**
 * Created by Анастасия on 11.04.2016.
 */
public class TrackerConnection extends Connection {

    protected TrackerConnection(Socket socket) throws IOException {
        super(socket);
    }
}
