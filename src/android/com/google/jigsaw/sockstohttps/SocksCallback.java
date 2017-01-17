package com.google.jigsaw.sockstohttps;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;

/**
 * Created by bemasc on 1/12/17.
 */
public interface SocksCallback {
    /**
     * Forward socket to destination, and return the bound address or null on failure.
     * @param up
     * @param destination
     * @return
     */
    InputStream connect(InputStream up, InetSocketAddress destination);
}
