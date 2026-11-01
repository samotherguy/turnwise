# Rolling Recorder

An Android app that keeps listening in the background and holds the last few hours of audio in a rolling buffer. Open the app at any time and save the last 5 minutes, the last 2 hours, or any stretch in between. Saved clips stay on the phone permanently, ready to hand to Turnwise for transcription.

## Installing

Option 1: Open this folder in Android Studio, let it sync, and press Run with your phone connected.

Option 2: Push the folder to GitHub. The workflow in `.github/workflows/build.yml` builds the app and attaches `app-debug.apk` as a downloadable artifact under the Actions tab. Copy it to the phone and open it. Android will ask you to allow installs from unknown sources.

On first launch, grant the microphone and notification permissions, then tap "Allow unrestricted battery use". The second step matters: without it, some phone makers (Samsung, Xiaomi, Oppo and others) will stop the app after a while.

## How it works

Audio is recorded at 16 kHz mono and compressed to Advanced Audio Coding (AAC) at 48 kilobits per second. It is written in 30-second chunks into the app's private storage. Chunks older than the rolling window are deleted. At 48 kilobits per second, 2 hours takes about 43 MB and 10 hours about 216 MB.

Saving a clip joins the chunks that overlap your range and trims the first and last one at about 60 millisecond precision, without re-encoding. The result goes to `Recordings/RollingRecorder` (`Music/RollingRecorder` before Android 12), so it shows up in the Files app. Saving runs beside the recorder and never pauses it.

## Calls and other apps using the microphone

Android lets one app at a time have the microphone during a phone, WhatsApp or Teams call. Instead of an error, it silences the stream going to this app. The app detects that in two ways (Android's own silenced flag, and a full second of digital silence), stops writing, shows "Paused" in the app and in the notification, and starts writing again as soon as audio returns. Nothing needs tapping. The conversation you had on the call itself is not captured, because the phone does not let a third app hear it.

## Start and stop

Tap Start listening to switch it on and Stop listening to switch it off. The choice is remembered. If you stop it, it stays stopped, including after a reboot. If it is on, it restarts after Android kills it.

## Limits to know about

Android 14 and later do not let an app start a microphone service from the background. So after a reboot or an app update, the app cannot restart silently. It posts a notification, and one tap on it resumes listening. The same applies in the rare case where Android kills the service and refuses to restart it.

A crash or force stop loses at most the 30-second chunk being written.

If you shorten the rolling window, audio older than the new window is deleted straight away.

The microphone is in use all the time, so Android shows its microphone indicator dot permanently, and the battery cost is real (an always-on wake lock keeps encoding running with the screen off). Expect noticeably higher drain than a normal app.

Recording people without their knowledge is restricted in many places. Check the rules where you are.
