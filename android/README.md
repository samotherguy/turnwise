# Turnwise for Android

The phone side of [Turnwise](../README.md): an app that listens all the time into a rolling buffer, sends any stretch of it to the box for a transcript, and can make the note on the phone itself when the box is out of reach. What each screen does, how the pieces fit, and the measurements behind the choices are in the main README under [The Android app](../README.md#the-android-app); this file is the build and install sheet.

## Build

JDK 17 and the Android SDK; the Gradle wrapper fetches the rest. Put your addresses in `local.properties` first (`turnwise.url=https://<box>.<tailnet>.ts.net:8446`, `waker.url=https://waker.<tailnet>.ts.net:8491`, optionally `turnwise.demo.url`); the repo ships placeholders.

```bash
./gradlew assembleFullDebug testFullDebugUnitTest
adb install -r app/build/outputs/apk/full/debug/app-full-debug.apk
```

Three flavours build from the same code:

| Flavour | Task | What it is |
| --- | --- | --- |
| `full` | `assembleFullDebug` | Everything, including call capture through an accessibility service. Play Protect refuses to sideload an APK that declares one, so it goes on with `adb install`; after every reinstall the service has to be switched back on in Settings → Accessibility. |
| `play` | `assemblePlayDebug` | The same app without that service: what a store build would start from. Installs from a file. |
| `demo` | `assembleDemoDebug` | "Turnwise Demo", its own package beside the real app, pointed at the server's demo account so no real meeting is on screen. |

The on-device mode needs two native pieces that are checked in: `app/libs/sherpa-onnx-2.0.1.aar` (speaker segmentation and embeddings) and `app/src/main/jniLibs/arm64-v8a/libllamaserver.so`, llama.cpp's server built for Android with OpenCL (`~/.local/opt/build-llama-android.sh` in the author's setup: NDK 25, `-DGGML_OPENCL=ON -DGGML_OPENMP=OFF`, 16 KB page alignment). The app is arm64 only. The model files themselves are not in the repo; the app downloads them from the box's `static/models/` or any URL set in Settings.

## First run

Grant the microphone and notification permissions and allow unrestricted battery use when asked; without the last one Samsung, Xiaomi and others stop the app after a while. Then Settings → Server → your Tailscale Serve address (the phone must be on the tailnet) and Listen → Start. For the widget, long-press the home screen and add "Turnwise".

## What to know

- **Starting after a reboot or update.** Android does not let a microphone service start itself from the background, so the app posts a notification and one tap resumes listening.
- **Calls.** Without call capture, a phone, WhatsApp or Teams call silences the microphone stream and the app shows "Paused" until it returns. With the `full` flavour and the accessibility service on, Samsung phones deliver both sides of the call, at the ear too, and the recording ends with the call.
- **Memory.** The phone pipeline runs as a visible job with checkpoints. Samsung kills apps under memory pressure during calls, so the job waits while a call is active, resumes where it was after a kill, and gives up with a message after four attempts.
- **Storage.** 48 kbps AAC in 30-second chunks: about 21 MB an hour of buffer. Clips go to `Recordings/RollingRecorder` (`Music/RollingRecorder` before Android 12). The package is still `com.rollingrecorder` from the app's first life; renaming it would wipe an installed copy's buffer and settings.
- **Battery.** The microphone is held all the time, so the indicator dot is permanent and the drain is real.
- Recording people without their knowledge is restricted in many places. Check the rules where you are.

## Tests

`./gradlew testFullDebugUnitTest`: note parsing, loudness blocks, markdown and speaker colours checked against the web app, the Hinglish transliteration rules, and the loop collapsing used on live lines.
