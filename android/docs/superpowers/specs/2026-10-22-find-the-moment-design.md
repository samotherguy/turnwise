# Find the moment: preview player, loudness timeline, send to Turnwise

Approved from a mockup on 2026-10-22. Rolling Recorder is the Turnwise companion app:
it buffers hours of audio, and the user cuts a conversation out of it for transcription.

## Intent
The buffer is mostly silence with a few conversations in it. The user could not say how
they would find the right stretch, so the screen shows where talking happened and lets
them hear the edges before committing.

## What gets built
- **Loudness timeline.** The chunk writer stores one loudness byte per second next to each
  chunk (`<start>_<end>.lvl`). The timeline draws the buffer as bars; tall bars are talking.
  Tapping a talking block selects it. Handles can be dragged, and each edge has +1m/-1m
  nudges. Quick picks (last 5/15/30 min, 1 h, everything) stay.
- **Preview player.** The selection is rendered to a cache file without re-encoding (the
  same join the export uses) and played with MediaPlayer. Buttons: Play/Pause, Start, Last
  15 s, plus a seek bar. The engine is flushed only when the selection reaches the live chunk.
- **Send to Turnwise.** Saves the clip locally as before, then POSTs it to
  `<base>/api/upload?filename=...&created=<seconds>` and remembers the returned note id.
  A sent clip shows an "In Turnwise" tag and opens `<base>/#/note/<id>`. The base URL is a
  setting, defaulting to the user's Tailscale Serve address on port 8446.
- **UI.** Material icons, a pulsing mic while listening, animated show/hide of the player.

## Non-goals
Waveform drawing, re-encoding, speaker detection, uploading without saving locally.

## Calls made by the builder
- Nudge buttons are per edge (start -1m/+1m, end -1m/+1m) so it is clear which moves.
- Block detection threshold is relative to the buffer's loud percentile with a floor; marked
  as a calibration knob.
- Levels of the chunk being written are published live so the timeline reaches "now".

## Added 2026-10-22 (same day): home-screen widget
A 1x1 widget with one tap target: Start when stopped, Stop when listening, showing the
current state. Starting from the widget uses a foreground-service PendingIntent; if Android
refuses the microphone start from a widget, fall back to a trampoline activity.
