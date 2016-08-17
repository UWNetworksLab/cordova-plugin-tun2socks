package org.uproxy.tun2socks;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkInfo;
import android.support.v4.content.LocalBroadcastManager;
import java.util.HashMap;
import android.util.Log;

// This class listens for network connectivity changes and binds the process
// to the non-VPN active network, with the ultimate goal of avoiding a VPN
// loop back.
// This class requires API 23 (Marshmallow) in order to call ConnectivityManger
// APIs such as: bindProcessToNetwork and getActiveNetwork.
public class NetworkManager {

    private static final String LOG_TAG = "NetworkManager";

    ConnectivityManager m_connectivityManager;
    BroadcastReceiver m_broadcastReceiver;
    Context m_applicationContext;
    Network m_boundNetwork = null;

    public NetworkManager(Context context) {
      m_applicationContext = context.getApplicationContext();
      m_connectivityManager =
        (ConnectivityManager)m_applicationContext.getSystemService(
            Context.CONNECTIVITY_SERVICE);

      IntentFilter intentFilter = new IntentFilter();
      intentFilter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
      m_broadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
          connectivityChanged(intent);
        }
      };
      m_applicationContext.registerReceiver(this.m_broadcastReceiver,
                                            intentFilter);
      // Bind process to network before establishing VPN.
      bindProcessToActiveNetwork();
    }

    // Destroys the network receiver.
    public void destroy() {
      if (m_broadcastReceiver != null) {
        try {
          m_applicationContext.unregisterReceiver(m_broadcastReceiver);
        } catch (Exception e) {
          Log.e(LOG_TAG, "Error unregistering network receiver: " + e.getMessage(), e);
        } finally {
          m_broadcastReceiver = null;
        }
      }
    }

    // Binds the process to the currently active, non-VPN network - if there is
    // one. This method is idempotent if the process is already bound to a
    // connected non-VPN network.
    private void bindProcessToActiveNetwork() {
      if (m_connectivityManager == null) {
        return;
      }

      Network activeNetwork = selectConnectedNonVpnNetwork();
      if (activeNetwork == null) {
        Log.w(LOG_TAG, "No active network available for binding.");
        return;
      }
      if (activeNetwork.equals(
              m_connectivityManager.getBoundNetworkForProcess())) {
        Log.v(LOG_TAG, "Already bound to active network.");
        return;
      }

      NetworkInfo networkInfo =
          m_connectivityManager.getNetworkInfo(activeNetwork);
      Log.i(LOG_TAG, "Binding process to network " + networkInfo);
      if (!m_connectivityManager.bindProcessToNetwork(activeNetwork)) {
        Log.e(LOG_TAG, "Failed to bind process to network.");
        return;
      }
    }

    // Handles changes in connectivity by binding the process to the latest
    // connected network.
    private void connectivityChanged(Intent intent) {
      NetworkInfo activeNetworkInfo = m_connectivityManager.getActiveNetworkInfo();
      Log.d(LOG_TAG, "ACTIVE NETWORK " + ((activeNetworkInfo != null) ? activeNetworkInfo.toString() : "null" ));
      NetworkInfo boundNetworkInfo = m_connectivityManager.getNetworkInfo(m_connectivityManager.getBoundNetworkForProcess());
      Log.d(LOG_TAG, "BOUND NETWORK " + ((boundNetworkInfo != null) ? boundNetworkInfo.toString() : "null"));
      NetworkInfo networkInfo = intent.getParcelableExtra("networkInfo");
      if (networkInfo == null) {
        Log.w(LOG_TAG, "No network info received in connectivity broadcast.");
      } else {
        Log.d(LOG_TAG, "INTENT NETWORK " + networkInfo.toString());
      }

      bindProcessToActiveNetwork();
   }

   // Selects the best connected non-VPN network from the available networks.
   private Network selectConnectedNonVpnNetwork() {
      Network activeNetwork = m_connectivityManager.getActiveNetwork();
      if (isConnectedNonVpnNetwork(
              m_connectivityManager.getNetworkInfo(activeNetwork))) {
        // Prefer the active network if it fulfills the requirements.
        // Odds are the process is already bound to this network.
        return activeNetwork;
      }
      // Select the best network from the available networks. This is necessary
      // when regaining network connectivity after being disconnected from a
      // VPN. Although the VPN is the active network, there must be an
      // underlying connected non-VPN network.
      HashMap<Integer, Network> networks = new HashMap<Integer, Network>();
      for (Network network : m_connectivityManager.getAllNetworks()) {
        NetworkInfo networkInfo = m_connectivityManager.getNetworkInfo(network);
        Log.v(LOG_TAG, "NETWORK: " + networkInfo.toString());

        if (isConnectedNonVpnNetwork(networkInfo)) {
          // Consider the network if it is a not VPN and it is connected.
          networks.put(networkInfo.getType(), network);
        }
      }

      // Select from candidates based on a 'best connectivity' heuristic.
      if (networks.containsKey(ConnectivityManager.TYPE_ETHERNET)) {
        return networks.get(ConnectivityManager.TYPE_ETHERNET);
      } else if (networks.containsKey(ConnectivityManager.TYPE_WIFI)) {
        return networks.get(ConnectivityManager.TYPE_WIFI);
      } else if (networks.containsKey(ConnectivityManager.TYPE_WIMAX)) {
        return networks.get(ConnectivityManager.TYPE_WIMAX);
      } else if (networks.containsKey(ConnectivityManager.TYPE_MOBILE)) {
        return networks.get(ConnectivityManager.TYPE_MOBILE);
      }
      return null;
   }

   // Returns true if the supplied network is connected, available, and not
   // a VPN.
   private boolean isConnectedNonVpnNetwork(NetworkInfo networkInfo) {
      if (networkInfo == null) {
        return false;
      }
      return networkInfo.getType() != ConnectivityManager.TYPE_VPN &&
          networkInfo.isConnectedOrConnecting() &&
          networkInfo.isAvailable();
   }

}
