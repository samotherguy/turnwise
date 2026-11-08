#pragma once
#include <Arduino.h>
#include <vector>

// The rolling chunk store on the SD card: /rec/<name>.wav, oldest deleted first unless kept.
namespace store {
  // Every FatFS call goes through this lock: the writer (high priority) and the Wi-Fi streaming, listing,
  // bookmarks and serial dumps never touch the card at the same time.
  void lock();
  void unlock();
  struct Guard { Guard() { lock(); } ~Guard() { unlock(); } };
  constexpr const char* DIR = "/rec";
  constexpr size_t MAX_CHUNKS = 120;          // 120 x 5 min = 10 h
  struct Chunk { String name; uint32_t size; bool kept; };

  bool begin();                               // mount the card; false if no card
  bool remount();                             // after a card error: end + begin
  bool mounted();
  uint64_t freeBytes();
  uint64_t totalBytes();
  std::vector<Chunk> list();                  // sorted by name == chronological
  bool keep(const String& name, bool on);
  bool remove(const String& name);
  size_t prune();                             // delete the oldest unkept chunks beyond MAX_CHUNKS; returns how many
  size_t markLast(uint32_t minutes);          // keep the newest chunks covering `minutes`; returns how many marked
  String path(const String& name);
  void invalidate();                          // the chunk list changed (new chunk closed, delete)
}
