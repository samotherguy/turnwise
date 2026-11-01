#include "aircon.h"
#include <IRremoteESP8266.h>
#include <IRac.h>
#include <Preferences.h>

namespace {
  constexpr int IR_PIN = 44;
  IRac* ac = nullptr;
  const decode_type_t PROTOCOLS[] = { decode_type_t::MITSUBISHI_AC, decode_type_t::MITSUBISHI112, decode_type_t::MITSUBISHI136, decode_type_t::MITSUBISHI_HEAVY_152, decode_type_t::MITSUBISHI_HEAVY_88 };
  const char* PROTO_NAMES[] = { "Mitsubishi Electric (MSZ)", "Mitsubishi 112", "Mitsubishi 136", "Mitsubishi Heavy 152", "Mitsubishi Heavy 88" };
  const stdAc::opmode_t MODES[] = { stdAc::opmode_t::kCool, stdAc::opmode_t::kDry, stdAc::opmode_t::kFan, stdAc::opmode_t::kHeat, stdAc::opmode_t::kAuto };
  const char* MODE_NAMES[] = { "cool", "dry", "fan", "heat", "auto" };
  const stdAc::fanspeed_t FANS[] = { stdAc::fanspeed_t::kAuto, stdAc::fanspeed_t::kLow, stdAc::fanspeed_t::kMedium, stdAc::fanspeed_t::kHigh };
  const char* FAN_NAMES[] = { "auto", "low", "medium", "high" };
  aircon::State s = { false, 24, 0, 0, 0 };
  void save() { Preferences p; p.begin("ac"); p.putBool("on", s.power); p.putInt("t", s.temp); p.putInt("m", s.mode); p.putInt("f", s.fan); p.putInt("p", s.protocol); p.end(); }
}

void aircon::begin() {
  Preferences p; p.begin("ac", true); s.power = p.getBool("on", false); s.temp = p.getInt("t", 24); s.mode = p.getInt("m", 0); s.fan = p.getInt("f", 0); s.protocol = p.getInt("p", 0); p.end();
  ac = new IRac(IR_PIN);
}
aircon::State aircon::state() { return s; }
void aircon::power(bool on) { s.power = on; send(); }
void aircon::temp(int d) { s.temp = constrain(s.temp + d, 16, 31); send(); }
void aircon::nextMode() { s.mode = (s.mode + 1) % 5; send(); }
void aircon::nextFan() { s.fan = (s.fan + 1) % 4; send(); }
void aircon::nextProtocol() { s.protocol = (s.protocol + 1) % 5; save(); }
void aircon::send() {
  if (!ac) return;
  ac->next.protocol = PROTOCOLS[s.protocol];
  ac->next.model = 1;
  ac->next.power = s.power;
  ac->next.mode = MODES[s.mode];
  ac->next.degrees = s.temp;
  ac->next.celsius = true;
  ac->next.fanspeed = FANS[s.fan];
  ac->next.swingv = stdAc::swingv_t::kAuto;
  ac->next.swingh = stdAc::swingh_t::kOff;
  ac->next.light = false; ac->next.beep = false; ac->next.econo = false; ac->next.turbo = false; ac->next.quiet = false; ac->next.filter = false; ac->next.clean = false; ac->next.sleep = -1; ac->next.clock = -1;
  ac->sendAc();
  save();
  Serial.printf("[ac] sent %s power=%d temp=%d mode=%s fan=%s\n", PROTO_NAMES[s.protocol], s.power, s.temp, MODE_NAMES[s.mode], FAN_NAMES[s.fan]);
}
const char* aircon::modeName() { return MODE_NAMES[s.mode]; }
const char* aircon::fanName() { return FAN_NAMES[s.fan]; }
const char* aircon::protocolName() { return PROTO_NAMES[s.protocol]; }
