#include "net.h"
#include "recorder.h"
#include "store.h"
#include "log.h"
#include <M5Unified.h>
#include <WiFi.h>
#include <WiFiMulti.h>
#include <ESPmDNS.h>
#include <WebServer.h>
#include <Preferences.h>
#include <SD.h>
#include <time.h>

namespace {
  constexpr const char* HOSTNAME = "turnwise-cardputer";
  constexpr int MAX_NETS = 4;
  WiFiMulti multi;
  WebServer server(80);
  bool s_enabled = true, s_up = false, s_served = false;
  uint32_t s_lastTry = 0;

  int count() { Preferences p; p.begin("wifi", true); int n = p.getInt("n", 0); p.end(); return n; }
  void loadInto(WiFiMulti& m) {
    Preferences p; p.begin("wifi", true);
    for (int i = 0; i < p.getInt("n", 0); i++) m.addAP(p.getString(("s" + String(i)).c_str()).c_str(), p.getString(("p" + String(i)).c_str()).c_str());
    p.end();
  }
  String json(const String& body) { return "{" + body + "}"; }
  String q(const String& s) { String o = "\""; for (char c : s) { if (c == '"' || c == '\\') o += '\\'; o += c; } return o + "\""; }

  String statusJson() {
    auto r = recorder::status();
    return json(
      "\"recording\":" + String(r.running ? "true" : "false") + ",\"chunk\":" + q(r.chunk) + ",\"seconds\":" + r.seconds +
      ",\"chunks\":" + store::list().size() + ",\"overruns\":" + r.overruns + ",\"writeErrors\":" + r.writeErrors +
      ",\"sdFree\":" + String((double)store::freeBytes(), 0) + ",\"sdTotal\":" + String((double)store::totalBytes(), 0) +
      ",\"battery\":" + M5.Power.getBatteryLevel() + ",\"time\":" + String((long)time(nullptr)) + ",\"uptime\":" + (millis() / 1000) +
      ",\"ip\":" + q(WiFi.localIP().toString()) + ",\"name\":" + q(HOSTNAME) + ",\"version\":\"0.2\"");
  }
  void routes() {
    server.on("/api/status", HTTP_GET, [] { server.send(200, "application/json", statusJson()); });
    server.on("/api/chunks", HTTP_GET, [] {
      String o = "["; bool first = true;
      for (auto& c : store::list()) {
        if (!first) o += ","; first = false;
        o += json("\"name\":" + q(c.name) + ",\"size\":" + c.size + ",\"kept\":" + (c.kept ? "true" : "false") + ",\"seconds\":" + (c.size > 44 ? (c.size - 44) / (recorder::RATE * 2) : 0));
      }
      server.send(200, "application/json", o + "]");
    });
    server.on("/api/chunk", HTTP_GET, [] {
      String name = server.arg("name");
      if (!name.length() || name.indexOf('/') >= 0) { server.send(400, "text/plain", "name?"); return; }
      store::lock(); File f = SD.open(store::path(name)); size_t total = f ? f.size() : 0; store::unlock();
      if (!f) { server.send(404, "text/plain", "no such chunk"); return; }
      server.sendHeader("Content-Disposition", "attachment; filename=\"" + name + "\"");
      // Stream by hand: each read holds the card lock briefly, then the recorder's writer can take its turn.
      server.setContentLength(total); server.send(200, "audio/wav", "");
      static uint8_t buf[8192]; WiFiClient client = server.client(); size_t sent = 0;
      while (sent < total && client.connected()) {
        size_t n; { store::Guard g; n = f.read(buf, sizeof buf); }
        if (n == 0) break;
        if (client.write(buf, n) != n) break;
        sent += n;
      }
      { store::Guard g; f.close(); }
      if (sent < total) client.stop();          // never leave a client waiting for bytes that will not come
    });
    server.on("/api/log", HTTP_GET, [] { server.send(200, "text/plain", logring::dump()); });
    server.on("/api/marks", HTTP_GET, [] { store::Guard g; File f = SD.open("/rec/marks.txt"); if (!f) { server.send(200, "text/plain", ""); return; } server.streamFile(f, "text/plain"); f.close(); });
    server.on("/api/keep", HTTP_POST, [] { server.send(store::keep(server.arg("name"), server.arg("on") != "0") ? 200 : 500, "application/json", "{\"ok\":true}"); });
    server.on("/api/rm", HTTP_POST, [] { server.send(store::remove(server.arg("name")) ? 200 : 500, "application/json", "{\"ok\":true}"); });
    server.on("/api/mark", HTTP_POST, [] { server.send(200, "application/json", json("\"kept\":" + String(store::markLast(server.arg("minutes").toInt() > 0 ? server.arg("minutes").toInt() : 30)))); });
    server.on("/api/rotate", HTTP_POST, [] { recorder::rotate(); server.send(200, "application/json", "{\"ok\":true}"); });
    server.on("/api/rec", HTTP_POST, [] { if (server.arg("on") == "1") recorder::start(); else recorder::stop(); server.send(200, "application/json", statusJson()); });
    server.on("/api/time", HTTP_POST, [] {
      struct timeval tv = { (time_t)strtoll(server.arg("epoch").c_str(), nullptr, 10), 0 }; settimeofday(&tv, nullptr);
      server.send(200, "application/json", statusJson());
    });
    server.onNotFound([] { server.send(404, "text/plain", "turnwise cardputer: /api/status /api/chunks /api/chunk?name= /api/keep /api/rm /api/mark /api/rotate /api/rec /api/time"); });
  }
  void onUp() {
    s_up = true;
    logring::add(String("") + sprintfS("[net] connected to %s ip=%s rssi=%d", WiFi.SSID().c_str(), WiFi.localIP().toString().c_str(), WiFi.RSSI()));
    configTzTime("SGT-8", "pool.ntp.org", "time.google.com");      // Singapore; chunks get real timestamps once NTP answers
    if (MDNS.begin(HOSTNAME)) { MDNS.addService("turnwise", "tcp", 80); MDNS.addService("http", "tcp", 80); }
    if (!s_served) { routes(); server.begin(); s_served = true; }
  }
}

void net::begin() {
  Preferences p; p.begin("wifi", true); s_enabled = p.getBool("on", true); p.end();
  WiFi.mode(WIFI_STA); WiFi.setHostname(HOSTNAME); WiFi.setSleep(true);
  loadInto(multi);
  logring::add(String("") + sprintfS("[net] %d known network(s), wifi %s", count(), s_enabled ? "on" : "off"));
}
void net::loop() {
  if (!s_enabled) return;
  if (WiFi.status() == WL_CONNECTED) { if (!s_up) onUp(); server.handleClient(); return; }
  if (s_up) { s_up = false; logring::add("[net] disconnected"); }
  if (count() && millis() - s_lastTry > 15000) { s_lastTry = millis(); multi.run(4000); }
}
bool net::connected() { return s_enabled && WiFi.status() == WL_CONNECTED; }
String net::ip() { return connected() ? WiFi.localIP().toString() : ""; }
bool net::enabled() { return s_enabled; }
void net::setEnabled(bool on) {
  s_enabled = on; Preferences p; p.begin("wifi"); p.putBool("on", on); p.end();
  if (!on) { WiFi.disconnect(true); s_up = false; } else { s_lastTry = 0; }
}
void net::addNetwork(const String& ssid, const String& pass) {
  Preferences p; p.begin("wifi"); int n = p.getInt("n", 0), slot = n;
  for (int i = 0; i < n; i++) if (p.getString(("s" + String(i)).c_str()) == ssid) slot = i;
  if (slot >= MAX_NETS) slot = MAX_NETS - 1;
  p.putString(("s" + String(slot)).c_str(), ssid); p.putString(("p" + String(slot)).c_str(), pass);
  if (slot == n) p.putInt("n", n + 1);
  p.end(); multi.addAP(ssid.c_str(), pass.c_str()); s_lastTry = 0;
}
void net::forget(const String& ssid) {
  Preferences p; p.begin("wifi"); int n = p.getInt("n", 0), w = 0;
  String s[MAX_NETS], pw[MAX_NETS];
  for (int i = 0; i < n; i++) { String a = p.getString(("s" + String(i)).c_str()), b = p.getString(("p" + String(i)).c_str()); if (a != ssid) { s[w] = a; pw[w] = b; w++; } }
  for (int i = 0; i < w; i++) { p.putString(("s" + String(i)).c_str(), s[i]); p.putString(("p" + String(i)).c_str(), pw[i]); }
  p.putInt("n", w); p.end();
}
String net::describe() {
  if (!s_enabled) return "wifi off";
  if (WiFi.status() == WL_CONNECTED) return WiFi.SSID() + " " + WiFi.localIP().toString() + " rssi " + WiFi.RSSI();
  return count() ? "wifi: searching (" + String(count()) + " known)" : "wifi: no networks saved";
}
