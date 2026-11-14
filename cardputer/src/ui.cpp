#include "ui.h"
#include "recorder.h"
#include "store.h"
#include "net.h"
#include "aircon.h"
#include <M5Cardputer.h>
#include <HTTPClient.h>
#include <Preferences.h>
#include <SD.h>
#include <time.h>

namespace {
  M5Canvas canvas(&M5Cardputer.Display);
  enum App { HOME, RECORDER, AIRCON, BOX, BOOKMARK, CLOCK, NETWORK, ABOUT };
  App app = HOME; int sel = 0;
  const char* APPS[] = { "Recorder", "Aircon remote", "GPU box", "Bookmark", "Clock", "Network", "About" };
  const App APP_IDS[] = { RECORDER, AIRCON, BOX, BOOKMARK, CLOCK, NETWORK, ABOUT };
  uint32_t lastDraw = 0; bool dirty = true; String toast; uint32_t toastUntil = 0;
  const uint16_t GREEN = 0x1DB954 >> 8 & 0xF800 | 0x1DB954 >> 5 & 0x07E0 | 0x1DB954 >> 3 & 0x1F;   // ~ #1DB954 in RGB565
  constexpr uint16_t MIST = 0xB5B6, INK = 0x0000, PANEL = 0x18E3, RED = 0xF9A6, WHITE = 0xFFFF;

  void say(const String& t) { toast = t; toastUntil = millis() + 2500; dirty = true; }

  // --- Box: the GPU box through the waker phone on the LAN ---
  String wakerUrl() { Preferences p; p.begin("box", true); String u = p.getString("waker", "http://waker.local:8490"); p.end(); return u; }
  int boxUp = -1, llmUp = -1; uint32_t boxPolled = 0, offArmed = 0;
  String http(const String& method, const String& url) {
    if (!net::connected()) return "";
    HTTPClient c; c.setConnectTimeout(1500); c.setTimeout(4000); c.begin(url);
    int code = method == "POST" ? c.POST("") : c.GET(); String body = code > 0 ? c.getString() : ""; c.end();
    return code == 200 ? body : "";
  }
  void pollBox() {
    boxPolled = millis(); String b = http("GET", wakerUrl() + "/status");
    if (!b.length()) { boxUp = llmUp = -1; return; }
    boxUp = b.indexOf("\"server\": true") >= 0; llmUp = b.indexOf("\"llm\": true") >= 0;
  }

  // --- Bookmark: a typed label stamped into the recording ---
  String input, marksCache; bool marksDirty = true;
  void saveMark(const String& label) {
    time_t now = time(nullptr); char ts[24]; strftime(ts, sizeof ts, "%Y-%m-%d %H:%M:%S", localtime(&now));
    store::Guard g;
    File f = SD.open("/rec/marks.txt", FILE_APPEND); if (f) { f.printf("%s\t%s\t%s\n", ts, recorder::status().chunk.c_str(), label.c_str()); f.close(); }
    store::keep(recorder::status().chunk, true); marksDirty = true;
    Serial.printf("[mark] %s %s\n", ts, label.c_str());
  }
  String lastMarks(int n) {
    store::Guard g;
    File f = SD.open("/rec/marks.txt"); if (!f) return "";
    String lines[8]; int k = 0;
    while (f.available()) { lines[k % 8] = f.readStringUntil('\n'); k++; }
    f.close(); String out;
    for (int i = max(0, k - n); i < k; i++) { String l = lines[i % 8]; int t1 = l.indexOf('\t'), t2 = l.indexOf('\t', t1 + 1); out += l.substring(11, 16) + "  " + l.substring(t2 + 1) + "\n"; }
    return out;
  }

  void bar() {
    auto r = recorder::status();
    canvas.fillRect(0, 0, 240, 16, PANEL);
    canvas.setTextSize(1); canvas.setTextColor(WHITE, PANEL);
    if (r.running) { if ((millis() / 600) % 2 == 0) canvas.fillCircle(8, 8, 4, RED); canvas.setCursor(16, 4); canvas.print("REC"); }
    else { canvas.setTextColor(MIST, PANEL); canvas.setCursor(4, 4); canvas.print("idle"); }
    time_t now = time(nullptr); char ts[12]; strftime(ts, sizeof ts, "%H:%M", localtime(&now));
    canvas.setTextColor(WHITE, PANEL); canvas.setCursor(108, 4); canvas.print(ts);
    canvas.setCursor(176, 4); canvas.printf("%3d%%", M5.Power.getBatteryLevel());
    canvas.setTextColor(net::connected() ? GREEN : MIST, PANEL); canvas.setCursor(212, 4); canvas.print(net::connected() ? "wifi" : "    ");
  }
  void title(const char* t) { canvas.setTextColor(WHITE); canvas.setTextSize(2); canvas.setCursor(6, 22); canvas.print(t); }
  void line(int y, const String& s, uint16_t c = MIST) { canvas.setTextSize(1); canvas.setTextColor(c); canvas.setCursor(6, y); canvas.print(s); }

  void drawHome() {
    title("Turnwise");
    for (int i = 0; i < 7; i++) {
      int col = i < 4 ? 0 : 1, row = i < 4 ? i : i - 4; int x = 6 + col * 118, y = 44 + row * 15;
      if (i == sel) canvas.fillRoundRect(x - 3, y - 3, 112, 14, 3, GREEN);
      canvas.setTextSize(1); canvas.setTextColor(i == sel ? INK : WHITE); canvas.setCursor(x, y); canvas.printf("%d  %s", i + 1, APPS[i]);
    }
    line(110, "Recording never stops while you use these.");
    line(122, "arrows / number + enter to open   ` esc = home", WHITE);
  }
  void openApp(int i) {
    sel = i; app = APP_IDS[i];
    if (app == BOX) boxPolled = 0;
    if (app == BOOKMARK) { input = ""; marksDirty = true; }
  }
  void drawRecorder() {
    auto r = recorder::status();
    title(r.running ? "Listening" : "Not listening");
    canvas.setTextSize(3); canvas.setTextColor(WHITE); canvas.setCursor(6, 44); canvas.printf("%02u:%02u", r.seconds / 60, r.seconds % 60);
    size_t n = store::list().size();
    line(74, String("chunk ") + (r.chunk.length() ? r.chunk : "-"));
    line(86, String(n) + " chunks on card, about " + String(n * 5 / 60.0, 1) + " h  |  " + String(store::freeBytes() / 1e9, 1) + " GB free");
    line(98, "overruns " + String(r.overruns) + "  write errors " + String(r.writeErrors));
    line(116, "m  keep the last 30 min    r  start / stop", WHITE);
  }
  void drawAircon() {
    auto s = aircon::state();
    title("Aircon");
    canvas.setTextSize(3); canvas.setTextColor(s.power ? WHITE : MIST); canvas.setCursor(6, 44); canvas.printf("%d", s.temp);
    canvas.setTextSize(2); canvas.setCursor(50, 50); canvas.print(s.power ? "ON" : "OFF");
    canvas.setTextSize(2); canvas.setCursor(110, 50); canvas.print(aircon::modeName());
    line(74, String("fan ") + aircon::fanName() + "   protocol: " + aircon::protocolName());
    line(92, "p power   - / =  temp   m mode   f fan", WHITE);
    line(104, "t next protocol (if the unit ignores it)   enter resend", WHITE);
  }
  void drawBox() {
    title("GPU box");
    auto dot = [&](int y, const char* name, int up) { canvas.fillCircle(12, y + 4, 4, up == 1 ? GREEN : up == 0 ? RED : MIST); canvas.setTextSize(1); canvas.setTextColor(WHITE); canvas.setCursor(24, y); canvas.printf("%s  %s", name, up == 1 ? "online" : up == 0 ? "off" : "unknown"); };
    dot(46, "com    Turnwise server", boxUp); dot(62, "com-2  LLM box", llmUp);
    line(84, net::connected() ? "via waker " + wakerUrl().substring(7) : "no wifi: cannot reach the waker");
    line(104, "w wake com    l wake com-2    o shut down com (press twice)", WHITE);
  }
  void drawBookmark() {
    title("Bookmark");
    line(44, "Type a label and press enter. It is stamped with the time");
    line(56, "and the chunk is kept, so it survives the rolling buffer.");
    canvas.fillRoundRect(6, 70, 228, 18, 4, PANEL); canvas.setTextSize(1); canvas.setTextColor(WHITE); canvas.setCursor(10, 75); canvas.print(input + ((millis() / 500) % 2 ? "_" : " "));
    if (marksDirty) { marksCache = lastMarks(3); marksDirty = false; }
    int y = 94; String m = marksCache; int from = 0;
    while (from < (int)m.length() && y < 130) { int nl = m.indexOf('\n', from); if (nl < 0) nl = m.length(); line(y, m.substring(from, nl)); from = nl + 1; y += 12; }
  }
  void drawClock() {
    time_t now = time(nullptr); struct tm t; localtime_r(&now, &t); char ts[16], ds[32];
    strftime(ts, sizeof ts, "%H:%M", &t); strftime(ds, sizeof ds, "%A %d %B %Y", &t);
    canvas.setTextSize(6); canvas.setTextColor(WHITE); canvas.setCursor(24, 34); canvas.print(ts);
    line(92, ds, WHITE);
    auto r = recorder::status();
    line(110, (r.running ? "listening " : "idle ") + String(store::list().size() * 5 / 60.0, 1) + " h buffered   bat " + String(M5.Power.getBatteryLevel()) + "%");
  }
  void drawNetwork() {
    title("Network");
    line(46, net::describe(), WHITE);
    time_t now = time(nullptr); char ts[32]; strftime(ts, sizeof ts, "%Y-%m-%d %H:%M:%S", localtime(&now));
    line(60, String("time ") + ts);
    line(74, "App address: http://" + (net::ip().length() ? net::ip() : String("-")));
    line(100, "w  wifi on / off", WHITE);
  }
  void drawAbout() {
    title("About");
    line(46, "Turnwise Cardputer 0.4", WHITE);
    line(60, "uptime " + String(millis() / 1000) + " s   heap " + String(ESP.getFreeHeap() / 1024) + " KB");
    line(74, "card " + String(store::totalBytes() / 1e9, 1) + " GB, " + String(store::freeBytes() / 1e9, 1) + " GB free");
    line(88, "mic gain: serial 'micgain'   wifi: serial 'wifi add'");
  }
  void draw() {
    canvas.fillScreen(INK);
    bar();
    switch (app) { case HOME: drawHome(); break; case RECORDER: drawRecorder(); break; case AIRCON: drawAircon(); break; case BOX: drawBox(); break; case BOOKMARK: drawBookmark(); break; case CLOCK: drawClock(); break; case NETWORK: drawNetwork(); break; case ABOUT: drawAbout(); break; }
    if (millis() < toastUntil) { canvas.fillRoundRect(20, 56, 200, 24, 6, GREEN); canvas.setTextSize(1); canvas.setTextColor(INK); canvas.setCursor(28, 64); canvas.print(toast); }
    canvas.pushSprite(0, 0);
  }

  void key(char c) {
    if (c == '`') { app = HOME; dirty = true; return; }
    switch (app) {
      case HOME:
        if (c >= '1' && c <= '7') openApp(c - '1');
        else if (c == ';' || c == ',') sel = (sel + 6) % 7;        // the keys with the up / left arrows printed on them
        else if (c == '.' || c == '/') sel = (sel + 1) % 7;        // down / right
        break;
      case BOX:
        if (c == 'w') { say(http("POST", wakerUrl() + "/wake").length() ? "wake sent to com" : "waker not reachable"); boxPolled = 0; }
        if (c == 'l') { say(http("POST", wakerUrl() + "/wake/llm").length() ? "wake sent to com-2" : "waker not reachable"); }
        if (c == 'o') { if (millis() - offArmed < 3000) { say(http("POST", wakerUrl() + "/off").indexOf("true") >= 0 ? "com shutting down" : "shutdown failed"); offArmed = 0; boxPolled = 0; } else { offArmed = millis(); say("press o again to shut down com"); } }
        break;
      case BOOKMARK:
        if (input.length() < 40) input += c;
        break;
      case RECORDER:
        if (c == 'm') say("kept last 30 min: " + String(store::markLast(30)) + " chunks");
        if (c == 'r') { if (recorder::status().running) { recorder::stop(); say("stopped"); } else { recorder::start(); say("listening"); } }
        break;
      case AIRCON:
        if (c == 'p') { aircon::power(!aircon::state().power); say(aircon::state().power ? "sent: on" : "sent: off"); }
        if (c == '-') { aircon::temp(-1); say("sent"); }
        if (c == '=' || c == '+') { aircon::temp(1); say("sent"); }
        if (c == 'm') { aircon::nextMode(); say(String("sent: ") + aircon::modeName()); }
        if (c == 'f') { aircon::nextFan(); say(String("sent: fan ") + aircon::fanName()); }
        if (c == 't') { aircon::nextProtocol(); say(aircon::protocolName()); }
        break;
      case NETWORK:
        if (c == 'w') { net::setEnabled(!net::enabled()); say(net::enabled() ? "wifi on" : "wifi off"); }
        break;
      default: break;
    }
    dirty = true;
  }
}

void ui::begin() {
  M5Cardputer.Display.setRotation(1); M5Cardputer.Display.setBrightness(80);
  canvas.setColorDepth(8); canvas.createSprite(240, 135);
  aircon::begin();
  draw();
}
void ui::loop() {
  if (M5Cardputer.Keyboard.isChange() && M5Cardputer.Keyboard.isPressed()) {
    auto st = M5Cardputer.Keyboard.keysState();
    if (st.enter && app == HOME) { openApp(sel); dirty = true; }
    if (st.enter && app == AIRCON) { aircon::send(); say("sent"); }
    if (app == BOOKMARK) {
      if (st.del && input.length()) { input.remove(input.length() - 1); dirty = true; }
      if (st.enter) { saveMark(input.length() ? input : "mark"); say("bookmarked"); input = ""; }
      if (st.space) { input += ' '; dirty = true; }
    }
    for (char c : st.word) key(c);
  }
  if (app == BOX && net::connected() && millis() - boxPolled > 5000) { pollBox(); dirty = true; }
  if (dirty || millis() - lastDraw > 500) { lastDraw = millis(); dirty = false; draw(); }
}
