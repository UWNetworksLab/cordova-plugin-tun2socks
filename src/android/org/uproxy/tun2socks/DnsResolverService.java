package org.uproxy.tun2socks;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.StringBuffer;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import socks.Socks5Proxy;
import socks.SocksSocket;

// This class listens on a UDP socket for incoming DNS traffic, which is proxy'd
// through SOCKS over TCP to Honest DNS.
public class DnsResolverService extends Service {

  private static final String LOG_TAG = "DnsResolverService";
  public static final String DNS_ADDRESS_BROADCAST = "dnsResolverAddressBroadcast";
  public static final String DNS_ADDRESS_EXTRA = "dnsResolverAddress";
  public static final String SOCKS_SERVER_ADDRESS_EXTRA = "socksServerAddress";

  private final IBinder binder = new LocalBinder();
  private String m_socksServerAddress;
  private DnsUdpToHttps dnsResolver = null;

  public class LocalBinder extends Binder {
    public DnsResolverService getService() {
      return DnsResolverService.this;
    }
  }

  @Override
  public IBinder onBind(Intent intent) {
    return binder;
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    Log.i(LOG_TAG, "start command");
    m_socksServerAddress = intent.getStringExtra(SOCKS_SERVER_ADDRESS_EXTRA);
    if (m_socksServerAddress == null) {
      Log.e(LOG_TAG, "Failed to receive socks server address.");
    }
    return START_NOT_STICKY;
  }

  @Override
  public void onCreate() {
    Log.i(LOG_TAG, "create");
    dnsResolver = new DnsUdpToHttps(DnsResolverService.this);
    dnsResolver.start();
  }

  @Override
  public void onDestroy() {
    Log.i(LOG_TAG, "destroy");
    if (dnsResolver != null) {
      dnsResolver.kill();
    }
  }

  // Parses the socks server address received on start and returns it as
  // a InetSocketAddress.
  public InetSocketAddress getSocksServerAddress() {
    if (m_socksServerAddress == null) {
      return null;
    }
    int separatorIndex = m_socksServerAddress.indexOf(':');
    String ip = m_socksServerAddress.substring(0, separatorIndex);
    int port = Integer.parseInt(m_socksServerAddress.substring(separatorIndex + 1));
    return new InetSocketAddress(ip, port);
  }

  private class DnsUdpToHttps extends Thread {
    private static final String LOG_TAG = "DnsUdpToHttps";
    private static final String DNS_RESOLVER_IP = "173.194.66.102";//"216.58.219.206";
    private static final String GOOGLE_DNS_HOSTNAME = "dns.google.com";
    private static final int MAX_UDP_DATAGRAM_LEN = 512; // DNS max over UDP
    private static final int DEFAULT_DNS_PORT = 443;

    // DNS bit masks
    private static final byte DNS_QR = (byte) 0x80;
    private static final byte DNS_TC = (byte) 0x02;
    private static final byte DNS_Z = (byte) 0x70;
    private static final byte DNS_RCODE = (byte) 0xF;
    // DNS header constants
    private static final int DNS_HEADER_SIZE = 12;
    private static final int QR_OPCODE_AA_TC_OFFSET = 2;
    private static final int RA_Z_R_CODE_OFFSET = 3;
    private static final int QDCOUNT_OFFSET = 4;
    private static final int ANCOUNT_OFFSET = 6;
    private static final int NSCOUNT_OFFSET = 8;
    private static final int ARCOUNT_OFFSET = 10;

    // HTTP header constants
    private static final String HTTP_HEADER_RESPONSE_CODE_OK = "200 OK";
    private static final String HTTP_HEADER_CONTENT_LENGHT = "Content-Length: ";

    private volatile DatagramSocket udpSocket = null;
    private DnsResolverService m_parentService;

    public DnsUdpToHttps(DnsResolverService parentService) {
      m_parentService = parentService;
    }

    public void run() {
      byte[] udpBuffer = new byte[MAX_UDP_DATAGRAM_LEN];
      DatagramPacket udpPacket = new DatagramPacket(udpBuffer, udpBuffer.length);
      InetSocketAddress socksServerAddress =
          m_parentService.getSocksServerAddress(); //new InetSocketAddress("104.236.42.33", 3000);
      if (socksServerAddress == null) {
        return;
      }
      Log.d(LOG_TAG, "SOCKS server address: " + socksServerAddress.toString());

      InetSocketAddress googleDnsAddress = new InetSocketAddress(DNS_RESOLVER_IP, DEFAULT_DNS_PORT);

      Socks5Proxy socksProxy = null;
      try {
        socksProxy = new Socks5Proxy(socksServerAddress.getAddress(), socksServerAddress.getPort());
        udpSocket = new DatagramSocket();
      } catch (Throwable e) {
        e.printStackTrace();
        return;
      }
      broadcastUdpSocketAddress();
      SSLSocketFactory sslFactory =
          (SSLSocketFactory)SSLSocketFactory.getDefault();

      try {
        while (!isInterrupted()) {
          Log.i(LOG_TAG, "listening on " + udpSocket.getLocalSocketAddress().toString());
          try {
            udpSocket.receive(udpPacket);
          } catch (SocketTimeoutException e) {
            continue;
          }
          Log.d(
              LOG_TAG,
              String.format(
                  "UDP: got %d bytes from %s:%d\n%s",
                  udpPacket.getLength(),
                  udpPacket.getAddress().toString(),
                  udpPacket.getPort(),
                  new String(udpBuffer, 0, udpPacket.getLength())));

          byte[] dnsRequest = new byte[udpPacket.getLength()];
          System.arraycopy(udpPacket.getData(), 0, dnsRequest, 0, dnsRequest.length);
          if (!validateDnsRequest(udpPacket)) {
            Log.i(LOG_TAG, "Not a DNS request.");
            continue;
          }

          Socket dnsSocket = null;
          SSLSocket sslSocket = null;
          try {
            dnsSocket = new SocksSocket(socksProxy, DNS_RESOLVER_IP,
                                        DEFAULT_DNS_PORT);
            dnsSocket.connect(googleDnsAddress);
            sslSocket = (SSLSocket) sslFactory.createSocket(
                dnsSocket, socksServerAddress.getHostString(),
                socksServerAddress.getPort(), true);
          } catch (IOException e) {
            e.printStackTrace();
            continue;
          }
          String name = parseDnsRequestName(dnsRequest);
          if (name == null) {
            Log.e(LOG_TAG, "Failed to read name from malformed DNS request");
            continue;
          }
          short dnsRequestId = parseDnsRequestId(dnsRequest);
          Log.d(LOG_TAG, "NAME: " + name + " ID: " + dnsRequestId);

          try {
            performDnsHttpRequest(name, sslSocket);
            byte[] dnsResponse = readBinaryDnsHttpResponse(sslSocket);
            if (dnsResponse == null) {
              continue;
            }
            writeRequestIdToDnsResponse(dnsResponse, dnsRequestId);
            // Log.d(LOG_TAG, ">> RESPONSE: " + bytesToHex(dnsResponse, dnsResponse.length));
            DatagramPacket outPacket =
                new DatagramPacket(dnsResponse, dnsResponse.length,
                                   udpPacket.getSocketAddress());
            udpSocket.send(outPacket);
          } catch (SocketException e) {
            e.printStackTrace();
          } finally {
            try {
              if (dnsSocket != null) {
                dnsSocket.close();
              }
            } catch (IOException e) {
              e.printStackTrace();
            }
          }
        }
      } catch (Throwable e) {
        if (!isInterrupted()) {
          e.printStackTrace();
        }
      } finally {
        if (udpSocket != null) {
          udpSocket.close();
        }
      }
    }

    public void kill() {
      interrupt();
    }

    // TODO(alalama): debug only
    private String bytesToHex(byte[] in, int len) {
      final StringBuilder builder = new StringBuilder();
      for (int i = 0; i < len; ++i) {
        byte b = in[i];
        builder.append(String.format("%02x", b));
      }
      return builder.toString();
    }

    // Performs a DNS request for |name| over HTTP.
    // Assumes |socket| is connected.
    private void performDnsHttpRequest(final String name, Socket socket)
        throws IOException {
      PrintWriter printWriter = new PrintWriter(socket.getOutputStream());
      printWriter.println(String.format("GET /resolve?name=%s&encoding=raw HTTP/1.1", name));
      printWriter.println("Host: dns.google.com");
      printWriter.println("Connection: close");
      printWriter.println("");  // CLRF.
      printWriter.flush();
    }

    // Reads the binary payload from a DNS-over-HTTPS response.
    // Returns null on failed responses.
    private byte[] readBinaryDnsHttpResponse(Socket socket) {
      try {
        DataInputStream inputStream =
            new DataInputStream(socket.getInputStream());
        String line = inputStream.readLine();  // Read response status.
        if (line == null || !line.contains(HTTP_HEADER_RESPONSE_CODE_OK)) {
          Log.e(LOG_TAG, "Failed to receive OK response from DNS resolver.");
          return null;
        }
        // Read headers.
        int contentLenght = 0;
        while (line != null && !line.isEmpty()) {
          if (line.contains(HTTP_HEADER_CONTENT_LENGHT)) {
            contentLenght = Integer.parseInt(
                line.substring(HTTP_HEADER_CONTENT_LENGHT.length()));
          }
          line = inputStream.readLine();
        }
        if (contentLenght == 0) {
          Log.e(LOG_TAG, "Failed to receive Content-Length in HTTP response.");
          return null;
        }
        // Stream should be positioned at the DNS binary payload.
        byte dnsResponse[] = new byte[contentLenght];
        inputStream.readFully(dnsResponse);
        return dnsResponse;
      } catch (IOException e) {
        e.printStackTrace();
      }
      return null;
    }

    // Writes |dsnRequestId| to |dnsRequestId| ID header.
    private void writeRequestIdToDnsResponse(
        byte[] dnsResponse, short dnsRequestId) {
      ByteBuffer buffer = ByteBuffer.wrap(dnsResponse);
      buffer.putShort(dnsRequestId);
    }

    private boolean validateDnsRequest(DatagramPacket packet) {
      if (packet.getLength() < DNS_HEADER_SIZE) {
        return false;
      }
      ByteBuffer buffer = ByteBuffer.wrap(packet.getData());
      byte qrOpcodeAaTcRd = buffer.get(QR_OPCODE_AA_TC_OFFSET);
      byte raZRcode = buffer.get(RA_Z_R_CODE_OFFSET);
      short qdcount = buffer.getShort(QDCOUNT_OFFSET);
      short ancount = buffer.getShort(ANCOUNT_OFFSET);
      short nscount = buffer.getShort(NSCOUNT_OFFSET);

      // verify DNS header is request
      return (qrOpcodeAaTcRd & DNS_QR) == 0 /* query */
          && (raZRcode & DNS_Z) == 0 /* Z is Zero */
          && qdcount > 0 /* some questions */
          && nscount == 0
          && ancount == 0; /* no answers */
    }

    // Returns the domain names present in the DNS request.
    // Assumes the DNS packet has been validated.
    private String parseDnsRequestName(byte[] dnsRequest) {
      ByteBuffer buffer = ByteBuffer.wrap(dnsRequest);
      buffer.position(DNS_HEADER_SIZE);  // Position at the start of questions
      StringBuffer nameBuffer = new StringBuffer();

      byte labelLength = buffer.get();
      while (labelLength > 0) {
        if (labelLength > buffer.limit()) {
          return null;
        }
        byte[] labelBytes = new byte[labelLength];
        buffer.get(labelBytes);
        String label = new String(labelBytes);
        nameBuffer.append(label);
        nameBuffer.append(".");

        labelLength = buffer.get();
      }
      return nameBuffer.toString();
    }

    private short parseDnsRequestId(byte[] dnsRequest) {
      ByteBuffer buffer = ByteBuffer.wrap(dnsRequest);
      return buffer.getShort();
    }

    private void broadcastUdpSocketAddress() {
      Log.d(LOG_TAG, "Broadcasting address");
      if (udpSocket == null) {
        return;
      }
      String dnsResolverAddress = String.format("127.0.0.1:%d", udpSocket.getLocalPort());
      Intent addressBroadcast = new Intent(DnsResolverService.DNS_ADDRESS_BROADCAST);
      addressBroadcast.putExtra(DnsResolverService.DNS_ADDRESS_EXTRA, dnsResolverAddress);
      LocalBroadcastManager.getInstance(m_parentService).sendBroadcast(addressBroadcast);
    }
  }
}
