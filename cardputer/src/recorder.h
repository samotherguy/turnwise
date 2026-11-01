#pragma once
#include <Arduino.h>

// Continuous 16 kHz / 16-bit mono capture into 5-minute WAV chunks. Never blocks: the mic driver
// hands full buffers to a callback, a writer task on core 0 puts them on the card.
namespace recorder {
  constexpr uint32_t RATE = 16000;
  constexpr uint32_t CHUNK_SECONDS = 300;
  struct Status { bool running; String chunk; uint32_t seconds; uint32_t overruns; uint32_t writeErrors; uint32_t chunks; };

  void begin();
  void start();
  void stop();
  void rotate();                 // close the current chunk and start a new one
  Status status();
  String chunkName();            // name for a chunk starting now
  void applyGain();
  void setGain(uint8_t pga, uint8_t adcvol);   // pga 0..7 (6 dB steps), adcvol 0xBF = 0 dB
}
