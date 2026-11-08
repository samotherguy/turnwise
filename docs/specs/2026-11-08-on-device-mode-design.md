# On-device mode: design

A second way to make a note: everything on the phone, nothing leaving it. The server path is unchanged.

## Notes

- One list, newest first. Phone notes carry an "On phone" badge; chips filter All / Phone / Box.
- A phone note is a folder under the app's files dir in the server's own format (`meta.json`, `transcript.json`, the
  audio), plus `meta.origin = "phone"`. One note screen, one player, one speaker-rename flow and one Ask screen render
  both kinds through `origin`.
- Box off: phone notes always show; server notes come from the cached last list, greyed, with the Wake banner.
- Redo on the box: a phone note's screen offers it. The audio uploads through the normal path (wakes the box); when
  the server note exists the local folder is deleted and the server note opens. Speaker names given on the phone go
  along as hints. No meeting ever exists twice.

## Where a recording goes

- Settings → Transcribe: "On the box" (default) or "On this phone". Send buttons say which.
- Box off and setting = box: the banner offers Wake, or "Do it on this phone this once".
- Phone processing is a foreground job with a notification: Speakers → Transcribing (minutes done) → Summary → Note
  ready. One GPU model at a time (ASR 1.7B Q8 and LLM 2B Q8 do not fit together).

## Pipeline on the phone

1. Decode the clip to 16 kHz mono PCM (MediaCodec).
2. Speakers: pyannote segmentation 3.0 + a speaker embedding model through sherpa-onnx; turns merged like the server
   (gap 1 s, max 30 s). Each turn is transcribed on its own so a segment has one speaker.
3. ASR: Qwen3-ASR 1.7B Q8 through the bundled llama-server, one turn per request; the live guard (token cap,
   repetition penalty, loop collapse) applies.
4. Script: Devanagari → typed Hinglish by `Hinglish.kt`. No English column, no denoise (stated on the note).
5. Summary: Qwen3.5 2B Q8, the server's SUMMARY_PROMPT in 20-minute chunks then a merge, title + action items.
6. Ask: the 2B over the note; long notes are trimmed to the segments that best match the question (~6k tokens).
7. Speaker memory on the phone (`speakers.json`, its own embedding space); naming a speaker teaches it.

Measured on the S24 Ultra: ASR ≈ 20 min per hour of audio; summary ≈ 2 min per 20-minute chunk; Ask 23–48 s;
phone under 60 °C. Q4_K_M files are killed for memory on the OpenCL backend: Q8_0 or Q4_0 only.

## Models

Settings → On-device models: ASR (2.2 GB), projector (0.36 GB), LLM (2.0 GB), speaker models (~32 MB); download,
size, delete. Source is `models.url` (default: the box's `/models/`), so the Play build needs no box.

## Flavours

`play` has no accessibility service, a first run that does not ask for a server, and works with phone notes only.
