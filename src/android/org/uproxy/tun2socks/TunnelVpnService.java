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

package org.uproxy.tun2socks;

import android.annotation.TargetApi;
import android.content.Intent;
import android.net.VpnService;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;

@TargetApi(Build.VERSION_CODES.ICE_CREAM_SANDWICH)
public class TunnelVpnService extends VpnService {

  private static final String LOG_TAG = "TunnelVpnService";
  public static final String TUNNEL_VPN_DISCONNECT_BROADCAST =
      "tunnelVpnDisconnectBroadcast";

  private TunnelManager m_tunnelManager = new TunnelManager(this);

  public class LocalBinder extends Binder {
    public TunnelVpnService getService() {
      return TunnelVpnService.this;
    }
  }

  private final IBinder m_binder = new LocalBinder();

  @Override
  public IBinder onBind(Intent intent) {
    String action = intent.getAction();
    if (action != null && action.equals(SERVICE_INTERFACE)) {
      return super.onBind(intent);
    }
    return m_binder;
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    Log.d(LOG_TAG, "on start");
    return m_tunnelManager.onStartCommand(intent, flags, startId);
  }

  @Override
  public void onCreate() {
    Log.d(LOG_TAG, "on create");
    TunnelState.getTunnelState().setTunnelManager(m_tunnelManager);
  }

  @Override
  public void onDestroy() {
    Log.d(LOG_TAG, "on destroy");
    TunnelState.getTunnelState().setTunnelManager(null);
    m_tunnelManager.onDestroy();
  }

  @Override
  public void onRevoke() {
    Log.e(LOG_TAG, "VPN service revoked.");
    broadcastVpnDisconnect();
    // stopSelf will trigger onDestroy in the main thread.
    stopSelf();
  }

  public VpnService.Builder newBuilder() {
    return new VpnService.Builder();
  }

  private void broadcastVpnDisconnect() {
    Intent disconnectBroadcast = new Intent(TUNNEL_VPN_DISCONNECT_BROADCAST);
    LocalBroadcastManager.getInstance(TunnelVpnService.this)
        .sendBroadcast(disconnectBroadcast);
  }
}
