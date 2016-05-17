package ru.spbau.mit;

import java.io.IOException;
import java.net.Socket;

public abstract class ClientBase {
    protected ClientInfo info;

    public ClientBase(ClientInfo info) {
        this.info = info;
    }

    protected TrackerConnection connectToTracker() throws IOException {
        return new TrackerConnection(new Socket(info.host, TrackerConnection.PORT));
    }
}
