import NetworkExtension

@objc(Tun2Socks)
class Tun2Socks: CDVPlugin {

  var tunnelManager: NETunnelProviderManager!

  func start(_ command: CDVInvokedUrlCommand) {
    NSLog("tun2socks start")
    setupVpn() { error in
      // TODO(alalama): switch on state
      if let error = error {
        return
      }
      self.startVpn()
    }
  }

  func stop(_ command: CDVInvokedUrlCommand) {
    stopVpn()
  }

  func deviceSupportsPlugin(_ command: CDVInvokedUrlCommand) {
    let pluginResult = CDVPluginResult(status: CDVCommandStatus_OK,
                                       messageAs: true)
    self.commandDelegate!.send(pluginResult, callbackId: command.callbackId)
  }

  func onDisconnect(_ command: CDVInvokedUrlCommand) {
  }

  private func setupVpn(completion: @escaping(Error?) -> Void) {
    // TODO(alalama): test enable and save
    NETunnelProviderManager.loadAllFromPreferences() { (managers, error) in
      if let error = error {
        NSLog("Failed to load VPN configuration: \(error)")
        completion(error)
        return
      }

      var manager: NETunnelProviderManager!
      if let managers = managers, managers.count > 0 {
        manager  = managers.first
        if manager.isEnabled {
          self.tunnelManager = manager
          NotificationCenter.default.post(name: .NEVPNConfigurationChange, object: nil)
          completion(nil)
          return;
        }
      } else {
        let config = NETunnelProviderProtocol()
        // config.providerConfiguration = ["a": 1]
        config.providerBundleIdentifier = "org.uproxy.uProxy.VpnExtension"
        config.serverAddress = "uProxy"

        manager = NETunnelProviderManager()
        manager.protocolConfiguration = config
      }
      manager.isEnabled = true
      manager.saveToPreferences() { (error) -> Void in
        if let error = error {
          NSLog("Failed to save VPN configuration: \(error)");
          completion(error)
          return
        }

        self.tunnelManager = manager;
        // self.updateStatus()
        NotificationCenter.default.post(name: .NEVPNConfigurationChange, object: nil)

        completion(nil)
      }
    }
  }

  // func toggleVpn(_ sender: Any) {
  //   switch(self.tunnelManager.connection.status) {
  //     case NEVPNStatus.invalid:
  //       NSLog("VPN not configured");
  //       break
  //     case NEVPNStatus.connected:
  //     case NEVPNStatus.connecting:
  //     case NEVPNStatus.reasserting:
  //       stopVpn()
  //       break
  //     case NEVPNStatus.disconnected:
  //       startVpn()
  //       break
  //     case NEVPNStatus.disconnecting:
  //       NSLog("Already disconnecting");
  //       break
  //   }
  // }

  private func startVpn() {
    NSLog("Connecting VPN");
    let session: NETunnelProviderSession = self.tunnelManager.connection as! NETunnelProviderSession
    let options = ["a": 1]    // Send additional options to the tunnel provider
    do {
      try session.startTunnel(options: options)
    } catch let error as NSError  {
      NSLog("Failed to start VPN: \(error)")
    }
  }

  private func stopVpn() {
    let session: NETunnelProviderSession = self.tunnelManager.connection as! NETunnelProviderSession
    session.stopTunnel()
  }

}
