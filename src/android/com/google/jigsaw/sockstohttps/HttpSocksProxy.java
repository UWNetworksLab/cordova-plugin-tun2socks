package com.google.jigsaw.sockstohttps;

import android.util.Log;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * Created by bemasc on 1/12/17.
 */
public class HttpSocksProxy {
    private static String LOG_TAG = "HttpSocksProxy";
    public int start(String scheme, String address, int port, String username, String password) {
        final ServerSocket serverSocket;
        try {
            serverSocket = new ServerSocket(0);
        } catch (IOException e) {
            Log.e("Failed to listen", e.toString());
            return -1;
        }
        final SocksCallback callback = new BasicHttpSocksForwarder(scheme, address, port, username,
                password);

        new Thread(
            new Runnable() {
                public void run() {
                    try {
                        while (true) {
                            Socket socket = serverSocket.accept();
                            Log.d(LOG_TAG, "New client socket!");
                            if (!SocksBinder.negotiate(socket, callback)) {
                                Log.w(LOG_TAG, "Negotiation failed!");
                                socket.close();
                            }
                        }
                    } catch (IOException e) {
                        Log.e("Failure in accept loop", e.toString());
                    }
                }
            }, "Accept loop for " + address)
            .start();
        return serverSocket.getLocalPort();
    }
}
