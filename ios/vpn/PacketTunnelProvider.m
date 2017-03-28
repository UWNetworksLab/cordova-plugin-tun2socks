//
//  PacketTunnelProvider.m
//  ShadowProxy
//
#import "PacketTunnelProvider.h"
#import <ShadowPath/ShadowPath.h>
#import <PacketProcessor/TunnelInterface.h>

@implementation PacketTunnelProvider

- (void)startTunnelWithOptions:(NSDictionary *)options completionHandler:(void (^)(NSError *))completionHandler {
  NSLog(@"START TUNNEL");
 [self setupPacketTunnelFlow];

  NEIPv4Settings *ipv4Settings = [[NEIPv4Settings alloc] initWithAddresses:@[@"192.168.20.2"]
                                                               subnetMasks: @[@"255.255.255.0"]];
  ipv4Settings.includedRoutes = @[[NEIPv4Route defaultRoute]];
  NEPacketTunnelNetworkSettings *settings = [[NEPacketTunnelNetworkSettings alloc]
                                             initWithTunnelRemoteAddress:@"192.168.20.1"];
  settings.IPv4Settings = ipv4Settings;
  settings.DNSSettings = [[NEDNSSettings alloc] initWithServers:@[@"8.8.8.8"]];
  __weak PacketTunnelProvider *weakSelf = self;
  [self setTunnelNetworkSettings:settings completionHandler:^(NSError * _Nullable error) {
    if (error) {
      NSLog(@"%@: Error cannot set tunnel network settings: %@", weakSelf, error.localizedDescription);
      return completionHandler(error);
    }
   NSLog(@"TUNNEL CONNECTED");
   [weakSelf startShadowsocks];
    completionHandler(nil);
  }];
}
- (void)stopTunnelWithReason:(NEProviderStopReason)reason completionHandler:(void (^)(void))completionHandler {
  //  self.pendingStopCompletion = completionHandler;
  NSLog(@"Stopping tunnel");
 [TunnelInterface stop];
}


void shadowsocks_handler(int fd, void *udata) {
 // TODO(alalama): don't hardcode port, get from FD instead
 NSLog(@"Shadowsocks callback. sock fd: %d,  sock_port: %d", fd, 9999);
 PacketTunnelProvider* provider = (__bridge PacketTunnelProvider*)udata;
 [provider startTun2SocksWithPort:9999];
}

- (void)startShadowsocks {
 [NSThread detachNewThreadWithBlock:^{
   const profile_t example_profile = {
     .remote_host = "162.243.124.176",
     .local_addr = "127.0.0.1",
     .method = "aes-256-cfb",
     .password = "uproxy123",
     .remote_port = 8080,
     .local_port = 9999,
     .timeout = 600,
     .acl = NULL,
     .log = NULL,
     .fast_open = 0,
     .mode = 0,
     .auth = 0,
     .verbose = 3
   };
   start_ss_local_server(example_profile, shadowsocks_handler, (__bridge void *)self);
 }];
}

- (void)setupPacketTunnelFlow {
 NSError *error = [TunnelInterface setupWithPacketTunnelFlow:self.packetFlow];
 if (error) {
   NSLog(@"Failed to set up tunnel packet flow: %@", error);
   exit(1);
 }
}

- (void)startTun2SocksWithPort:(int) port {
 [[NSNotificationCenter defaultCenter] addObserver:self selector:@selector(onTun2SocksDone)
                                              name:kTun2SocksStoppedNotification object:nil];
 [TunnelInterface startTun2Socks:port];
 dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(0.5 * NSEC_PER_SEC)), dispatch_get_main_queue(), ^{
   [TunnelInterface processPackets];
 });
}

- (void)onTun2SocksDone {
 NSLog(@"tun2socks done");
 [[NSNotificationCenter defaultCenter] removeObserver:self];
 [self cancelTunnelWithError:nil];
 exit(EXIT_SUCCESS);
}


@end
