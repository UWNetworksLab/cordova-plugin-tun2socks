/*
 * Copyright (C) Psiphon Inc.
 * Released under badvpn licence: https://github.com/ambrop72/badvpn#license
 */

package org.uproxy.tun2socks;

import android.util.Log;

public class Tun2SocksJni {

  // runTun2Socks takes a tun device file descriptor (from Android's VpnService,
  // for example) and plugs it into tun2socks, which routes the tun TCP traffic
  // through the specified SOCKS proxy. UDP traffic is sent to the specified
  // udpgw server.
  //
  // The tun device file descriptor should be set to non-blocking mode.
  // tun2Socks does *not* take ownership of the tun device file descriptor; the
  // caller is responsible for closing it after tun2socks terminates.
  //
  // runTun2Socks blocks until tun2socks is stopped by calling terminateTun2Socks.
  // It's safe to call terminateTun2Socks from a different thread.
  //
  // logTun2Socks is called from tun2socks when an event is to be logged.

  public static native int runTun2Socks(
      int vpnInterfaceFileDescriptor,
      int vpnInterfaceMTU,
      String vpnIpAddress,
      String vpnNetMask,
      String socksServerAddress,
      String dnsResolverAddress,
      int transparentDNS);

  public static native int terminateTun2Socks();

  public static void logTun2Socks(String level, String channel, String msg) {
    String logMsg = String.format("%s (%s): %s", level, channel, msg);
    Log.i("Tun2Socks", logMsg);
  }

  static {
    System.loadLibrary("tun2socks");
  }
}
