package com.google.jigsaw.sockstohttps;

import android.util.Base64;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.security.cert.X509Certificate;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Created by bemasc on 1/11/17.
 */
public class BasicHttpSocksForwarder implements SocksCallback {
    private static String LOG_TAG = "BasicHttpSocksForwarder";
    private String address;
    private int port;
    private String username;
    private String password;

    // TODO: Figure out a trust model.
    private TrustManager[] trustAllCerts = new TrustManager[] {
            new X509TrustManager() {
                public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
                public void checkClientTrusted(
                        java.security.cert.X509Certificate[] certs, String authType) {
                }
                public void checkServerTrusted(
                        java.security.cert.X509Certificate[] certs, String authType) {
                }
            }
    };

    BasicHttpSocksForwarder(String scheme, String address, int port, String username, String
            password) {
        Log.d(LOG_TAG, "Will forward to " + address + ":" + port);
        this.address = address;
        this.port = port;
        // TODO: Scheme
        this.username = username;
        this.password = password;
    }

    /**
     * Connect an input stream to the destination through the proxy.  Blocks during handshake.
     * @param up
     * @param destination
     * @return
     */
    public InputStream connect(final InputStream up, InetSocketAddress destination) {
        InputStream down_in;
        try {
            SSLContext sc = SSLContext.getInstance("SSL");
            sc.init(null, trustAllCerts, new java.security.SecureRandom());
            InetAddress server = InetAddress.getByName(address);
            Log.d(LOG_TAG, "Connecting to " + server.toString() + ":" + port);
            final SSLSocket socket = (SSLSocket) sc.getSocketFactory().createSocket(server,
                    port);
            Log.d(LOG_TAG, "Starting handshake");
            socket.startHandshake();
            String host;
            if (destination.isUnresolved()) {
                host = destination.getHostName();
            } else {
                String ipAddress = destination.getAddress().getHostAddress();
                if (ipAddress.contains(":")) {
                    // IPv6
                    host = "[" + ipAddress + "]";
                } else {
                    // IPv5
                    host = ipAddress;
                }
            }

            String path = host + ":" + destination.getPort();
            Log.d(LOG_TAG, "Setting URL path to " + path);
            Log.d(LOG_TAG, "socket.isConnected() == " + socket.isConnected());
            String headers = "CONNECT " + path + " HTTP/1.1\r\n";
            if (username != null || password != null) {
                String safeUsername = username == null ? "" : username;
                String safePassword = password == null ? "" : password;
                String cat = safeUsername + ":" + safePassword;
                String cat64 = Base64.encodeToString(cat.getBytes(), Base64.NO_WRAP);
                headers += "Proxy-Authorization: Basic " + cat64 + "\r\n";
            }
            headers += "\r\n";
            final OutputStream up_out = socket.getOutputStream();
            Log.d(LOG_TAG, "Got output stream; writing request");
            up_out.write(headers.getBytes());

            Log.d(LOG_TAG, "Wrote request; awaiting response");
            down_in = socket.getInputStream();
            String response = "";
            while (!response.endsWith("\r\n\r\n")) {
                byte[] nextChar = {(byte)down_in.read()};
                response += new String(nextChar, Charset.forName("US-ASCII"));
            }
            Log.d(LOG_TAG, "Got Response:\n" + response);

            new Thread(
                    new Runnable() {
                        public void run() {
                            try {
                                int n;
                                byte[] buffer = new byte[16384];
                                while ((n = up.read(buffer)) > -1) {
                                    up_out.write(buffer, 0, n);
                                }
                                up_out.close();
                                socket.close();
                            } catch (IOException e) {
                                return;
                            }
                        }
                    }, "Copy upload bytes to " + destination.toString())
                    .start();
        } catch (Exception e) {
            Log.w(LOG_TAG, "In connect, got " + e.toString());
            return null;
        }

        // TODO: Use a richer return type to return different error codes.
        return down_in;
    }
}
