#pragma once
#include <Arduino.h>

// Wi-Fi (known networks in NVS), mDNS, NTP, and the small HTTP API the Turnwise app talks to.
namespace net {
  void begin();
  void loop();
  bool connected();
  String ip();
  bool enabled();
  void setEnabled(bool on);
  void addNetwork(const String& ssid, const String& pass);
  void forget(const String& ssid);
  String describe();                      // one line for serial / screen
}
