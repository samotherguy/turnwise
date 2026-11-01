#pragma once
#include <Arduino.h>

// Mitsubishi air-conditioner remote over the Cardputer's IR LED (GPIO 44). Five protocol variants, pick by model.
namespace aircon {
  struct State { bool power; int temp; int mode; int fan; int protocol; };
  void begin();
  State state();
  void power(bool on);
  void temp(int delta);
  void nextMode();
  void nextFan();
  void nextProtocol();
  void send();                       // transmit the current state
  const char* modeName();
  const char* fanName();
  const char* protocolName();
}
