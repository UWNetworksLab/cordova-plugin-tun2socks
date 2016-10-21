package org.uproxy.tun2socks;

import android.app.Service;
import android.content.Intent;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;

import socks.Socks5Proxy;
import socks.SocksSocket;

// This class listens on a UDP socket for incoming DNS traffic, which is proxy'd
// through SOCKS over TCP to Google's Public DNS.
public class DnsUdpToSocksResolver extends Thread {
  private static final String LOG_TAG = "DnsUdpToSocksResolver";
  private static final String DNS_RESOLVER_IP = "8.8.8.8";
  // UDP and DNS over TCP theoretical max packet length is 64K.
  private static final int MAX_BUFFER_SIZE = 65535;
  private static final int DEFAULT_DNS_PORT = 53;

  // DNS bit masks
  private static final byte DNS_QR = (byte) 0x80;
  private static final byte DNS_TC = (byte) 0x02;
  private static final byte DNS_Z = (byte) 0x70;
  // DNS header constants
  private static final int DNS_HEADER_SIZE = 12;
  private static final int QR_OPCODE_AA_TC_OFFSET = 2;
  private static final int RA_Z_R_CODE_OFFSET = 3;
  private static final int QDCOUNT_OFFSET = 4;
  private static final int ANCOUNT_OFFSET = 6;
  private static final int NSCOUNT_OFFSET = 8;
  private static final int ARCOUNT_OFFSET = 10;

  // Threading constants
  private static final int N_WORKERS = 10;

  private volatile DatagramSocket m_udpSocket = null;
  private DnsResolverService m_parentService;
  private ThreadPoolExecutor m_executorService;

  public DnsUdpToSocksResolver(DnsResolverService parentService) {
    m_parentService = parentService;
    m_executorService = (ThreadPoolExecutor)Executors.newFixedThreadPool(N_WORKERS);
  }

  public void run() {
    byte[] udpBuffer = new byte[MAX_BUFFER_SIZE];
    DatagramPacket udpPacket = new DatagramPacket(udpBuffer, udpBuffer.length);

    InetSocketAddress socksServerAddress =
        m_parentService.getSocksServerAddress();
    if (socksServerAddress == null) {
      return;
    }
    Log.d(LOG_TAG, "SOCKS server address: " + socksServerAddress.toString());

    Socks5Proxy socksProxy = null;
    InetAddress dnsServerAddress = null;
    try {
      socksProxy = new Socks5Proxy(socksServerAddress.getAddress(),
                                   socksServerAddress.getPort());
      dnsServerAddress = InetAddress.getByName(DNS_RESOLVER_IP);
      m_udpSocket = new DatagramSocket();
    } catch (Throwable e) {
      e.printStackTrace();
      return;
    }
    broadcastUdpSocketAddress();

    try {
      while (!isInterrupted()) {
        Log.d(LOG_TAG, "listening on " + m_udpSocket.getLocalSocketAddress().toString());

        try {
          m_udpSocket.receive(udpPacket);
        } catch (SocketTimeoutException e) {
          continue;
        } catch (IOException e) {
          Log.e(LOG_TAG, "Receive operation failed on udp socket: ", e);
          continue;
        }

        Log.d(
            LOG_TAG,
            String.format(
                "UDP: got %d bytes from %s:%d\n%s",
                udpPacket.getLength(),
                udpPacket.getAddress().toString(),
                udpPacket.getPort(),
                new String(udpPacket.getData(), 0, udpPacket.getLength())));

        // Copy the UDP packet and its underlying buffer data.
        DatagramPacket workerUdpPacket;
        int packetLength = udpPacket.getLength();
        byte[] workerBuffer = new byte[packetLength];
        System.arraycopy(udpPacket.getData(), 0, workerBuffer, 0, packetLength);
         try {
            workerUdpPacket = new DatagramPacket(
                workerBuffer, packetLength, udpPacket.getSocketAddress());
        } catch (SocketException e) {
          Log.e(LOG_TAG, "Failed to create worker udp packet: ", e);
          continue;
        }

        DnsResolverWorker worker = new DnsResolverWorker(
            m_udpSocket, workerUdpPacket, socksProxy, dnsServerAddress);
        try {
          m_executorService.execute(worker);
        } catch (RejectedExecutionException e) {
          e.printStackTrace();
          continue;
        }
      }
    } finally {
      if (m_udpSocket != null) {
        m_udpSocket.close();
      }
      if (m_executorService != null) {
        m_executorService.shutdownNow();
      }
    }
  }

  private class DnsResolverWorker implements Runnable {

    private static final String LOG_TAG = "DnsResolverWorker";

    private DatagramSocket m_udpSocket;
    private DatagramPacket m_udpPacket;
    Socks5Proxy m_socksProxy;
    InetAddress m_dnsServerAddress;

    public DnsResolverWorker(
          DatagramSocket udpSocket, DatagramPacket udpPacket,
          Socks5Proxy socksProxy, InetAddress dnsServerAddress) {
      m_udpSocket = udpSocket;
      m_udpPacket = udpPacket;
      m_socksProxy = socksProxy;
      m_dnsServerAddress = dnsServerAddress;
    }

    @Override
    public void run() {
      if (!isValidDnsRequest(m_udpPacket)) {
        Log.i(LOG_TAG, "Not a DNS request.");
        return;
      }

      Socket tcpSocket = null;
      DataOutputStream tcpOutputStream = null;
      DataInputStream tcpInputStream = null;
      try {
        tcpSocket = new SocksSocket(m_socksProxy, m_dnsServerAddress, DEFAULT_DNS_PORT);
        tcpSocket.setKeepAlive(true);
        tcpOutputStream = new DataOutputStream(tcpSocket.getOutputStream());
        tcpInputStream = new DataInputStream(tcpSocket.getInputStream());
      } catch (IOException e) {
        e.printStackTrace();
        return;
      }

      if (!writeUdpPacketToStream(tcpOutputStream, m_udpPacket)) {
        return;
      }

      byte dnsResponse[] = readStreamPayload(tcpInputStream);
      if (dnsResponse == null) {
        closeSocket(tcpSocket);
        return;
      }
      Log.d(LOG_TAG, "Got DNS response " + dnsResponse.length);

      if (!sendUdpPayload(dnsResponse, m_udpSocket,
                          m_udpPacket.getSocketAddress())) {
        return;
      }
      closeSocket(tcpSocket);
    }

    private boolean isValidDnsRequest(DatagramPacket packet) {
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

    // Sends a UDP packet over TCP.
    private boolean writeUdpPacketToStream(DataOutputStream outputStream,
                                           DatagramPacket udpPacket) {
      try {
        outputStream.writeShort(udpPacket.getLength());
        outputStream.write(udpPacket.getData());
        outputStream.flush();
      } catch (IOException e) {
        Log.e(LOG_TAG, "Failed to send UDP packet: ", e);
        return false;
      }
      return true;
    }

    // Reads the payload from a TCP stream.
    private byte[] readStreamPayload(DataInputStream inputStream) {
      final String errorMessage = "Failed to read TCP response: ";
      byte buffer[] = null;
      try {
        short responseBytes = inputStream.readShort();
        buffer = new byte[responseBytes];
        inputStream.readFully(buffer);
      } catch (SocketException e) {
        Log.e(LOG_TAG, errorMessage, e);
      } catch (IOException e) {
        Log.e(LOG_TAG, errorMessage, e);
      }
      return buffer;
    }

    // Sends a UDP packet containing |payload| to |destAddress| via |udpSocket|.
    private boolean sendUdpPayload(byte[] payload, DatagramSocket udpSocket,
                                   SocketAddress destAddress) {
      try {
        DatagramPacket outPacket =
            new DatagramPacket(payload, payload.length, destAddress);
        udpSocket.send(outPacket);
        return true;
      } catch (IOException e) {
        Log.d(LOG_TAG, "Failed to send UDP payload ", e);
      }
      return false;
    }

    // Utility method to close a socket.
    private void closeSocket(Socket socket) {
      try {
        if (socket != null && !socket.isClosed()) {
          socket.close();
        }
      } catch (IOException e) {
        Log.w("Failed to close socket ", e);
      } finally {
        socket = null;
      }
    }

  }

  private void broadcastUdpSocketAddress() {
    Log.d(LOG_TAG, "Broadcasting address");
    if (m_udpSocket == null) {
      return;
    }
    String dnsResolverAddress = String.format("127.0.0.1:%d", m_udpSocket.getLocalPort());
    Intent addressBroadcast = new Intent(DnsResolverService.DNS_ADDRESS_BROADCAST);
    addressBroadcast.putExtra(DnsResolverService.DNS_ADDRESS_EXTRA, dnsResolverAddress);
    LocalBroadcastManager.getInstance(m_parentService).sendBroadcast(addressBroadcast);
  }
}
