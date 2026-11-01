#pragma once
#include <Arduino.h>
// A small ring of recent events in RAM, so a problem can be read back later over serial ("log") or GET /api/log.
namespace logring {
  void add(const String& line);
  String dump();
}

inline String sprintfS(const char* fmt, ...) { char b[200]; va_list a; va_start(a, fmt); vsnprintf(b, sizeof b, fmt, a); va_end(a); return String(b); }
