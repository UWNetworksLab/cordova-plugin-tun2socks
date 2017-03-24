# cordova-plugin-tun2socks

This Cordova plugin provides the ability to start a system-wide VPN for Android devices.

We use [tun2socks](https://github.com/ambrop72/badvpn-googlecode-export/blob/master/tun2socks/badvpn-tun2socks.8) as an adapter; it receives all of the device’s traffic through the VPN network interface (TUN) and forwards it to a SOCKS server.

To handle DNS resoution, we have implemented a local DNS resolver that intercepts DNS queries over UDP and proxies them over TCP (to Google Public DNS) through the SOCKS server.

## Re-building the .so

ndk-build -C android

### Target Devices

This plugin targets Android devices running Lollipop (API 21), or higher. This requirement stems from calling `addDisallowedApplication`, a [VPNService.Builder API](https://developer.android.com/reference/android/net/VpnService.Builder.html#addDisallowedApplication(java.lang.String)) introduced in version 21, which allows the specified application's traffic to bypass the VPN.

### Javascript API

`start(socksServerAddress:string) : Promise<string>;`

Starts the VPN service, and tunnels all the traffic to the SOCKS5 server at `socksServerAddress`.
Restarts tunneling while preserving the VPN connection if called when the plugin is already running.

`stop(): Promise<string>;`

Stops the VPN service.

`onDisconnect(): Promise<string>;`

Sets a success callback on the returned promise, to be called if the VPN service gets revoked or disconnected.

`deviceSupportsPlugin(): Promise<Boolean>;`

Retruns true if the device runs the minimum required version for the plugin to function properly.

### Code Sources

We re-use and have used as a starting point open source code from [Psiphon](https://psiphon.ca/uz@Latn/open-source.html), specifically https://github.com/mei3am/ps.

 * `src/android`:
  * starting point: https://github.com/mei3am/ps/tree/master/Android/app
  * `Android/app/src/main/java/ca/psiphon/PsiphonTunnel.java` -> `src/android/org/uproxy/tun2socks/Tunnel.java`
  * `Android/app/src/main/java/com/psiphon3/psiphonlibrary/TunnelManager.java` -> `src/android/org/uproxy/tun2socks/TunnelManager.java`
  * `Android/app/src/main/java/com/psiphon3/psiphonlibrary/TunnelVpnService.java` -> `src/android/org/uproxy/tun2socks/TunnelVpnService.java`
 * `src/badvpn`:
  * built upon Psiphon's fork of [badvpn](https://github.com/ambrop72/badvpn).
  * starting point: https://github.com/mei3am/ps/tree/master/Android/badvpn
  * uProxy-specific changes mostly confined to tun2socks/tun2socks.c and marked with `// ==== UPROXY ====` (like Psiphon-specific changes)
 * `src/android/libs/jsocks.jar`:
  * http://jsocks.sourceforge.net/
