#include "log.h"
#include <time.h>
namespace { constexpr int N = 40; String lines[N]; int head = 0, count = 0; portMUX_TYPE mux = portMUX_INITIALIZER_UNLOCKED; }
void logring::add(const String& line) {
  time_t now = time(nullptr); char ts[12]; strftime(ts, sizeof ts, "%H:%M:%S", localtime(&now));
  String l = String(ts) + " " + line;
  portENTER_CRITICAL(&mux); lines[head] = l; head = (head + 1) % N; if (count < N) count++; portEXIT_CRITICAL(&mux);
  Serial.println(line);
}
String logring::dump() {
  String out; for (int i = 0; i < count; i++) out += lines[(head - count + i + N) % N] + "\n"; return out;
}
