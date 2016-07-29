# cordova-plugin-tun2socks

This Android Cordova plugin allows users to establish a system-wide VPN that tunnels all TCP to a SOCKS5 server through
[tun2socks](https://code.google.com/archive/p/badvpn/wikis/tun2socks.wiki). DNS over UDP is handled transparently by a local DNS resolver, that connects to Google's Public DNS through the SOCKS server.

### Javascript API

`start(socksServerAddress:string) : Promise<string>;`
  
Starts the VPN service, and tunnels all the traffic to the SOCKS5 server at `socksServerAddress`.

`stop(): Promise<string>;`

Stops the VPN service.

`onDisconnect(): Promise<string>;`

Sets a success callback on the returned promise, to be called if the VPN service gets revoked or disconnected.


