#include "recorder.h"
#include "store.h"
#include "log.h"
#include <M5Unified.h>
#include <SD.h>
#include <Preferences.h>
#include <time.h>

namespace {
  constexpr size_t BUF_SAMPLES = 2048;                 // 128 ms per buffer
  constexpr int BUFS = 6;                              // 2 in flight + ~512 ms of SD stall tolerance
  int16_t g_buf[BUFS][BUF_SAMPLES];
  QueueHandle_t g_filled, g_free;                      // int16_t* pointers
  TaskHandle_t g_task;
  volatile bool g_run = false, g_rotate = false;
  File g_file; String g_name; uint32_t g_bytes = 0, g_started = 0, g_overruns = 0, g_werr = 0, g_chunks = 0;
  uint32_t g_boot = 0, g_consecFail = 0;

  void writeHeader(File& f, uint32_t dataBytes) {
    uint8_t h[44]; uint32_t rate = recorder::RATE, byteRate = rate * 2, riff = 36 + dataBytes;
    memcpy(h, "RIFF", 4); memcpy(h + 4, &riff, 4); memcpy(h + 8, "WAVEfmt ", 8);
    uint32_t fmtLen = 16; uint16_t pcm = 1, ch = 1, align = 2, bits = 16;
    memcpy(h + 16, &fmtLen, 4); memcpy(h + 20, &pcm, 2); memcpy(h + 22, &ch, 2); memcpy(h + 24, &rate, 4);
    memcpy(h + 28, &byteRate, 4); memcpy(h + 32, &align, 2); memcpy(h + 34, &bits, 2);
    memcpy(h + 36, "data", 4); memcpy(h + 40, &dataBytes, 4);
    f.seek(0); f.write(h, 44);
  }
  void closeChunk() {
    store::Guard g;
    if (!g_file) return;
    writeHeader(g_file, g_bytes); g_file.close(); g_chunks++; store::invalidate();
    logring::add(String("") + sprintfS("[rec] closed %s %u bytes", g_name.c_str(), g_bytes));
    size_t pruned = store::prune(); if (pruned) logring::add(String("") + sprintfS("[rec] pruned %u old chunk(s)", pruned));
  }
  bool openChunk() {
    store::Guard g;
    g_name = recorder::chunkName(); g_bytes = 0; g_started = millis();
    g_file = SD.open(store::path(g_name), FILE_WRITE);
    if (!g_file) { logring::add(String("") + sprintfS("[rec] cannot open %s", g_name.c_str())); g_werr++; return false; }
    store::invalidate();
    writeHeader(g_file, 0);
    logring::add(String("") + sprintfS("[rec] opened %s", g_name.c_str()));
    return true;
  }
  // Called by the capture task when a buffer is full: hand it to the writer, queue a fresh one at once.
  void onBuffer(void*, void* data, size_t) {
    BaseType_t woke = pdFALSE;
    xQueueSendFromISR(g_filled, &data, &woke);          // ISR-safe variant is fine from a task too and never blocks
    int16_t* next = nullptr;
    if (xQueueReceiveFromISR(g_free, &next, &woke) == pdTRUE) {
      if (!M5.Mic.record(next, BUF_SAMPLES)) { xQueueSendFromISR(g_free, &next, &woke); g_overruns++; }
    } else g_overruns++;
  }
  void writer(void*) {
    int16_t* buf;
    for (;;) {
      if (xQueueReceive(g_filled, &buf, pdMS_TO_TICKS(500)) != pdTRUE) continue;
      if (g_run && store::mounted()) {
        if (!g_file || g_rotate || g_bytes >= recorder::RATE * 2 * recorder::CHUNK_SECONDS) { g_rotate = false; closeChunk(); openChunk(); }
        if (g_file) {
          // A write can come back short while the Wi-Fi side is streaming a chunk off the card (the filesystem lock
          // times out). That is transient: retry the same buffer a few times before calling it a failure.
          size_t n = 0;
          for (int attempt = 0; attempt < 4 && n != BUF_SAMPLES * 2; attempt++) {
            if (attempt) vTaskDelay(pdMS_TO_TICKS(30));
            store::Guard g;
            n = g_file.write((uint8_t*)buf, BUF_SAMPLES * 2);
            if (n != BUF_SAMPLES * 2 && n > 0) { g_file.seek(44 + g_bytes); }      // partial write: rewind to the buffer's start
          }
          if (n == BUF_SAMPLES * 2) { g_bytes += n; g_consecFail = 0; }
          else {
            g_werr++;
            // A card that errors for real stays dead in the SD driver until it is re-initialised. Remount and open a fresh chunk.
            if (++g_consecFail >= 6) { logring::add(String("") + sprintfS("[rec] %u failed writes, remounting the card", g_consecFail)); g_file.close(); g_consecFail = 0; vTaskDelay(pdMS_TO_TICKS(500)); store::remount(); if (store::mounted()) openChunk(); }
          }
          if ((g_bytes / (BUF_SAMPLES * 2)) % 32 == 0) { store::Guard g; g_file.flush(); }      // every ~4 s, so a crash loses little
        }
      }
      xQueueSend(g_free, &buf, 0);
    }
  }
}

String recorder::chunkName() {
  time_t now = time(nullptr); struct tm t; localtime_r(&now, &t);
  char s[40];
  if (t.tm_year + 1900 >= 2025) strftime(s, sizeof s, "%Y%m%d-%H%M%S.wav", &t);   // the clock is set: real timestamps
  else snprintf(s, sizeof s, "0000-b%04u-%06u.wav", g_boot, (unsigned)(millis() / 1000));   // no clock yet: boot number + uptime
  return s;
}

// ES8311 input gain: PGA 0..7 (0/6/12/18/24/30/36/42 dB) and ADC volume (0xBF = 0 dB, +0.5 dB per step). Persisted.
static uint8_t g_pga = 3, g_adcvol = 0xCF;   // 18 dB analog + 8 dB digital; 30 dB analog rails on DC offset
void recorder::applyGain() {   // the ES8311 input path as Espressif's board package sets it; M5Unified's defaults record near-mute
  const uint8_t seq[][2] = { {0x0E, 0x02}, {0x14, (uint8_t)(0x10 | (g_pga & 0x0F))}, {0x15, 0x40}, {0x16, 0x24}, {0x17, g_adcvol}, {0x1C, 0x6A} };
  for (auto& e : seq) M5.In_I2C.writeRegister8(0x18, e[0], e[1], 400000);
}
void recorder::setGain(uint8_t pga, uint8_t adcvol) {
  g_pga = pga; g_adcvol = adcvol;
  Preferences p; p.begin("rec"); p.putUChar("pga", pga); p.putUChar("adcvol", adcvol); p.end();
  if (g_run) applyGain();
}
void recorder::begin() {
  Preferences p; p.begin("rec"); g_boot = p.getUInt("boot", 0) + 1; p.putUInt("boot", g_boot); g_pga = p.getUChar("pga", 4); g_adcvol = p.getUChar("adcvol", 0xCF); p.end();
  g_filled = xQueueCreate(BUFS, sizeof(int16_t*)); g_free = xQueueCreate(BUFS, sizeof(int16_t*));
  for (int i = 2; i < BUFS; i++) { int16_t* b = g_buf[i]; xQueueSend(g_free, &b, 0); }
  xTaskCreatePinnedToCore(writer, "recwriter", 6144, nullptr, 5, &g_task, 0);
  auto mc = M5.Mic.config(); mc.sample_rate = RATE; mc.magnification = 16; M5.Mic.config(mc);
  M5.Mic.setBufferReleaseCallback(nullptr, onBuffer);
}
void recorder::start() {
  if (g_run) return;
  if (!M5.Mic.begin()) { logring::add("[rec] mic begin failed"); return; }
  applyGain();
  g_run = true;
  M5.Mic.record(g_buf[0], BUF_SAMPLES, RATE); M5.Mic.record(g_buf[1], BUF_SAMPLES, RATE);
  logring::add("[rec] recording");
}
void recorder::stop() {
  if (!g_run) return;
  g_run = false; M5.Mic.end();
  delay(50); closeChunk();
  logring::add("[rec] stopped");
}
void recorder::rotate() { g_rotate = true; }
recorder::Status recorder::status() {
  return { g_run, g_name, g_file ? (millis() - g_started) / 1000 : 0, g_overruns, g_werr, g_chunks };
}
