package com.google.jigsaw.sockstohttps;

import android.renderscript.ScriptGroup;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;

/**
 * Created by bemasc on 1/12/17.
 */
public class SocksBinder {
    private static String LOG_TAG = "SocksBinder";
    private static boolean doAuthHandshake(Socket socket) throws IOException {
        InputStream in = socket.getInputStream();
        int version = in.read();
        if (version != 5) {
            Log.w(LOG_TAG, "Wrong version: " + version);
            return false;
        }
        int numAuthMethods = in.read();
        if (numAuthMethods != 1) {
            Log.w(LOG_TAG, "Wrong number of auth methods: " + numAuthMethods);
            return false;
        }
        int authMethod = in.read();
        if (authMethod != 0) {
            Log.w(LOG_TAG, "Wrong authMethod: " + authMethod);
            return false;
        }
        byte[] authResponse = {5, 0};
        socket.getOutputStream().write(authResponse);
        return true;
    }

    private static InetSocketAddress readConnectRequest(InputStream up) throws IOException {
        Log.d(LOG_TAG, "Reading version");
        int version = up.read();
        if (version != 5) {
            Log.w(LOG_TAG, "Wrong version in connect: " + version);
            return null;
        }
        Log.d(LOG_TAG, "Reading command");
        int cmd = up.read();
        if (cmd != 1) {
            Log.w(LOG_TAG, "Wrong command: " + cmd);
            // Unsupported command
            return null;
        }
        Log.d(LOG_TAG, "Reading reserved");
        int reserved = up.read();
        Log.d(LOG_TAG, "Reading address type");
        int addressType = up.read();
        int len;
        if (addressType == 1) {
            // IPv4
            len = 4;
        } else if (addressType == 3) {
            // Domain name
            len = up.read();
        } else if (addressType == 4) {
            // IPv6
            len = 16;
        } else {
            Log.w(LOG_TAG, "Wrong address type: " + addressType);
            return null;
        }

        Log.d(LOG_TAG, "Reading address");
        // Read |len| bytes
        byte[] rawAddress = new byte[len];
        int i = 0;
        while (i < len) {
            int bytes = up.read(rawAddress, i, len - i);
            if (bytes <= 0) {
                Log.w(LOG_TAG, "Connection terminated mid-address");
                return null;
            }
            i += bytes;
        }

        Log.d(LOG_TAG, "Reading port");
        int port = (up.read() << 8) | up.read();
        if (addressType == 1 || addressType == 4) {
            InetAddress ip = InetAddress.getByAddress(rawAddress);
            return new InetSocketAddress(ip, port);
        } else if (addressType == 3) {
            String hostname = new String(rawAddress);
            return InetSocketAddress.createUnresolved(hostname, port);
        }
        Log.e(LOG_TAG, "Unreachable");
        return null;
    }

    /**
     * Blocks for the duration of negotiation, then spawns a thread to keep bytes flowing.
     * @param socket A socket on which a client is expected to make a SOCKS request
     * @return The requested address
     */
    public static boolean negotiate(final Socket socket, SocksCallback callback) {
        final InputStream up;
        final InputStream down_in;
        final InetSocketAddress destination;
        try {
            if (!doAuthHandshake(socket)) {
                Log.w(LOG_TAG, "Auth handshake failed");
                return false;
            }

            // Read request
            up = socket.getInputStream();
            Log.d(LOG_TAG, "Waiting for connect request");
            destination = readConnectRequest(up);
            if (destination == null) {
                Log.w(LOG_TAG, "Invalid connect request");
                return false;
            }
            Log.d(LOG_TAG, "Got request for " + destination.toString());

            down_in = callback.connect(up, destination);
            if (down_in == null) {
                Log.w(LOG_TAG, "Connect failed");
                return false;
            }
            byte[] connectResponse = {5, 0, 0, 1, 0,0,0,0,0,0};
            socket.getOutputStream().write(connectResponse);

        } catch (IOException e) {
            Log.w(LOG_TAG, "IOError after handshake: " + e.toString());
            return false;
        }

        new Thread(
                new Runnable() {
                    public void run() {
                        try {
                            OutputStream down_out = socket.getOutputStream();
                            int n;
                            byte[] buffer = new byte[16384];
                            while ((n = down_in.read(buffer)) > -1) {
                                down_out.write(buffer, 0, n);
                            }
                            down_out.close();
                            socket.close();
                        } catch (IOException e) {
                            return;
                        }
                    }
                }, "Copy download bytes from " + destination.toString())
                .start();
        return true;
    }
}
