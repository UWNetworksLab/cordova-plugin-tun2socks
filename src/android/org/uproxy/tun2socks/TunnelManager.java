package org.uproxy.tun2socks;

/*
 * Copyright (c) 2016, Psiphon Inc.
 * All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

import android.annotation.TargetApi;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.VpnService;
import android.os.Build;
import android.os.IBinder;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;

import com.google.jigsaw.sockstohttps.HttpSocksProxy;

import java.net.URI;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

public class TunnelManager implements Tunnel.HostService {

  private static final String LOG_TAG = "TunnelManager";
  public static final String PROXY_SERVER_ADDRESS_EXTRA = "proxyServerAddress";
  public static final String SOCKS_SERVER_ADDRESS_EXTRA = "socksServerAddress";

  private TunnelVpnService m_parentService = null;
  private CountDownLatch m_tunnelThreadStopSignal;
  private Thread m_tunnelThread;
  private AtomicBoolean m_isStopping;
  private Tunnel m_tunnel = null;
  private String m_proxyServerAddress;
  private String m_socksServerAddress;
  private HttpSocksProxy m_socks;
  private AtomicBoolean m_isReconnecting;

  public TunnelManager(TunnelVpnService parentService) {
    m_parentService = parentService;
    m_isStopping = new AtomicBoolean(false);
    m_isReconnecting = new AtomicBoolean(false);
    m_tunnel = Tunnel.newTunnel(this);
  }

  // Implementation of android.app.Service.onStartCommand
  public int onStartCommand(Intent intent, int flags, int startId) {
    Log.i(LOG_TAG, "onStartCommand");
    LocalBroadcastManager.getInstance(m_parentService)
        .registerReceiver(
            m_broadcastReceiver, new IntentFilter(DnsResolverService.DNS_ADDRESS_BROADCAST));

    m_proxyServerAddress = intent.getStringExtra(PROXY_SERVER_ADDRESS_EXTRA);
    if (m_proxyServerAddress == null) {
      Log.e(LOG_TAG, "Failed to receive the socks server address.");
      m_parentService.broadcastVpnStart(false /* success */);
      return 0;
    }

    startHttpSocksProxy();

    try {
      if (!m_tunnel.startRouting()) {
        Log.e(LOG_TAG, "Failed to establish VPN");
        m_parentService.broadcastVpnStart(false /* success */);
      }
      startDnsResolverService();
    } catch (Tunnel.Exception e) {
      Log.e(LOG_TAG, String.format("Failed to establish VPN: %s", e.getMessage()));
      m_parentService.broadcastVpnStart(false /* success */);
    }
    return android.app.Service.START_NOT_STICKY;
  }

  private void startHttpSocksProxy() {
    // TODO: make an IPv6-safe parser.
    URI proxyUri;
    try {
      proxyUri = new URI(m_proxyServerAddress);
    } catch (Exception e) {
      Log.e(LOG_TAG, e.toString());
      return;
    }
    String scheme = proxyUri.getScheme();
    String host = proxyUri.getHost();
    int proxyPort = proxyUri.getPort();
    int socksPort;
    // TODO: support insecure HTTP?
    if ("socks5".equals(scheme)) {
      socksPort = proxyPort == -1 ? 1080 : proxyPort;
    } else if ("https".equals(scheme)) {
      String userInfo = proxyUri.getUserInfo();
      String username = null;
      String password = null;
      if (userInfo != null) {
        if (userInfo.contains(":")) {
          String[] split = userInfo.split(":");
          username = split[0];
          password = split[1];
        } else {
          username = userInfo;
        }
      }
      m_socks = new HttpSocksProxy();
      socksPort = m_socks.start("https", host, proxyPort, username, password);
    } else {
      Log.e(LOG_TAG, "Invalid proxy address: " + m_proxyServerAddress);
      return;
    }
    m_socksServerAddress = "127.0.0.1:" + socksPort;
  }

  // Implementation of android.app.Service.onDestroy
  public void onDestroy() {
    if (m_tunnelThread == null) {
      return;
    }

    LocalBroadcastManager.getInstance(m_parentService).unregisterReceiver(m_broadcastReceiver);

    // Stop DNS resolver service
    m_parentService.stopService(new Intent(m_parentService, DnsResolverService.class));

    // signalStopService should have been called, but in case is was not, call here.
    // If signalStopService was not already called, the join may block the calling
    // thread for some time.
    signalStopService();

    try {
      m_tunnelThread.join();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    m_tunnelThreadStopSignal = null;
    m_tunnelThread = null;
  }

  // Signals the runTunnel thread to stop. The thread will self-stop the service.
  // This is the preferred method for stopping the tunnel service:
  // 1. VpnService doesn't respond to stopService calls
  // 2. The UI will not block while waiting for stopService to return
  public void signalStopService() {
    if (m_tunnelThreadStopSignal != null) {
      m_tunnelThreadStopSignal.countDown();
    }
  }

  // Stops the tunnel thread and restarts it with |socksServerAddress|.
  public void restartTunnel(final String proxyServerAddress) {
    Log.i(LOG_TAG, "Restarting tunnel.");
    if (proxyServerAddress == null ||
        proxyServerAddress.equals(m_proxyServerAddress)) {
      // Don't reconnect if the socks server address hasn't changed.
      m_parentService.broadcastVpnStart(true /* success */);
      return;
    }
    m_proxyServerAddress = proxyServerAddress;
    startHttpSocksProxy();
    m_isReconnecting.set(true);

    // Signaling stop to the tunnel thread with the reconnect flag set causes
    // the thread to stop the tunnel (but not the VPN or the service) and send
    // the new SOCKS server address to the DNS resolver before exiting itself.
    // When the DNS broadcasts its local address, the tunnel will restart.
    signalStopService();
  }

  private void startTunnel(final String dnsResolverAddress) {
    m_tunnelThreadStopSignal = new CountDownLatch(1);
    m_tunnelThread =
        new Thread(
            new Runnable() {
              @Override
              public void run() {
                runTunnel(m_socksServerAddress, dnsResolverAddress);
              }
            });
    m_tunnelThread.start();
  }

  private void runTunnel(String socksServerAddress, String dnsResolverAddress) {
    m_isStopping.set(false);

    try {
      if (!m_tunnel.startTunneling(socksServerAddress, dnsResolverAddress)) {
        throw new Tunnel.Exception("application is not prepared or revoked");
      }
      Log.i(LOG_TAG, "VPN service running");
      m_parentService.broadcastVpnStart(true /* success */);

      try {
        m_tunnelThreadStopSignal.await();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }

      m_isStopping.set(true);

    } catch (Tunnel.Exception e) {
      Log.e(LOG_TAG, String.format("Start tunnel failed: %s", e.getMessage()));
      m_parentService.broadcastVpnStart(false /* success */);
    } finally {
      if (m_isReconnecting.get()) {
        // Stop tunneling only, not VPN, if reconnecting.
        Log.i(LOG_TAG, "Stopping tunnel.");
        m_tunnel.stopTunneling();
        // Start the DNS resolver service with the new SOCKS server address.
        startDnsResolverService();
      } else {
        // Stop VPN tunnel and service only if not reconnecting.
        Log.i(LOG_TAG, "Stopping VPN and tunnel.");
        m_tunnel.stop();
        m_parentService.stopForeground(true);
        m_parentService.stopSelf();
      }
      m_isReconnecting.set(false);
    }
  }

  //----------------------------------------------------------------------------
  // Tunnel.HostService
  //----------------------------------------------------------------------------

  @Override
  public String getAppName() {
    return "Tun2Socks";
  }

  @Override
  public Context getContext() {
    return m_parentService;
  }

  @Override
  public VpnService getVpnService() {
    return ((TunnelVpnService) m_parentService);
  }

  @Override
  public VpnService.Builder newVpnServiceBuilder() {
    return ((TunnelVpnService) m_parentService).newBuilder();
  }

  @Override
  public void onDiagnosticMessage(String message) {
    Log.d(LOG_TAG, message);
  }

  @Override
  public void onTunnelConnected() {
    Log.i(LOG_TAG, "Tunnel connected.");
  }

  @Override
  @TargetApi(Build.VERSION_CODES.M)
  public void onVpnEstablished() {
    Log.i(LOG_TAG, "VPN established.");
  }

  private void startDnsResolverService() {
    Intent dnsResolverStart = new Intent(m_parentService, DnsResolverService.class);
    dnsResolverStart.putExtra(SOCKS_SERVER_ADDRESS_EXTRA, m_socksServerAddress);
    m_parentService.startService(dnsResolverStart);
  }

  private DnsResolverAddressBroadcastReceiver m_broadcastReceiver =
      new DnsResolverAddressBroadcastReceiver(TunnelManager.this);

  private class DnsResolverAddressBroadcastReceiver extends BroadcastReceiver {
    private TunnelManager m_tunnelManager;

    public DnsResolverAddressBroadcastReceiver(TunnelManager tunnelManager) {
      m_tunnelManager = tunnelManager;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
      String dnsResolverAddress = intent.getStringExtra(DnsResolverService.DNS_ADDRESS_EXTRA);
      if (dnsResolverAddress == null || dnsResolverAddress.isEmpty()) {
        Log.e(LOG_TAG, "Failed to receive DNS resolver address");
        return;
      }
      Log.d(LOG_TAG, "DNS resolver address: " + dnsResolverAddress);
      // Callback into tunnel manager to start tunneling.
      m_tunnelManager.startTunnel(dnsResolverAddress);
    }
  };
}
