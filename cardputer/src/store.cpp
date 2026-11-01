#include "store.h"
#include "log.h"
#include <SD.h>
#include <SPI.h>
#include <algorithm>

// Cardputer microSD: SPI, SCK 40, MISO 39, MOSI 14, CS 12
static constexpr int SD_SCK = 40, SD_MISO = 39, SD_MOSI = 14, SD_CS = 12;
static bool s_mounted = false;
static SPIClass s_spi(FSPI);                  // the display sits on SPI3 (HSPI); the card gets SPI2 (FSPI)
static std::vector<String> s_keep;            // names flagged to keep, mirrored in /rec/keep.txt

static void loadKeep() {
  s_keep.clear();
  File f = SD.open("/rec/keep.txt");
  if (!f) return;
  while (f.available()) { String l = f.readStringUntil('\n'); l.trim(); if (l.length()) s_keep.push_back(l); }
  f.close();
}
static void saveKeep() {
  File f = SD.open("/rec/keep.txt", FILE_WRITE);
  if (!f) return;
  for (auto& n : s_keep) f.println(n);
  f.close();
}
static bool isKept(const String& n) { return std::find(s_keep.begin(), s_keep.end(), n) != s_keep.end(); }

bool store::begin() {
  s_spi.begin(SD_SCK, SD_MISO, SD_MOSI, SD_CS);
  s_mounted = SD.begin(SD_CS, s_spi, 4000000);                                 // slow first; FAT32 up to 32 GB (no exFAT)
  logring::add(String("") + sprintfS("[sd] mount=%s type=%d size=%.1fGB", s_mounted ? "ok" : "fail", (int)SD.cardType(), SD.cardSize() / 1e9));
  if (!s_mounted) return false;
  if (!SD.exists(DIR)) SD.mkdir(DIR);
  loadKeep();
  return true;
}
bool store::remount() { SD.end(); s_mounted = false; s_spi.end(); delay(50); invalidate(); return begin(); }
bool store::mounted() { return s_mounted; }
uint64_t store::freeBytes() { return s_mounted ? SD.totalBytes() - SD.usedBytes() : 0; }
uint64_t store::totalBytes() { return s_mounted ? SD.totalBytes() : 0; }
String store::path(const String& name) { return String(DIR) + "/" + name; }

static std::vector<store::Chunk> s_cache; static uint32_t s_cacheAt = 0; static bool s_cacheValid = false;
void store::invalidate() { s_cacheValid = false; }
std::vector<store::Chunk> store::list() {
  // Listing a FAT32 directory on a 64 GB card is slow and holds the filesystem lock, which starves the
  // recorder's writes; the screen and the API ask several times a second, so serve them from a cache.
  if (s_cacheValid && millis() - s_cacheAt < 5000) return s_cache;
  std::vector<Chunk> out;
  if (!s_mounted) return out;
  File dir = SD.open(DIR);
  if (!dir) return out;
  for (File f = dir.openNextFile(); f; f = dir.openNextFile()) {
    String n = f.name();
    int slash = n.lastIndexOf('/'); if (slash >= 0) n = n.substring(slash + 1);
    if (!f.isDirectory() && n.endsWith(".wav")) out.push_back({n, (uint32_t)f.size(), isKept(n)});
    f.close();
  }
  dir.close();
  std::sort(out.begin(), out.end(), [](const Chunk& a, const Chunk& b) { return a.name < b.name; });
  s_cache = out; s_cacheAt = millis(); s_cacheValid = true;
  return out;
}
bool store::keep(const String& name, bool on) {
  if (!s_mounted) return false;
  invalidate();
  if (on && !isKept(name)) s_keep.push_back(name);
  if (!on) s_keep.erase(std::remove(s_keep.begin(), s_keep.end(), name), s_keep.end());
  saveKeep();
  return true;
}
bool store::remove(const String& name) {
  if (!s_mounted) return false;
  keep(name, false); invalidate();
  return SD.remove(path(name));
}
size_t store::prune() {
  auto all = list();
  size_t over = all.size() > MAX_CHUNKS ? all.size() - MAX_CHUNKS : 0, deleted = 0;
  for (auto& c : all) {
    if (deleted >= over) break;
    if (c.kept) continue;
    if (SD.remove(path(c.name))) deleted++;
  }
  if (deleted) invalidate();
  return deleted;
}
size_t store::markLast(uint32_t minutes) {
  auto all = list();
  size_t n = 0; uint32_t covered = 0;
  for (auto it = all.rbegin(); it != all.rend() && covered < minutes * 60; ++it) {
    keep(it->name, true); n++;
    covered += (it->size > 44) ? (it->size - 44) / (16000 * 2) : 1;   // seconds of audio in the chunk
  }
  return n;
}
