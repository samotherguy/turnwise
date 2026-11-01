# Turnwise Cardputer

A pocket recorder that listens all day, so a meeting is captured even when the phone is not out. It is an
[M5Stack Cardputer ADV](https://docs.m5stack.com/en/core/Cardputer-Adv) (ESP32-S3, microphone, microSD slot,
1.14" screen, 56-key keyboard, infrared LED, 1750 mAh battery) running this firmware. The Android app treats it as a
second source on the Listen tab: it pulls the minutes you ask for over Wi-Fi and sends them to Turnwise like any
other recording. Nothing about Turnwise lives on the device.

## What it does

- **Records continuously** at 16 kHz, 16-bit mono into 5-minute WAV chunks on the card, 120 chunks (10 hours)
  rolling. The oldest chunk is deleted first unless it has been marked to keep. A 64 GB card holds it many times over.
- **Recording never stops**, whatever is on screen. The microphone driver hands full buffers to a writer task on
  the other core; the screen, keyboard, Wi-Fi and the apps below run on their own.
- **Serves a small API** on the local network (home Wi-Fi or the phone's hotspot), found over mDNS as
  `turnwise-cardputer.local` (`_turnwise._tcp`): status, chunk list, chunk download, keep, mark, rotate, time, log.
- **Keeps time** from NTP whenever Wi-Fi is up, so chunks are named by wall clock (`20261101-124442.wav`); before
  that they are named by boot number and uptime and sort before any dated chunk.
- **One lock for the card.** The recorder's writer, the Wi-Fi streaming, listing, bookmarks and serial dumps all
  take the same lock, so a long pull can never starve a write. A card that still errors is remounted and recording
  continues in a fresh chunk; the card is retried at boot; a 64 GB card that arrives as exFAT can be formatted FAT32
  in place.

## On the screen

The top bar shows REC while recording, the time, the battery and whether Wi-Fi is up. The home menu lists the apps:
move the highlight with the arrow keys (the `;` `.` `,` `/` keys) and press Enter, or press the number. The `` ` ``
key (top left, esc) always returns home. Every app shows its keys on its bottom line.

| App | What it does | Keys |
| --- | --- | --- |
| 1 Recorder | elapsed time, chunks on the card, error counters | `m` keep the last 30 min, `r` start / stop |
| 2 Aircon remote | Mitsubishi Electric remote over the IR LED on the top edge (MSY/MSZ series by default) | `p` power, `-` `=` temperature, `m` mode, `f` fan, `t` other Mitsubishi variants, Enter resend |
| 3 GPU box | shows whether the Turnwise machine and the LLM box are on, through the [waker phone](../waker) on the LAN | `w` wake com, `l` wake com-2, `o` twice shut com down |
| 4 Bookmark | type a label, Enter: stamped with the time into `/rec/marks.txt` and the chunk is kept | letters, Del, Enter |
| 5 Clock | big clock, date, hours buffered, battery | |
| 6 Network | which Wi-Fi, address, time | `w` Wi-Fi on / off |
| 7 About | version, uptime, heap, card | |

## Build and flash

PlatformIO, Arduino framework, [M5Cardputer](https://github.com/m5stack/M5Cardputer) + M5Unified, and
IRremoteESP8266 for the remote. The device appears as `/dev/ttyACM0` (USB-JTAG); the udev rule in
`../waker/`-style setups is just `SUBSYSTEM=="tty", ATTRS{idVendor}=="303a", ATTRS{idProduct}=="1001", MODE="0666"`.

```bash
pip install platformio pyserial                 # in a venv
pio run -e cardputer -t upload                  # builds, flashes, resets
python tools/cp.py status                       # talk to it over USB
```

If flashing cannot connect, hold the G0 button while plugging the USB in and try again. The stock demo can be put
back with M5Burner.

**The platform is pinned to `espressif32@6.12.0`** (Arduino 2.0.17 on ESP-IDF 4.4) on purpose: the current core
on ESP-IDF 5.5 returns constant samples from the ADV's ES8311 codec microphone. IDF 5.4 also works
(the pioarduino platform `54.03.21`); 5.5 does not, as of November 2026.

## Serial commands

`status`, `ls`, `dump <name>` (the WAV, raw, after a `DUMP name size` line; `cp.py dump` saves it), `keep` /
`unkeep` / `rm <name>`, `mark <minutes>`, `rec on|off`, `rotate`, `time <epoch>`, `wifi add <ssid> <password>` /
`wifi forget <ssid>` / `wifi on|off` / `wifi`, `waker <url>`, `micgain <pga 0-7> [adcvol]`, `miclevel <seconds>`,
`ac on|off`, `sd` (remount), `sdformat` (FAT32, in place), `i2c`, `regs`, `log`, `reboot`, `help`.

## HTTP API

| Call | Returns |
| --- | --- |
| `GET /api/status` | recording, chunk, seconds, chunks, overruns, writeErrors, sdFree, sdTotal, battery, time, uptime, ip, name, version |
| `GET /api/chunks` | `[{name, size, kept, seconds}]`, oldest first |
| `GET /api/chunk?name=` | the WAV |
| `POST /api/keep?name=&on=1` / `POST /api/rm?name=` | keep or delete a chunk |
| `POST /api/mark?minutes=` | keep the newest chunks covering that many minutes |
| `POST /api/rotate` | close the current chunk now (the app does this before pulling) |
| `POST /api/rec?on=1` | start or stop |
| `POST /api/time?epoch=` | set the clock |
| `GET /api/marks` | the bookmarks file |
| `GET /api/log` | the last 40 events |

Chunks come at about 300 KB/s (a 5-minute chunk in ~32 s), so 30 minutes takes about three minutes to pull; the app
sends 15 minutes to 2 hours. The server answers one request at a time: while a chunk is streaming, a status call
waits or times out, which is why the app keeps showing the last status during a send.

## Hardware notes that cost time

- **Microphone.** M5Unified's default codec sequence records at a near-mute level. `recorder::applyGain()` sets
  the ES8311 input path the way Espressif's board package does (PGA, ALC, digital gain); 18 dB analog + 8 dB digital
  by default, saved in flash. 30 dB analog rails on DC offset. `Mic.begin()` rewrites the codec, so gain is applied
  after it.
- **Card.** It must sit on SPI2 (`SPIClass(FSPI)`); the display owns SPI3. Pins CS 12, MOSI 14, CLK 40, MISO 39.
  Listing the directory on a big FAT32 card is slow, so the chunk list is cached for five seconds. Reads and writes
  from two tasks at once made writes fail (a send pulling six chunks back to back forced a remount every time) until
  every card access went through `store::lock()`. The SD driver never recovers from a real card error on its own,
  hence the remount after six failed writes; the device keeps a 40-line event log (`log`, `GET /api/log`).
- **Serial commands run inside `loop()`**, so a long one (the format) blocks the screen until it finishes.
- **Pins:** codec ES8311 at I2C 0x18 (SDA 8, SCL 9), I2S BCK 41 / WS 43 / mic data 46 / speaker 42; keyboard
  TCA8418 at 0x34; IMU at 0x69; IR LED 44; battery on GPIO 10. No RTC.

## Layout

`src/main.cpp` boot and serial commands · `src/recorder.*` capture and chunk writing · `src/store.*` the rolling
store · `src/net.*` Wi-Fi, mDNS, NTP, API · `src/ui.*` screen and apps · `src/aircon.*` the remote ·
`src/log.*` the event ring · `tools/cp.py` host helper.
