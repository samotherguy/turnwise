package com.rollingrecorder

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun NotesTab(snackbar: SnackbarHostState, padding: PaddingValues, uploads: Uploads, openNote: (String) -> Unit, upload: (List<android.net.Uri>, String) -> Unit) {
    val context = LocalContext.current
    val settings = remember { Settings(context) }
    var q by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    var view by rememberSaveable { mutableStateOf(settings.notesView) }
    LaunchedEffect(q) { delay(250); query = q.trim() }

    val notes = rememberPolled("notes|$query|${Bus.notesVersion}", cacheKey = "notes|$query", every = { list -> if (list.any { it.status.busy }) 3_000L else null }) {
        (if (query.isEmpty()) Api.notes() else Api.search(query)).also { if (query.isEmpty()) Voices.learn(it) }
    }
    val server = rememberPolled("server-settings") { Api.settings() }

    // Picked files wait here until the user says where they go.
    var picked by remember { mutableStateOf<List<android.net.Uri>?>(null) }
    var choosingFolder by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { if (it.isNotEmpty()) picked = it }

    val listState = rememberLazyListState()
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(), state = listState,
            contentPadding = PaddingValues(
                start = padding.calculateLeftPadding(androidx.compose.ui.unit.LayoutDirection.Ltr),
                end = padding.calculateRightPadding(androidx.compose.ui.unit.LayoutDirection.Ltr),
                top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 80.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Brand() }
            val diar = server.value?.optString("diarization")
            if (diar != null && diar != "ok") item { Banner("Speaker detection is off: $diar. See Settings.") }
            item { RecordPanel(snackbar, openNote) }
            if (uploads.label != null) item { UploadPanel(uploads) }
            item {
                TextField(
                    q, { q = it },
                    placeholder = { Text("Search transcripts") },
                    leadingIcon = { Icon(Icons.Rounded.Search, null, tint = Mist) },
                    trailingIcon = { if (q.isNotEmpty()) IconButton(onClick = { q = "" }) { Icon(Icons.Rounded.Close, "Clear search", tint = Mist) } },
                    singleLine = true, shape = CircleShape,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Raised, unfocusedContainerColor = Raised,
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (query.isEmpty()) item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("date" to "By date", "folder" to "By folder").forEach { (v, l) ->
                        Pill(l, view == v) { view = v; settings.notesView = v }
                    }
                }
            }
            notes.error?.let { item { Banner(it, error = true) } }
            val list = notes.value
            when {
                list == null -> item { Text("Loading…", style = MaterialTheme.typography.bodySmall) }
                list.isEmpty() -> item {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(top = 24.dp)) {
                        Duo(if (query.isNotEmpty()) Mood.THINK else Mood.SLEEP, Modifier.width(200.dp))
                        Spacer(Modifier.height(16.dp))
                        Text(
                            if (query.isNotEmpty()) "No matches." else "No notes yet. Record a meeting, upload a file, or send a moment from Listen.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                query.isNotEmpty() -> items(list, key = { it.id }) { NoteRow(it, showDate = true, showFolder = true) { openNote(it.id) } }
                else -> {
                    val groups = list.groupBy { if (view == "folder") it.folder else dayLabel(it.created) }
                    val keys = if (view == "folder") groups.keys.sortedWith(compareBy({ it.isEmpty() }, { it.lowercase() })) else groups.keys.toList()
                    keys.forEach { k ->
                        val ns = groups.getValue(k)
                        item(key = "h-$k") {
                            Text(
                                "${if (view == "folder" && k.isEmpty()) "No folder" else k} · ${ns.size}",
                                style = MaterialTheme.typography.labelSmall, color = Mist,
                                modifier = Modifier.padding(top = 12.dp, start = 4.dp),
                            )
                        }
                        items(ns, key = { it.id }) { NoteRow(it, showDate = view == "folder", showFolder = view != "folder") { openNote(it.id) } }
                    }
                }
            }
        }

        Row(
            Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = padding.calculateBottomPadding() - 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ExtendedFloatingActionButton(
                onClick = { picker.launch(arrayOf("audio/*", "video/*")) },
                containerColor = Raised, contentColor = Color.White,
                icon = { Icon(Icons.Rounded.Upload, null) }, text = { Text("Upload") },
            )
            RecordButton(snackbar)
        }
    }

    picked?.let { uris ->
        if (choosingFolder) {
            FolderDialog("", onDismiss = { choosingFolder = false; picked = null }) { f -> choosingFolder = false; picked = null; upload(uris, f) }
        } else {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { picked = null }, containerColor = Raised,
                title = { Text(if (uris.size == 1) "Upload 1 file" else "Upload ${uris.size} files", style = MaterialTheme.typography.titleMedium) },
                text = { Text("Titles, summaries and action items are generated from the content.", style = MaterialTheme.typography.bodySmall) },
                confirmButton = { androidx.compose.material3.TextButton(onClick = { picked = null; upload(uris, "") }) { Text("Upload", color = Green) } },
                dismissButton = { androidx.compose.material3.TextButton(onClick = { choosingFolder = true }) { Text("Into a folder…", color = Mist) } },
            )
        }
    }
}

@Composable
private fun Brand() {
    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp)) {
        Box(Modifier.size(36.dp).background(Brush.linearGradient(listOf(Color(0xFF2EE37A), Color(0xFF0E8F3C))), CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.GraphicEq, null, tint = Color.Black, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text("Turnwise", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.weight(1f))
        Duo(Mood.CHAT, Modifier.width(150.dp), celebrate = Bus.notesVersion)
    }
}

@Composable
fun NoteRow(n: NoteSummary, showDate: Boolean, showFolder: Boolean, onClick: () -> Unit) {
    val st = n.status.state
    val grad = TILE_GRADIENTS[(n.id.hashCode() and 0x7fffffff) % TILE_GRADIENTS.size]
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)).background(Brush.linearGradient(grad)), contentAlignment = Alignment.Center) {
            when {
                st == "error" -> Icon(Icons.Rounded.ErrorOutline, null, tint = Color.White)
                n.status.busy -> CircularProgressIndicator(Modifier.size(24.dp), color = Color.White, strokeWidth = 2.dp)
                else -> Icon(Icons.Rounded.GraphicEq, null, tint = Color.White)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(n.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val sub = listOfNotNull(
                if (showDate) dateTimeLabel(n.created) else timeLabel(n.created),
                durLabel(n.duration).ifEmpty { null },
                n.folder.takeIf { showFolder && it.isNotEmpty() },
                n.speakers.takeIf { it.isNotEmpty() }?.joinToString(", "),
            ).joinToString(" · ")
            Text(sub, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            n.snippet?.let { Text(it.replace("**", "").lineSequence().first(), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.8f), maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        if (st != "done") {
            Spacer(Modifier.width(8.dp))
            Surface(shape = CircleShape, color = if (st == "error") Color(0xFF3A1A1A) else Color(0xFF1B3A28)) {
                Text(
                    STATE_LABELS[st] ?: st, style = MaterialTheme.typography.labelSmall,
                    color = if (st == "error") Color(0xFFFF9B9B) else Green,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun UploadPanel(up: Uploads) {
    Panel(gap = 8.dp) {
        Text(
            if (up.total > 1) "Uploading ${up.index + 1} of ${up.total}" else "Uploading",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(up.label ?: "", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        LinearProgressIndicator(progress = { up.progress }, color = Green, trackColor = Raised, modifier = Modifier.fillMaxWidth())
    }
}

// ---------- record: a meeting is a marked stretch of the rolling buffer ----------

@Composable
private fun RecordButton(snackbar: SnackbarHostState) {
    val context = LocalContext.current
    val settings = remember { Settings(context) }
    val start = rememberStartRecording(snackbar)
    val status by RecorderState.status.collectAsState()
    var mark by remember { mutableLongStateOf(settings.recordMark) }
    LaunchedEffect(Unit) { while (true) { mark = settings.recordMark; delay(500) } }
    if (mark > 0) return
    ExtendedFloatingActionButton(
        onClick = {
            if (status == Status.STOPPED) start()
            settings.recordMark = System.currentTimeMillis()
            mark = settings.recordMark
        },
        containerColor = Green, contentColor = Color.Black,
        icon = { Icon(Icons.Rounded.Mic, null) }, text = { Text("Record") },
    )
}

@Composable
private fun RecordPanel(snackbar: SnackbarHostState, openNote: (String) -> Unit) {
    val context = LocalContext.current
    val settings = remember { Settings(context) }
    val scope = rememberCoroutineScope()
    val status by RecorderState.status.collectAsState()
    var mark by remember { mutableLongStateOf(settings.recordMark) }
    LaunchedEffect(Unit) { while (true) { mark = settings.recordMark; delay(500) } }
    val now by produceState(System.currentTimeMillis()) { while (true) { value = System.currentTimeMillis(); delay(500) } }
    var naming by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val pulse = rememberInfiniteTransition(label = "rec")
    val dot by pulse.animateFloat(0.8f, 1.2f, infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "dot")

    AnimatedVisibility(mark > 0, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.FiberManualRecord, null, tint = Color(0xFFFF4D4D), modifier = Modifier.size(20.dp).scale(dot))
                Spacer(Modifier.width(8.dp))
                Text("Recording a meeting", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(fmtClock((now - mark).coerceAtLeast(0)), style = MaterialTheme.typography.headlineMedium)
            }
            Text(
                if (status == Status.STOPPED) "Listening is off, so nothing new is being kept. Start it on the Listen tab."
                else "Taken from the rolling buffer, so you can leave this screen or lock the phone.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { settings.recordMark = 0; mark = 0 }, enabled = !saving, modifier = Modifier.weight(1f).height(48.dp)) { Text("Discard") }
                Button(
                    onClick = { naming = true }, enabled = !saving, modifier = Modifier.weight(1f).height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Green, contentColor = Color.Black),
                ) {
                    if (saving) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.Black)
                    else Icon(Icons.Rounded.Stop, null)
                    Spacer(Modifier.width(8.dp)); Text("Stop")
                }
            }
        }
    }

    if (naming) PromptDialog(
        "Transcribe the meeting", "", onDismiss = { naming = false }, placeholder = "Title (optional)",
        message = "Leave it empty and a title is generated from what was said.", confirm = "Transcribe",
        onDone = { title ->
            naming = false
            saving = true
            val from = mark
            scope.launch {
                try {
                    val to = System.currentTimeMillis()
                    val store = ChunkStore(context)
                    val r = withContext(Dispatchers.IO) { ClipExporter.export(context, store, from, to) }
                    val up = withContext(Dispatchers.IO) { Turnwise.upload(context, settings.turnwiseUrl, r.uri, r.name, from, title = title.trim()) }
                    settings.setNoteId(r.id, up.id)
                    settings.recordMark = 0
                    mark = 0
                    Bus.notesVersion++
                    openNote(up.id)
                } catch (t: Throwable) {
                    snackbar.showSnackbar("Could not send the recording: ${t.message}. It is still marked; try Stop again.")
                }
                saving = false
            }
        },
    )
}
