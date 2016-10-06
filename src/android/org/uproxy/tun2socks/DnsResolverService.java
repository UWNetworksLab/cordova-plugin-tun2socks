package org.uproxy.tun2socks;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;

import java.net.InetSocketAddress;


// This class exposes a stub DNS resolver service, connected to Google's DNS.
public class DnsResolverService extends Service {

  private static final String LOG_TAG = "DnsResolverService";
  public static final String DNS_ADDRESS_BROADCAST = "dnsResolverAddressBroadcast";
  public static final String DNS_ADDRESS_EXTRA = "dnsResolverAddress";
  public static final String SOCKS_SERVER_ADDRESS_EXTRA = "socksServerAddress";

  private final IBinder binder = new LocalBinder();
  private String m_socksServerAddress;
  private DnsUdpToSocksResolver m_dnsResolver = null;

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
      return START_NOT_STICKY;
    }

    if (m_dnsResolver != null) {
      m_dnsResolver.interrupt();  // Stop resolver to handle reconnect.
    }
    m_dnsResolver = new DnsUdpToSocksResolver(DnsResolverService.this);
    m_dnsResolver.start();

    return START_NOT_STICKY;
  }

  @Override
  public void onDestroy() {
    Log.i(LOG_TAG, "destroy");
    if (m_dnsResolver != null) {
      m_dnsResolver.interrupt();
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

}
