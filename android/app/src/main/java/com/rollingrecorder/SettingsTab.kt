package com.rollingrecorder

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private class ServerState(val settings: JSONObject, val voices: Map<String, Int>)

@Composable
fun SettingsTab(padding: PaddingValues) {
    val scope = rememberCoroutineScope()
    var reconnect by remember { mutableIntStateOf(0) }
    val polled = rememberPolled("server|$reconnect", cacheKey = "server") { ServerState(Api.settings(), Api.speakers()) }
    var forgetting by remember { mutableStateOf<String?>(null) }
    var saveError by remember { mutableStateOf<String?>(null) }
    val st = polled.value
    val s = st?.settings

    fun put(key: String, value: Any) {
        scope.launch {
            try {
                val fresh = Api.putSettings(JSONObject().put(key, value))
                polled.value = ServerState(fresh, polled.value?.voices ?: emptyMap())
                saveError = null
            } catch (t: Throwable) { saveError = errorText(t) }
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = padding, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { PageTitle("Settings") }
        item { Column { ListenerSettings(onAddressChanged = { reconnect++ }) } }
        item { PowerPanel() }
        if (BuildConfig.FLAVOR != "demo") item { CallsPanel() }
        item { CardputerGuide() }
        polled.error?.let { item { Banner(it, error = true) } }
        saveError?.let { item { Banner("Not saved: $it", error = true) } }
        if (s != null) {
            item {
                Panel {
                    SectionTitle("Speaker detection")
                    val ok = s.optString("diarization") == "ok"
                    Text(if (ok) "On" else "Off", style = MaterialTheme.typography.bodyLarge, color = if (ok) Green else Color(0xFFFF9B9B))
                    Text(
                        if (ok) "pyannote community-1 on the server GPU."
                        else "${s.optString("diarization")}. Accept the model terms at huggingface.co/pyannote/speaker-diarization-community-1, put HF_TOKEN in ~/.config/audio-notes/env and restart the service.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            item {
                Panel {
                    SectionTitle("Known voices")
                    if (st.voices.isEmpty()) Text("None yet. Open a note and tap a speaker's name to name them.", style = MaterialTheme.typography.bodySmall)
                    st.voices.entries.sortedBy { it.key.lowercase() }.forEach { (name, n) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            VoiceChip(name, 36.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(name, style = MaterialTheme.typography.bodyLarge)
                                Text("$n voice sample${if (n == 1) "" else "s"}", style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(onClick = { forgetting = name }) { Text("Forget", color = MaterialTheme.colorScheme.error) }
                        }
                    }
                    Stepper(
                        "Match threshold", "How alike a voice must sound to be recognised",
                        "%.2f".format(s.optDouble("match_threshold", 0.55)),
                        onMinus = { put("match_threshold", (s.optDouble("match_threshold", 0.55) - 0.05).coerceAtLeast(0.3).round2()) },
                        onPlus = { put("match_threshold", (s.optDouble("match_threshold", 0.55) + 0.05).coerceAtMost(0.95).round2()) },
                    )
                }
            }
            item {
                Panel {
                    SectionTitle("Transcription")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Remove background noise", style = MaterialTheme.typography.bodyLarge)
                            Text("Cleans the audio before transcribing", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(
                            s.optBoolean("denoise", true), { put("denoise", it) },
                            colors = SwitchDefaults.colors(checkedTrackColor = Green, checkedThumbColor = Color.Black),
                        )
                    }
                    Stepper(
                        "Noise attenuation", "How hard noise is pushed down", "${s.optInt("atten_db", 30)} dB",
                        onMinus = { put("atten_db", (s.optInt("atten_db", 30) - 5).coerceAtLeast(5)) },
                        onPlus = { put("atten_db", (s.optInt("atten_db", 30) + 5).coerceAtMost(100)) },
                    )
                    Text("Language", style = MaterialTheme.typography.bodyLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("auto" to "Hindi + English", "hi" to "Hindi", "en" to "English").forEach { (k, l) ->
                            Pill(l, s.optString("language", "auto") == k) { put("language", k) }
                        }
                    }
                    Text(
                        "Speech: ${s.optString("asr_backend")} · Hinglish, English and summaries: ${s.optString("llm")} via Ollama",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            item {
                Panel {
                    SectionTitle("Account")
                    val login = s.optString("login")
                    Text(
                        if (login.isEmpty()) "Owner (local)" else s.optString("name").ifEmpty { login },
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        if (s.optBoolean("owner")) "Your own notes, voices and Claude chats." else "Your notes are only visible to you.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }

    forgetting?.let { name ->
        ConfirmDialog("Forget $name's voice?", "Their name is removed from every note so the labels can be redone.", "Forget", true, { forgetting = null }) {
            forgetting = null
            scope.launch {
                try { Api.forgetSpeaker(name); polled.refresh(); Bus.notesVersion++ } catch (t: Throwable) { saveError = errorText(t) }
            }
        }
    }
}

private fun Double.round2() = Math.round(this * 100) / 100.0

/** How the Cardputer recorder fits in, for the Settings page. */
@Composable
private fun CardputerGuide() {
    var open by remember { mutableStateOf(false) }
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionTitle("Cardputer")
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { open = !open }) { Text(if (open) "Hide" else "Guide", color = Green) }
        }
        Text("A pocket recorder that listens all day and keeps the last 10 hours on its card. Use it instead of the phone's mic from the Listen tab.", style = MaterialTheme.typography.bodySmall)
        if (open) {
            listOf(
                "Listen tab" to "Switch the source to Cardputer. The app finds it on the same Wi-Fi (home network or this phone's hotspot \"Wifi\"), shows what it holds, and Send pulls the last 15 min to 2 h and sends it to Turnwise like any recording.",
                "On the device" to "The top bar shows REC while it records, the time, battery and Wi-Fi. Recording never stops, whatever is on screen.",
                "Home menu" to "Arrows and Enter, or a number, open an app. The ` key (top left, esc) always returns home.",
                "1 Recorder" to "Elapsed time and what is on the card. m keeps the last 30 minutes safe from the rolling buffer. r stops and starts listening.",
                "2 Aircon" to "Mitsubishi Electric remote through the infrared LED on the top edge. p power, - and = temperature, m mode, f fan, Enter resends.",
                "3 GPU box" to "Shows whether com and com-2 are on. w wakes com, l wakes com-2, o twice shuts com down. Works through the waker phone at home.",
                "4 Bookmark" to "Type a label and press Enter: it is stamped with the time and keeps that chunk. Useful mid-meeting.",
                "5 Clock" to "A big clock, the date, and how much is buffered.",
                "6 Network" to "Which Wi-Fi it is on and its address. w turns Wi-Fi off to save battery; the app then cannot reach it until you turn it back on.",
                "Charging" to "Expect close to 10 hours on a charge with Wi-Fi on. Charge over USB-C every night; it recovers and resumes recording by itself.",
                "Address" to "Blank means the app looks for it over the network. If it cannot be found, type the address shown in the device's Network app into the field under Listener settings.",
            ).forEach { (k, v) ->
                Text(k, style = MaterialTheme.typography.bodyLarge)
                Text(v, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Wake or shut down the Turnwise box (com) and the LLM box (com-2) through the waker phone. */
/** Experimental call capture: a switch, and the accessibility service it depends on. */
@Composable
private fun CallsPanel() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val settings = remember { Settings(context) }
    var on by remember { mutableStateOf(settings.callCapture) }
    var serviceOn by remember { mutableStateOf(CallCaptureService.enabled(context)) }
    androidx.compose.runtime.LaunchedEffect(Unit) { while (true) { serviceOn = CallCaptureService.enabled(context); kotlinx.coroutines.delay(2_000) } }   // picks up the toggle when you come back from Settings
    Panel {
        SectionTitle("Calls")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Keep recording through calls", style = MaterialTheme.typography.bodyLarge)
                Text("Experimental. Cellular calls may capture both sides; WhatsApp and Teams only your side unless on speaker.", style = MaterialTheme.typography.bodySmall)
            }
            Switch(on, { on = it; settings.callCapture = it }, colors = SwitchDefaults.colors(checkedTrackColor = Green, checkedThumbColor = Color.Black))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (serviceOn) "Accessibility service: on" else "Accessibility service: off (needed for calls)", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = if (serviceOn) Green else MaterialTheme.colorScheme.error)
            TextButton(onClick = { CallCaptureService.openSettings(context) }) { Text("Open", color = Green) }
        }
        if (!serviceOn) Text("Android 13+: if the toggle is greyed out, go to App info → ⋮ → Allow restricted settings first. Check your local law on recording calls.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun PowerPanel() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val settings = remember { Settings(context) }
    val scope = rememberCoroutineScope()
    var wakerUrl by remember { mutableStateOf(settings.wakerUrl) }
    var msg by remember { mutableStateOf<String?>(null) }
    var confirmOff by remember { mutableStateOf<Boolean?>(null) }   // false = com, true = com-2
    val polled = rememberPolled("waker|$wakerUrl", cacheKey = "waker", every = { 5_000L }) { withContext(Dispatchers.IO) { Waker.status() } }
    val st = polled.value

    fun run(label: String, action: () -> String?) {
        scope.launch {
            msg = label
            msg = try { withContext(Dispatchers.IO) { action() } } catch (t: Throwable) { errorText(t) }
            polled.refresh()
        }
    }

    @Composable
    fun Machine(name: String, role: String, up: Boolean?, llm: Boolean) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).background(when (up) { true -> Green; false -> Color(0xFFFF6B6B); null -> Mist }, CircleShape))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.bodyLarge)
                Text(when (up) { true -> "Online · $role"; false -> "Off · $role"; null -> role }, style = MaterialTheme.typography.bodySmall)
            }
            TextButton(enabled = up == false, onClick = { run("Waking $name…") { if (llm) { Waker.wake(true); "Wake sent to $name." } else if (Waker.wakeAndWait()) "$name is up." else "$name did not come up. Try again." } }) { Text("Wake", color = if (up == false) Green else Mist) }
            TextButton(enabled = up == true, onClick = { confirmOff = llm }) { Text("Off", color = if (up == true) MaterialTheme.colorScheme.error else Mist) }
        }
    }

    Panel {
        SectionTitle("Power")
        Machine("com", "Turnwise server", st?.server, llm = false)
        Machine("com-2", "LLM box", st?.llm, llm = true)
        polled.error?.let { Text("Waker: $it", style = MaterialTheme.typography.bodySmall, color = Color(0xFFFF9B9B)) }
        msg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        OutlinedTextField(
            value = wakerUrl,
            onValueChange = { wakerUrl = it; settings.wakerUrl = it },
            label = { Text("Waker address") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text("The always-on phone that wakes the server. When Turnwise does not answer, the app asks it to wake the box and waits.", style = MaterialTheme.typography.bodySmall)
    }

    confirmOff?.let { llm ->
        val name = if (llm) "com-2" else "com"
        ConfirmDialog("Shut down $name?", "It powers off in a few seconds. Nothing wakes it again until you tap Wake or send a recording.", "Shut down", true, { confirmOff = null }) {
            confirmOff = null
            run("Shutting down $name…") { Waker.off(llm)?.let { "Could not shut down $name: $it" } ?: "$name is shutting down." }
        }
    }
}

@Composable
private fun Stepper(label: String, sub: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(sub, style = MaterialTheme.typography.bodySmall)
        }
        Box(Modifier.size(40.dp).background(Raised, CircleShape), contentAlignment = Alignment.Center) {
            IconButton(onClick = onMinus) { Icon(Icons.Rounded.Remove, "Less", tint = Color.White) }
        }
        Text(value, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(72.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Box(Modifier.size(40.dp).background(Raised, CircleShape), contentAlignment = Alignment.Center) {
            IconButton(onClick = onPlus) { Icon(Icons.Rounded.Add, "More", tint = Color.White) }
        }
    }
}
