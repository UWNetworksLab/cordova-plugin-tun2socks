/* globals cordova, window, Promise */

window.tun2socks = {};

window.tun2socks._genericHandler = function(method, params) {
  "use strict";
  var args = Array.prototype.slice.call(arguments, 1);
  return new Promise(function(resolve, reject) {
    cordova.exec(resolve, reject, "Tun2Socks", method, args);
  });
};

module.exports = {
  start: window.tun2socks._genericHandler.bind({}, "start"),
  stop: window.tun2socks._genericHandler.bind({}, "stop")
};
