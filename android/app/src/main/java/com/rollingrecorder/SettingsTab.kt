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
import kotlinx.coroutines.launch
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
