// Turnwise peripheral: a Cardputer ADV that records all day into a rolling 10-hour buffer on its SD card.
// Milestone 1: the recorder, status on screen, and a serial command line to inspect and dump chunks.
#include <M5Cardputer.h>
#include <SD.h>
#include <time.h>
#include "recorder.h"
#include "store.h"
#include "net.h"
#include "ui.h"
#include "aircon.h"
#include "log.h"
#include <Preferences.h>

static const char* HELP =
  "commands: status | ls | dump <name> | keep <name> | unkeep <name> | rm <name> | mark <minutes> | "
  "rec on|off | rotate | time <epoch> | wifi [add <ssid> <pw>|forget <ssid>|on|off] | waker [url] | mictest | miclevel <sec> | micgain <0-15> | sd | sdformat | i2c | reboot | help";

static void printStatus() {
  auto s = recorder::status();
  time_t now = time(nullptr); char ts[32]; strftime(ts, sizeof ts, "%Y-%m-%d %H:%M:%S", localtime(&now));
  Serial.printf("rec=%s chunk=%s sec=%u chunks=%u overruns=%u werr=%u\n", s.running ? "on" : "off", s.chunk.c_str(), s.seconds, s.chunks, s.overruns, s.writeErrors);
  Serial.printf("sd=%s free=%.1fGB total=%.1fGB files=%u\n", store::mounted() ? "ok" : "none", store::freeBytes() / 1e9, store::totalBytes() / 1e9, store::list().size());
  Serial.printf("time=%s rtc=%s bat=%d%% uptime=%lus board=%d heap=%u\n", ts, M5.Rtc.isEnabled() ? "yes" : "no", M5.Power.getBatteryLevel(), millis() / 1000, (int)M5.getBoard(), ESP.getFreeHeap());
}

static void dump(const String& name) {
  File f = SD.open(store::path(name));
  if (!f) { Serial.println("ERR no such chunk"); return; }
  Serial.printf("DUMP %s %u\n", name.c_str(), (unsigned)f.size());
  static uint8_t buf[4096];
  while (f.available()) { size_t n = f.read(buf, sizeof buf); Serial.write(buf, n); }
  f.close();
  Serial.print("\nEND\n");
}

static void handle(String line) {
  line.trim(); if (!line.length()) return;
  int sp = line.indexOf(' '); String cmd = sp < 0 ? line : line.substring(0, sp), arg = sp < 0 ? "" : line.substring(sp + 1); arg.trim();
  if (cmd == "help") Serial.println(HELP);
  else if (cmd == "status") printStatus();
  else if (cmd == "ls") { for (auto& c : store::list()) Serial.printf("%s %u%s\n", c.name.c_str(), c.size, c.kept ? " keep" : ""); Serial.println("END"); }
  else if (cmd == "dump") dump(arg);
  else if (cmd == "keep") Serial.println(store::keep(arg, true) ? "OK" : "ERR");
  else if (cmd == "unkeep") Serial.println(store::keep(arg, false) ? "OK" : "ERR");
  else if (cmd == "rm") Serial.println(store::remove(arg) ? "OK" : "ERR");
  else if (cmd == "mark") Serial.printf("OK kept %u chunk(s)\n", store::markLast(arg.toInt() > 0 ? arg.toInt() : 30));
  else if (cmd == "rec") { if (arg == "on") recorder::start(); else recorder::stop(); printStatus(); }
  else if (cmd == "rotate") { recorder::rotate(); Serial.println("OK"); }
  else if (cmd == "wifi") {
    int sp2 = arg.indexOf(' '); String sub = sp2 < 0 ? arg : arg.substring(0, sp2), rest = sp2 < 0 ? "" : arg.substring(sp2 + 1);
    if (sub == "add") { int sp3 = rest.indexOf(' '); if (sp3 < 0) Serial.println("ERR wifi add <ssid> <password>"); else { net::addNetwork(rest.substring(0, sp3), rest.substring(sp3 + 1)); Serial.println("OK saved"); } }
    else if (sub == "forget") { net::forget(rest); Serial.println("OK"); }
    else if (sub == "on" || sub == "off") { net::setEnabled(sub == "on"); Serial.println("OK"); }
    else Serial.println(net::describe());
  }
  else if (cmd == "waker") { Preferences p; p.begin("box"); if (arg.length()) p.putString("waker", arg); Serial.println("waker url: " + p.getString("waker", "http://192.168.100.16:8490")); p.end(); }
  else if (cmd == "ac") { if (arg == "on") aircon::power(true); else if (arg == "off") aircon::power(false); else aircon::send(); Serial.println("OK sent"); }
  else if (cmd == "sd") { Serial.println(store::remount() ? "OK mounted" : "ERR mount failed"); if (store::mounted() && !recorder::status().running) recorder::start(); }
  else if (cmd == "log") Serial.print(logring::dump());
  else if (cmd == "reboot") { Serial.println("OK"); delay(100); ESP.restart(); }
  else if (cmd == "i2c") {                        // who answers on the internal bus: 0x18 codec, 0x34 keyboard, 0x68 IMU expected on the ADV
    bool found[128]; M5.In_I2C.scanID(found); Serial.print("internal i2c:");
    for (int a = 1; a < 128; a++) if (found[a]) Serial.printf(" 0x%02X", a);
    Serial.printf(" | sda=%d scl=%d\n", M5.In_I2C.getSDA(), M5.In_I2C.getSCL());
  }
  else if (cmd == "sdformat") {                   // format the card as FAT32 in place (exFAT cards above 32 GB come unmountable)
    static SPIClass* sp = nullptr; if (!sp) { sp = new SPIClass(FSPI); sp->begin(40, 39, 14, 12); }
    Serial.println("formatting, this takes a while…");
    bool ok = SD.begin(12, *sp, 4000000, "/sd", 5, true);
    Serial.printf("%s type=%d size=%.2fGB total=%.2fGB\n", ok ? "OK formatted and mounted" : "ERR", (int)SD.cardType(), SD.cardSize() / 1e9, SD.totalBytes() / 1e9);
    if (ok) { SD.end(); Serial.println(store::begin() ? "OK store mounted" : "ERR store mount"); if (store::mounted()) recorder::start(); }
  }
  else if (cmd == "miclevel") {                   // rms per second for N seconds: speak to the device and watch it move
    int secs = arg.length() ? arg.toInt() : 10; static int16_t buf[8000];
    bool was = recorder::status().running; if (was) recorder::stop();
    M5.Mic.setBufferReleaseCallback(nullptr, nullptr);
    M5.Speaker.end();                              // the speaker's I2S port shares the codec clock pins
    auto mc = M5.Mic.config(); Serial.printf("mic cfg: data=%d bck=%d ws=%d port=%d rate=%u\n", mc.pin_data_in, mc.pin_bck, mc.pin_ws, (int)mc.i2s_port, mc.sample_rate);
    if (!M5.Mic.begin()) { Serial.println("ERR mic begin"); return; }
    if (arg.indexOf("raw") < 0) recorder::applyGain();
    for (int t = 0; t < secs * 2; t++) {
      M5.Mic.record(buf, 8000, 16000); while (M5.Mic.isRecording()) delay(2);
      double sq = 0; int16_t pk = 0; for (int i = 0; i < 8000; i++) { sq += (double)buf[i] * buf[i]; if (abs(buf[i]) > pk) pk = abs(buf[i]); }
      Serial.printf("t=%4.1fs rms=%6.0f peak=%6d\n", t / 2.0, sqrt(sq / 8000), pk);
    }
    if (arg.indexOf("keep") < 0) { M5.Mic.end(); recorder::begin(); if (was) recorder::start(); } Serial.println("END");
  }
  else if (cmd == "i2cr") { int r = strtol(arg.c_str(), nullptr, 0); Serial.printf("es8311[0x%02X]=0x%02X\n", r, M5.In_I2C.readRegister8(0x18, r, 400000)); }
  else if (cmd == "i2cw") { int sp2 = arg.indexOf(' '); int r = strtol(arg.substring(0, sp2).c_str(), nullptr, 0), v = strtol(arg.substring(sp2 + 1).c_str(), nullptr, 0);
    Serial.printf("es8311[0x%02X] <- 0x%02X: %s\n", r, v, M5.In_I2C.writeRegister8(0x18, r, v, 400000) ? "ok" : "fail"); }
  else if (cmd == "micleft") { auto mc = M5.Mic.config(); mc.left_channel = arg.toInt(); M5.Mic.config(mc); Serial.printf("OK left_channel=%d\n", (int)mc.left_channel); }
  else if (cmd == "micstereo") { auto mc = M5.Mic.config(); mc.stereo = arg.toInt(); M5.Mic.config(mc); Serial.printf("OK stereo=%d\n", (int)mc.stereo); }
  else if (cmd == "regs") { for (int r : {0x00,0x01,0x02,0x03,0x04,0x05,0x09,0x0A,0x0B,0x0C,0x0D,0x0E,0x0F,0x10,0x11,0x14,0x15,0x16,0x17,0x1B,0x1C}) Serial.printf("%02X=%02X ", r, M5.In_I2C.readRegister8(0x18, r, 400000)); Serial.println(); }
  else if (cmd == "micgain") {                    // micgain <pga 0-7> [adcvol hex, 0xBF = 0 dB]; saved across reboots
    int sp2 = arg.indexOf(' '); int pga = arg.substring(0, sp2 < 0 ? arg.length() : sp2).toInt();
    int vol = sp2 < 0 ? 0xCF : (int)strtol(arg.substring(sp2 + 1).c_str(), nullptr, 0);
    recorder::setGain(pga, vol); Serial.printf("OK pga=%d (%d dB) adcvol=0x%02X\n", pga, pga * 6, vol);
  }
  else if (cmd == "mictest") {                    // one second from the mic while the speaker plays a tone: proves the codec path without a card or a person
    static int16_t buf[16000]; bool was = recorder::status().running; if (was) recorder::stop();
    if (!M5.Mic.begin()) { Serial.println("ERR mic begin"); return; }
    M5.Mic.setBufferReleaseCallback(nullptr, nullptr);
    Serial.println("recording 1 s: say something");
    M5.Mic.record(buf, 16000, 16000); while (M5.Mic.isRecording()) delay(5); M5.Mic.end();
    int16_t mn = 32767, mx = -32768; for (int i = 0; i < 16000; i++) { if (buf[i] < mn) mn = buf[i]; if (buf[i] > mx) mx = buf[i]; }
    Serial.printf("min=%d max=%d first=%d %d %d %d\n", mn, mx, buf[100], buf[101], buf[102], buf[103]);
    double sq = 0; int16_t pk = 0; for (int i = 0; i < 16000; i++) { sq += (double)buf[i] * buf[i]; if (abs(buf[i]) > pk) pk = abs(buf[i]); }
    Serial.printf("OK rms=%.0f peak=%d (silence ~<50, speech ~>500)\n", sqrt(sq / 16000), pk);
    recorder::begin(); if (was) recorder::start();
  }
  else if (cmd == "time") {
    struct timeval tv = { (time_t)strtoll(arg.c_str(), nullptr, 10), 0 }; settimeofday(&tv, nullptr);
    if (M5.Rtc.isEnabled()) M5.Rtc.setDateTime(gmtime(&tv.tv_sec));
    Serial.println("OK"); printStatus();
  }
  else Serial.println("ERR unknown command; try help");
}

void setup() {
  auto cfg = M5.config(); cfg.internal_mic = true;
  M5Cardputer.begin(cfg, true);
  Serial.begin(115200);
  setenv("TZ", "SGT-8", 1); tzset();          // local time from the start, so chunk names never flip between UTC and local
  delay(300);
  Serial.printf("\n[boot] turnwise cardputer, board=%d rtc=%s\n", (int)M5.getBoard(), M5.Rtc.isEnabled() ? "yes" : "no");
  if (M5.Rtc.isEnabled()) { M5.Rtc.setSystemTimeFromRtc(); }
  // The card can be mid-operation when the chip resets (a flash, a crash): give it a few tries before giving up.
  bool sd = false; for (int i = 0; i < 5 && !(sd = store::begin()); i++) delay(1000);
  if (!sd) Serial.println("[boot] no SD card after 5 tries: insert one, or power the device off and on");
  recorder::begin();
  if (store::mounted()) recorder::start();
  net::begin();
  ui::begin();
  Serial.println(HELP);
}

void loop() {
  M5Cardputer.update();
  net::loop();
  static String line;
  while (Serial.available()) { char c = Serial.read(); if (c == '\n' || c == '\r') { handle(line); line = ""; } else if (line.length() < 200) line += c; }
  ui::loop();
  delay(10);
}
