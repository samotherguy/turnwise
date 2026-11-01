# Native Turnwise (Android)

## Intent
The Android app becomes Turnwise itself, not only a recorder that hands clips to the web app. Every screen of the Turnwise web app (`audio-notes/static/index.html`) is rebuilt natively in Compose, in the dark green style of the Listen tab. No WebView (the user said so explicitly).

## Tabs
| Tab | What it holds |
|---|---|
| Notes (home) | Search, By date / By folder, status pills, Upload and Record actions |
| Listen | The rolling buffer, unchanged from the verified build |
| Ask | All chats, New chat |
| Clips | Saved clips from the buffer (previously "Library") |
| Settings | Listener (window, Turnwise address, battery) and server (speaker detection, voices, transcription options, models, account) |

Pushed screens: Note, Chats for a note, Chat. Back handled with a small state stack.

## Screens and API
- **Notes:** `GET /api/notes`, `GET /api/search?q=` (250 ms debounce), `GET /api/settings` for the speaker-detection banner. Poll 3 s while any note is busy.
- **Note:** `GET /api/notes/{id}`; title rename and folder move via `PATCH`; speaker naming via `PATCH {speakers}`; `GET .../md` for copy; `POST .../retranscribe`; `DELETE`. Progress steps while busy, poll 3 s. Audio streams from `.../audio`. Tap a turn to play from it; the playing turn is highlighted. Modes: Hinglish, English, Original.
- **Chats:** `GET /api/chats[?note=]`, `POST /api/chats`.
- **Chat:** `GET /api/chats/{sid}`, `POST .../ask {text, model}`, `.../clear`, `DELETE`. Local/Claude toggle only for the owner (`/api/me`). Poll 2 s while answering. `[n]` citations map to `sources[n-1]`; each source plays from its turn in the mini player.
- **Settings:** `GET/PUT /api/settings`, `GET /api/speakers`, `DELETE /api/speakers/{name}`.
- **Upload:** system picker (multiple, audio and video), serial `POST /api/upload` with optional folder. The share sheet (`ACTION_SEND`) uploads the same way.
- **Record:** marks a start point in the rolling buffer (starting the listener if needed); Stop exports start→now and uploads. `/api/record` is not used because it appends to one file, which suits the browser's webm stream but not m4a chunks.

## Builder calls
- One `Api` object over HttpURLConnection and org.json with 20 s timeouts, no new dependencies, no Hilt or modules. Per-screen state in composables; eight screens do not need a DI graph.
- Sent clips and the "Sent" notification open the native note screen.
- Speaker colours port the web palette rule so a person has the same colour in both apps.
- Markdown is limited to `**bold**` and `- ` bullets, as the server produces.
- Skipped: the web's pink theme (the app has its own), service worker and offline cache.
