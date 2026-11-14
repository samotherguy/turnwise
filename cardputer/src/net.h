#pragma once
#include <Arduino.h>

// Your own addresses live in local_config.h (gitignored); see local_config.h.example.
#if __has_include("local_config.h")
#include "local_config.h"
#endif
#ifndef WAKER_URL
#define WAKER_URL "http://waker.local:8490"
#endif

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
