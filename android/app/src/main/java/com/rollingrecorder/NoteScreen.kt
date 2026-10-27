package com.rollingrecorder

import android.net.Uri
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private val MODES = listOf("hinglish" to "Hinglish", "english" to "English", "text" to "Original")

/** What a turn says in the chosen mode, and the English line shown under Hinglish when it differs. */
fun turnLines(s: Segment, mode: String): Pair<String, String?> = when (mode) {
    "english" -> (s.english ?: s.text) to null
    "text" -> s.text to null
    else -> {
        val main = s.hinglish ?: s.text
        main to s.english?.takeIf { it.isNotBlank() && it != main }
    }
}

/** Loads the note's audio into the shared player (once) and plays from [sec]. */
suspend fun playNote(player: PreviewPlayer, n: Note, sec: Double) {
    if (player.noteId != n.id) player.load(null, Uri.parse(Api.audioUrl(n.id)), n.title, n)
    player.playFrom((sec * 1000).toLong())
}

@Composable
fun NoteScreen(
    id: String, atSec: Double?, player: PreviewPlayer, snackbar: SnackbarHostState, padding: PaddingValues,
    back: () -> Unit, openChats: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val polled = rememberPolled("note:$id", every = { n -> if (n.status.state !in setOf("done", "error")) 3_000L else null }) {
        Api.note(id)
    }
    var mode by rememberSaveable { mutableStateOf("hinglish") }
    var dialog by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    var loadingAudio by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val n = polled.value

    fun play(note: Note, sec: Double) {
        scope.launch {
            loadingAudio = true
            try { playNote(player, note, sec) } catch (t: Throwable) { snackbar.showSnackbar("Could not play: ${errorText(t)}") }
            loadingAudio = false
        }
    }
    fun patch(body: JSONObject, done: String? = null) {
        scope.launch {
            try {
                polled.value = Api.patchNote(id, body).also { PollCache["note:$id"] = it }
                Bus.notesVersion++
                done?.let { snackbar.showSnackbar(it) }
            } catch (t: Throwable) { snackbar.showSnackbar(errorText(t)) }
        }
    }

    // Opened from a chat source: start playing there once the note is in.
    var sought by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(n != null) {
        if (n != null && atSec != null && !sought) { sought = true; play(n, atSec) }
    }

    val header = 1   // items before the turns in the list below
    val playingHere = n != null && player.noteId == n.id
    val playingIdx = if (playingHere) player.turnIndex() else -1
    // Follow the playing turn, but only while the user is following (the previous turn was on screen).
    var lastIdx by remember { mutableIntStateOf(-1) }
    LaunchedEffect(playingIdx) {
        if (playingIdx >= 0 && player.playing) {
            val visible = listState.layoutInfo.visibleItemsInfo.map { it.index }
            val prevShown = lastIdx < 0 || (lastIdx + header) in visible
            if (prevShown && (playingIdx + header) !in visible.dropLast(1)) listState.animateScrollToItem(playingIdx + header, -300)
        }
        lastIdx = playingIdx
    }

    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = padding, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                TopBar(back, "Notes") {
                    if (n?.status?.state == "done") IconButton(onClick = { openChats(id) }) { Icon(Icons.AutoMirrored.Rounded.Chat, "Ask about this recording", tint = Color.White) }
                    Box {
                        IconButton(onClick = { menu = true }, enabled = n != null) { Icon(Icons.Rounded.MoreVert, "More actions", tint = Color.White) }
                        DropdownMenu(menu, { menu = false }, containerColor = Raised) {
                            listOf(
                                "ask" to "Ask about this recording", "rename" to "Rename", "folder" to "Move to folder…",
                                "copy" to "Copy markdown", "retry" to "Re-transcribe", "delete" to "Delete",
                            ).forEach { (k, l) ->
                                DropdownMenuItem(
                                    text = { Text(l, color = if (k == "delete") MaterialTheme.colorScheme.error else Color.White) },
                                    onClick = {
                                        menu = false
                                        when (k) {
                                            "ask" -> openChats(id)
                                            "copy" -> scope.launch {
                                                try { clipboard.setText(AnnotatedString(Api.markdown(id))); snackbar.showSnackbar("Markdown copied") }
                                                catch (t: Throwable) { snackbar.showSnackbar(errorText(t)) }
                                            }
                                            else -> dialog = k
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
                if (n == null) {
                    polled.error?.let { Banner(it, error = true) } ?: Text("Loading…", style = MaterialTheme.typography.bodySmall)
                    return@Column
                }
                Column {
                    Text(n.title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.clickable { dialog = "rename" })
                    Text(
                        listOfNotNull(dateTimeLabel(n.created), durLabel(n.duration).ifEmpty { null }, n.folder.ifEmpty { null }, n.backend)
                            .joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                polled.error?.let { Banner(it, error = true) }
                if (n.status.state !in setOf("done", "error")) Progress(n.status)
                if (n.status.state == "error") Banner(n.status.detail.ifEmpty { "Transcription failed." }, error = true)
                n.warning?.let { Banner(it) }
                if (n.status.state != "recording") NotePlayer(n, player, loadingAudio) { play(n, 0.0) }
                if (n.summary.isNotBlank()) Panel { SectionTitle("Summary"); MdText(n.summary) }
                if (n.actionItems.isNotEmpty()) Panel {
                    SectionTitle("Action items")
                    n.actionItems.forEach { a ->
                        Row {
                            Text("•", color = Green, modifier = Modifier.width(16.dp))
                            Text(
                                androidx.compose.ui.text.buildAnnotatedString {
                                    a.owner?.let { pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold)); append("$it: "); pop() }
                                    append(a.item)
                                },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
                if (n.segments.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MODES.forEach { (k, l) -> Pill(l, mode == k) { mode = k } }
                }
            }
        }
        if (n != null) itemsIndexed(n.segments, key = { i, _ -> i }) { i, s ->
            Turn(
                s, n.speakerName(s.speaker), mode, playing = i == playingIdx,
                onPlay = { play(n, s.start) },
                onRename = { dialog = "spk:${s.speaker}" },
            )
        }
    }

    val note = n ?: return
    when (val d = dialog) {
        "rename" -> PromptDialog("Title", note.title, { dialog = null }, { t ->
            dialog = null
            if (t.isNotBlank() && t != note.title) patch(JSONObject().put("title", t.trim()))
        })
        "folder" -> FolderDialog(note.folder, { dialog = null }) { f ->
            dialog = null
            patch(JSONObject().put("folder", f), if (f.isEmpty()) "Removed from folder" else "Moved to $f")
        }
        "retry" -> ConfirmDialog("Re-transcribe?", "The transcript, summary and speaker labels will be regenerated.", "Re-transcribe", false, { dialog = null }) {
            dialog = null
            scope.launch {
                try { Api.retranscribe(id); polled.refresh(); Bus.notesVersion++ } catch (t: Throwable) { snackbar.showSnackbar(errorText(t)) }
            }
        }
        "delete" -> ConfirmDialog("Delete \"${note.title}\"?", "The recording and transcript will be removed. This cannot be undone.", "Delete", true, { dialog = null }) {
            dialog = null
            scope.launch {
                try {
                    if (player.noteId == id) player.unload()
                    Api.deleteNote(id); Bus.notesVersion++; back()
                } catch (t: Throwable) { snackbar.showSnackbar(errorText(t)) }
            }
        }
        null -> {}
        else -> if (d.startsWith("spk:")) {
            val key = d.removePrefix("spk:")
            PromptDialog(
                "Name ${note.speakerName(key)}", note.speakers[key] ?: "", { dialog = null },
                { name ->
                    dialog = null
                    patch(JSONObject().put("speakers", JSONObject().put(key, name.trim())))
                    if (name.isNotBlank() && name.trim() !in Voices.palette) Voices.palette = Voices.palette + name.trim()
                },
                placeholder = "Name", message = "This voice will be recognised in future recordings.",
            )
        }
    }
}

@Composable
private fun Progress(st: NoteStatus) {
    val now by produceState(System.currentTimeMillis() / 1000.0) { while (true) { value = System.currentTimeMillis() / 1000.0; delay(1_000) } }
    val cur = if (st.state == "convert") 0 else STEPS.indexOf(st.state)
    if (cur < 0) { Banner("${STATE_LABELS[st.state] ?: st.state}…"); return }
    Panel(gap = 8.dp) {
        Text(
            "Step ${cur + 1} of ${STEPS.size}" + (st.started?.let { " · ${elapsedLabel(now - it)} elapsed" } ?: ""),
            style = MaterialTheme.typography.titleMedium,
        )
        STEPS.forEachIndexed { i, k ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) {
                    when {
                        i < cur -> Text("✓", color = Green)
                        i == cur -> CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = Green)
                        else -> Text("○", color = Mist)
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    (STATE_LABELS[k] ?: k) + if (i == cur && st.detail.isNotEmpty()) " · ${st.detail}" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (i > cur) Mist else Color.White,
                )
            }
        }
    }
}

/** Big play button until this note is loaded, then a full transport with turn skips. */
@Composable
private fun NotePlayer(n: Note, player: PreviewPlayer, loading: Boolean, onPlay: () -> Unit) {
    val here = player.noteId == n.id
    Surface(shape = RoundedCornerShape(20.dp), color = Raised, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            if (here) {
                Slider(
                    value = if (player.durationMs > 0) player.positionMs.toFloat() / player.durationMs else 0f,
                    onValueChange = { player.seek((it * player.durationMs).toLong()) },
                    colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Green, inactiveTrackColor = Color.White.copy(alpha = 0.2f)),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(fmtClock(player.positionMs), style = MaterialTheme.typography.labelSmall, color = Mist)
                    Text(fmtClock(player.durationMs), style = MaterialTheme.typography.labelSmall, color = Mist)
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                if (here && n.segments.isNotEmpty()) IconButton(onClick = { player.stepTurn(-1) }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Rounded.SkipPrevious, "Previous turn", tint = Color.White, modifier = Modifier.size(32.dp))
                }
                Spacer(Modifier.width(24.dp))
                Box(
                    Modifier.size(64.dp).background(Green, CircleShape).clickable(enabled = !loading) { if (here) player.toggle() else onPlay() },
                    contentAlignment = Alignment.Center,
                ) {
                    if (loading) CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp, color = Color.Black)
                    else Icon(
                        if (here && player.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        if (here && player.playing) "Pause" else "Play recording", tint = Color.Black, modifier = Modifier.size(36.dp),
                    )
                }
                Spacer(Modifier.width(24.dp))
                if (here && n.segments.isNotEmpty()) IconButton(onClick = { player.stepTurn(1) }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Rounded.SkipNext, "Next turn", tint = Color.White, modifier = Modifier.size(32.dp))
                }
            }
            if (!here) Text(
                "Play recording" + (n.duration?.let { " · ${durLabel(it)}" } ?: ""),
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun Turn(s: Segment, who: String, mode: String, playing: Boolean, onPlay: () -> Unit, onRename: () -> Unit) {
    val bg by animateColorAsState(if (playing) Color(0xFF1B3A28) else Color.Transparent, label = "turn")
    val (main, sub) = turnLines(s, mode)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(bg).clickable(onClick = onPlay).padding(12.dp),
    ) {
        VoiceChip(who)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    who, style = MaterialTheme.typography.labelLarge, color = Voices.color(who),
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onRename).padding(vertical = 4.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(tsLabel(s.start), style = MaterialTheme.typography.labelSmall, color = Mist)
            }
            Text(main, style = MaterialTheme.typography.bodyMedium, color = if (playing) Color.White else Color.White.copy(alpha = 0.9f))
            sub?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }
        }
    }
}
