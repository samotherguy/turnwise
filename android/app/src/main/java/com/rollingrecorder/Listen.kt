package com.rollingrecorder

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

private val WINDOW_CHOICES = listOf(1, 2, 4, 6, 10)
private const val BUCKETS = 240
private val QUICK_PICKS = listOf(5L to "Last 5 min", 15L to "15 min", 30L to "30 min", 60L to "1 h")

/** Listen-tab state that has to survive switching tabs. The preview render lives in the app shell. */
class ListenUi {
    var sel by mutableStateOf<LongRange?>(null)
    var pick by mutableStateOf<Long?>(null)       // quick-pick minutes, or -1 for everything
    var zoomFrom by mutableStateOf<Long?>(null)   // wave view start; null = whole buffer
    var rendering by mutableStateOf(false)
    var renderSeq = 0
}

/** Renders [sel] of the buffer into a temp file and loads it, a moment after the selection stops changing. */
suspend fun renderPreview(context: Context, ui: ListenUi, player: PreviewPlayer, snackbar: SnackbarHostState) {
    val s = ui.sel ?: run { if (player.title == "Selection") player.unload(); return }
    delay(500)
    ui.rendering = true
    val file = File(context.cacheDir, "preview_${++ui.renderSeq}.m4a")
    try {
        withContext(Dispatchers.IO) {
            ClipExporter.cut(ChunkStore(context), s.first, s.last, file)
            ensureActive()
            player.load(file, null, "Selection")
        }
    } catch (t: Throwable) {
        file.delete()
        if (t !is CancellationException) snackbar.showSnackbar("Could not preview: ${t.message}")
    }
    ui.rendering = false
}

/** Uploads a saved clip, remembers its note and offers to open it in the app. */
suspend fun sendToTurnwise(
    context: Context, snackbar: SnackbarHostState, clipId: Long, uri: Uri, name: String, createdMs: Long, openNote: (String) -> Unit,
) {
    val settings = Settings(context)
    try {
        val r = withContext(Dispatchers.IO) { Turnwise.upload(context, settings.turnwiseUrl, uri, name, createdMs) }
        settings.setNoteId(clipId, r.id)
        if (snackbar.showSnackbar("Sent to Turnwise ✓", actionLabel = "Open") == SnackbarResult.ActionPerformed) openNote(r.id)
    } catch (t: Throwable) {
        snackbar.showSnackbar("Could not send: ${t.message}")
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ListenTab(ui: ListenUi, player: PreviewPlayer, snackbar: SnackbarHostState, padding: PaddingValues, openNote: (String) -> Unit) {
    val context = LocalContext.current
    val settings = remember { Settings(context) }
    val store = remember { ChunkStore(context) }
    val scope = rememberCoroutineScope()

    val status by RecorderState.status.collectAsState()
    val tick by RecorderState.chunkTick.collectAsState()
    val live by RecorderState.live.collectAsState()
    var busy by remember { mutableStateOf(false) }

    val now by produceState(System.currentTimeMillis()) {
        while (true) { value = System.currentTimeMillis(); delay(1_000) }
    }
    // chunkTick fires on every chunk close, export and prune, so no polling is needed.
    val chunks by produceState(emptyList<ChunkStore.Chunk>(), tick) {
        value = withContext(Dispatchers.IO) { store.list().onEach { it.levels } }
    }

    val startRecording = rememberStartRecording(snackbar)

    // Timeline facts.
    val spans = remember(chunks, live) {
        chunks.map { Blocks.Span(it.startMs, it.endMs, it.levels) } +
            listOfNotNull(live?.let { Blocks.Span(it.startMs, it.startMs + it.levels.size * 1000L, it.levels) })
    }
    val oldestStart = spans.minOfOrNull { it.startMs }
    val tlFrom = oldestStart ?: now
    val spanMs = (now - tlFrom).coerceAtLeast(0L)
    val audioMs = chunks.sumOf { it.durationMs } + (live?.levels?.size ?: 0) * 1000L
    val viewFrom = maxOf(tlFrom, ui.zoomFrom ?: tlFrom)
    val buckets = remember(spans, viewFrom, now) { Blocks.buckets(spans, viewFrom, now, BUCKETS) }
    val bucketMs = ((now - viewFrom) / BUCKETS).coerceAtLeast(1L)
    val blocks = remember(buckets) { Blocks.find(buckets, bucketMs) }

    fun select(from: Long, to: Long) {
        val f = from.coerceIn(tlFrom, now - 1_000)
        val t = to.coerceIn(f + 1_000, now)
        ui.sel = f..t
        ui.pick = null
    }

    fun saveSelection(send: Boolean) {
        val s = ui.sel ?: return
        if (busy) return
        busy = true
        scope.launch {
            try {
                val r = withContext(Dispatchers.IO) { ClipExporter.export(context, store, s.first, s.last) }
                if (send) sendToTurnwise(context, snackbar, r.id, r.uri, r.name, s.first, openNote)
                else snackbar.showSnackbar("Saved ${r.name} (${fmtDur(r.durationMs)})")
            } catch (t: Throwable) {
                snackbar.showSnackbar("Could not save: ${t.message}")
            }
            busy = false
        }
    }

    val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    val batteryUnrestricted = power.isIgnoringBatteryOptimizations(context.packageName)
    val s = ui.sel
    val playheadMs = if (s != null && player.ready && player.title == "Selection") s.first + player.positionMs else null

    LazyColumn(Modifier.fillMaxSize(), contentPadding = padding, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Hero(
                status = status,
                subtitle = if (oldestStart == null) "Nothing buffered yet"
                else "Last ${settings.windowHours} h kept · ${fmtDur(audioMs)} since ${clock(oldestStart)}",
                onStart = startRecording,
                onStop = { RecorderService.stop(context) },
            )
        }
        if (!batteryUnrestricted) {
            item {
                Panel {
                    Text("Keep it alive", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Battery optimisation can stop background apps. Allow unrestricted use so listening is never killed.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(onClick = {
                        val i = Intent(
                            android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:${context.packageName}")
                        )
                        try { context.startActivity(i) } catch (_: ActivityNotFoundException) {}
                    }) { Text("Allow unrestricted battery use") }
                }
            }
        }
        item {
            Panel {
                SectionTitle("Find the moment")
                if (oldestStart == null) {
                    Text("Start listening and the last hours will appear here.", style = MaterialTheme.typography.bodySmall)
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        QUICK_PICKS.forEach { (m, label) ->
                            // Zoom to three times the pick so the selection has room on both sides.
                            Pill(label, ui.pick == m, enabled = spanMs > 0) { ui.zoomFrom = now - m * 3 * 60_000L; select(now - m * 60_000L, now); ui.pick = m }
                        }
                        // View-only: selecting 10 h would render a 200 MB preview.
                        Pill("Everything · ${fmtDur(spanMs)}", ui.pick == -1L) { ui.zoomFrom = null; ui.pick = -1L }
                    }
                    Wave(
                        buckets = buckets, blocks = blocks,
                        fromMs = viewFrom, toMs = now, sel = ui.sel, playheadMs = playheadMs,
                        onSelect = { f, t -> select(f, t) },
                        onBlock = { f, t ->
                            // In the zoomed-out view a "block" is hours long: zoom into it instead of
                            // selecting it, so the next tap lands on the real stretch.
                            if (ui.zoomFrom == null && t - f > 20 * 60_000L) { ui.zoomFrom = f - (t - f) / 4; ui.pick = null }
                            else select(f, t)
                        },
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(clock(viewFrom), style = MaterialTheme.typography.labelSmall, color = Mist)
                        Text("now ${clock(now)}", style = MaterialTheme.typography.labelSmall, color = Mist)
                    }
                    Text(
                        if (blocks.isEmpty()) "Bright waves are talking. Tap to place the nearest handle, or drag it."
                        else "Bright waves are talking. Tap one to select it, or drag the handles.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    AnimatedVisibility(
                        visible = s != null,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically(),
                    ) {
                        if (s != null) {
                            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                Column {
                                    Text("${clock(s.first)} → ${clock(s.last)}", style = MaterialTheme.typography.titleMedium)
                                    Text("${fmtDur(s.last - s.first)} selected", style = MaterialTheme.typography.bodySmall)
                                }
                                NudgeRow("Start", s.first) { d -> select(s.first + d, s.last) }
                                NudgeRow("End", s.last) { d -> select(s.first, s.last + d) }
                                Transport(player, ui.rendering)
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(
                                        onClick = { saveSelection(send = false) },
                                        enabled = !busy, modifier = Modifier.weight(1f).height(48.dp),
                                    ) {
                                        Icon(Icons.Rounded.Save, null); Spacer(Modifier.width(8.dp)); Text("Save clip")
                                    }
                                    Button(
                                        onClick = { saveSelection(send = true) },
                                        enabled = !busy, modifier = Modifier.weight(1f).height(48.dp),
                                    ) {
                                        if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.Black)
                                        else Icon(Icons.Rounded.Send, null)
                                        Spacer(Modifier.width(8.dp)); Text("Send")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Start listening, asking for the microphone (and notifications, so Stop stays reachable) first. */
@Composable
fun rememberStartRecording(snackbar: SnackbarHostState): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (granted(context, Manifest.permission.RECORD_AUDIO)) RecorderService.start(context)
        else scope.launch { snackbar.showSnackbar("The microphone permission is needed to record.") }
    }
    return {
        val missing = listOfNotNull(
            Manifest.permission.RECORD_AUDIO.takeUnless { granted(context, it) },
            Manifest.permission.POST_NOTIFICATIONS.takeIf { Build.VERSION.SDK_INT >= 33 && !granted(context, it) },
        )
        if (missing.isEmpty()) RecorderService.start(context) else launcher.launch(missing.toTypedArray())
    }
}

@Composable
fun ClipsTab(player: PreviewPlayer, snackbar: SnackbarHostState, padding: PaddingValues, openNote: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var savedTick by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    val saved by produceState<List<SavedClip>?>(null, savedTick) {
        value = withContext(Dispatchers.IO) { runCatching { ClipExporter.listSaved(context) }.getOrDefault(emptyList()) }
    }
    fun playSaved(clip: SavedClip) {
        scope.launch {
            try {
                player.load(null, clip.uri, clip.name)
                player.playFrom(0)
            } catch (t: Throwable) {
                snackbar.showSnackbar("Could not play: ${t.message}")
            }
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = padding, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { PageTitle("Clips") }
        if (saved?.isEmpty() == true) {
            item {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(top = 24.dp)) {
                    Duo(Mood.SLEEP, Modifier.width(200.dp))
                    Spacer(Modifier.height(16.dp))
                    Text("No clips yet. Pick a moment on the Listen tab and save it; clips also land in Recordings/RollingRecorder.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        itemsIndexed(saved ?: emptyList()) { i, clip ->
            SavedClipRow(
                clip = clip, index = i,
                isPlaying = player.title == clip.name && player.playing,
                busy = busy,
                onPlay = { if (player.title == clip.name && player.ready) player.toggle() else playSaved(clip) },
                onSend = {
                    if (!busy) scope.launch {
                        busy = true
                        sendToTurnwise(context, snackbar, clip.id, clip.uri, clip.name, clip.addedMs, openNote)
                        savedTick++
                        busy = false
                    }
                },
                onOpen = { clip.noteId?.let(openNote) },
                onShare = { shareClip(context, clip.uri) },
            )
        }
    }
}

/** The phone-side settings: how much to keep and where Turnwise lives. */
@Composable
fun ListenerSettings(onAddressChanged: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { Settings(context) }
    val store = remember { ChunkStore(context) }
    val scope = rememberCoroutineScope()
    var windowHours by remember { mutableIntStateOf(settings.windowHours) }
    var turnwiseUrl by remember { mutableStateOf(settings.turnwiseUrl) }
    Panel {
        SectionTitle("Rolling window")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WINDOW_CHOICES.forEach { h ->
                Pill("${h}h", windowHours == h) {
                    windowHours = h
                    settings.windowHours = h
                    scope.launch(Dispatchers.IO) {
                        store.prune(settings.windowMs, keepFromMs = settings.recordMark)
                        RecorderState.chunkTick.value = RecorderState.chunkTick.value + 1
                    }
                }
            }
        }
        val maxMb = windowHours * 3600L * BIT_RATE / 8 / 1_000_000L
        Text("Uses up to about $maxMb MB. A shorter window deletes older audio straight away.", style = MaterialTheme.typography.bodySmall)
    }
    Spacer(Modifier.height(16.dp))
    Panel {
        SectionTitle("Server")
        OutlinedTextField(
            value = turnwiseUrl,
            onValueChange = { turnwiseUrl = it; settings.turnwiseUrl = it },
            label = { Text("Turnwise address") },
            leadingIcon = { Icon(Icons.Rounded.Cloud, null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text("Your Tailscale Serve address for Turnwise. The phone must be on the tailnet.", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = onAddressChanged) { Text("Reconnect", color = Green) }
    }
}

/** Gradient hero where Blip and Bolt act out the recorder's state: listening, paused, asleep. */
@Composable
private fun Hero(status: Status, subtitle: String, onStart: () -> Unit, onStop: () -> Unit) {
    val top by animateColorAsState(
        when (status) {
            Status.RECORDING -> Color(0xFF1B4D36)
            Status.PAUSED -> Color(0xFF4D3A12)
            Status.STOPPED -> Color(0xFF2A2A2A)
            else -> Color(0xFF1E3A4D)
        },
        tween(600), label = "heroTop",
    )
    val mood = when (status) {
        Status.RECORDING -> Mood.LISTEN
        Status.STOPPED -> Mood.SLEEP
        else -> Mood.THINK
    }
    Box(
        Modifier
            .fillMaxWidth()
            .clip(CARD)
            .background(Brush.verticalGradient(listOf(top, MaterialTheme.colorScheme.surface))),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Duo(mood, Modifier.width(184.dp).padding(top = 12.dp))
                Spacer(Modifier.weight(1f))
                val stopped = status == Status.STOPPED
                Box(
                    Modifier
                        .size(56.dp)
                        .background(if (stopped) Green else Color.White.copy(alpha = 0.14f), CircleShape)
                        .clickable { if (stopped) onStart() else onStop() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (stopped) Icons.Rounded.PlayArrow else Icons.Rounded.Stop, if (stopped) "Start" else "Stop",
                        tint = if (stopped) Color.Black else Color.White, modifier = Modifier.size(30.dp),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            AnimatedContent(
                statusTitle(status),
                transitionSpec = { (fadeIn(tween(250)) + slideInVertically { it / 2 }) togetherWith (fadeOut(tween(150)) + slideOutVertically { -it / 2 }) },
                label = "title",
            ) { Text(it, style = MaterialTheme.typography.headlineMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            Text(statusDetail(status) ?: subtitle, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun NudgeRow(label: String, at: Long, onNudge: (Long) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Mist, modifier = Modifier.width(44.dp))
        Text(clock(at), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Pill("−1 min", false) { onNudge(-60_000) }
        Spacer(Modifier.width(8.dp))
        Pill("+1 min", false) { onNudge(60_000) }
    }
}

/** Player-style transport: big round play, jump to start, jump to the last 15 s. */
@Composable
private fun Transport(player: PreviewPlayer, rendering: Boolean) {
    val ready = player.ready && !rendering
    val playScale by animateFloatAsState(if (player.playing) 1.08f else 1f, spring(dampingRatio = 0.5f), label = "play")
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Slider(
                value = if (player.durationMs > 0) player.positionMs.toFloat() / player.durationMs else 0f,
                onValueChange = { player.seek((it * player.durationMs).toLong()) },
                enabled = ready,
                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Green, inactiveTrackColor = Color.White.copy(alpha = 0.2f)),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (ready) fmtClock(player.positionMs) else "0:00", style = MaterialTheme.typography.labelSmall, color = Mist)
                Text(if (rendering) "Preparing…" else if (ready) fmtClock(player.durationMs) else "", style = MaterialTheme.typography.labelSmall, color = Mist)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                TransportButton(Icons.Rounded.SkipPrevious, "Start", ready) { player.playFrom(0) }
                Spacer(Modifier.width(24.dp))
                Box(
                    Modifier
                        .size(64.dp)
                        .scale(playScale)
                        .background(if (ready) Green else Color(0xFF535353), CircleShape)
                        .clickable(enabled = ready) { player.toggle() },
                    contentAlignment = Alignment.Center,
                ) {
                    if (rendering) CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp, color = Color.White)
                    else Icon(if (player.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (player.playing) "Pause" else "Play", tint = Color.Black, modifier = Modifier.size(36.dp))
                }
                Spacer(Modifier.width(24.dp))
                TransportButton(Icons.Rounded.SkipNext, "Last 15 s", ready) { player.playFrom(player.durationMs - 15_000) }
            }
        }
    }
}

@Composable
private fun TransportButton(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp)) {
            Icon(icon, label, tint = if (enabled) Color.White else Color(0xFF535353), modifier = Modifier.size(32.dp))
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = Mist)
    }
}

@Composable
private fun SavedClipRow(
    clip: SavedClip, index: Int, isPlaying: Boolean, busy: Boolean,
    onPlay: () -> Unit, onSend: () -> Unit, onOpen: () -> Unit, onShare: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)).background(Brush.linearGradient(TILE_GRADIENTS[index % TILE_GRADIENTS.size])),
            contentAlignment = Alignment.Center,
        ) { Icon(if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.GraphicEq, null, tint = Color.White) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                clipTitle(clip.name),
                style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (isPlaying) Green else Color.White,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (clip.noteId != null) {
                    Text("In Turnwise", color = Green, style = MaterialTheme.typography.labelSmall)
                    Text(" · ", style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    "${fmtDur(clip.durationMs)} · ${"%.1f".format(clip.sizeBytes / 1_000_000.0)} MB",
                    style = MaterialTheme.typography.bodySmall, maxLines = 1,
                )
            }
        }
        IconButton(onClick = onPlay, modifier = Modifier.size(40.dp)) { Icon(if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (isPlaying) "Pause" else "Play", tint = Color.White) }
        if (clip.noteId != null) {
            IconButton(onClick = onOpen, modifier = Modifier.size(40.dp)) { Icon(Icons.Rounded.OpenInNew, "Open in Turnwise", tint = Green) }
        } else {
            IconButton(onClick = onSend, enabled = !busy, modifier = Modifier.size(40.dp)) { Icon(Icons.Rounded.Send, "Send to Turnwise", tint = Color.White) }
        }
        IconButton(onClick = onShare, modifier = Modifier.size(40.dp)) { Icon(Icons.Rounded.Share, "Share", tint = Mist) }
    }
}

/** Loudness as a mirrored wave over the whole buffer, with a glowing draggable selection. */
@Composable
private fun Wave(
    buckets: IntArray, blocks: List<IntRange>,
    fromMs: Long, toMs: Long, sel: LongRange?, playheadMs: Long?,
    onSelect: (Long, Long) -> Unit,
    onBlock: (Long, Long) -> Unit,
) {
    val latest = rememberUpdatedState(Triple(fromMs, toMs, sel))
    val latestBlocks = rememberUpdatedState(blocks)
    var dragging by remember { mutableIntStateOf(-1) }   // 0 = start handle, 1 = end handle
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(Unit) { reveal.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) }
    val span = (toMs - fromMs).coerceAtLeast(1).toFloat()
    val selStart by animateFloatAsState(sel?.let { (it.first - fromMs) / span } ?: 0f, spring(stiffness = 600f), label = "selStart")
    val selEnd by animateFloatAsState(sel?.let { (it.last - fromMs) / span } ?: 0f, spring(stiffness = 600f), label = "selEnd")

    fun timeAt(x: Float, width: Float): Long {
        val (f, t, _) = latest.value
        return f + ((x / width).coerceIn(0f, 1f) * (t - f)).toLong()
    }
    fun nearestHandle(x: Float, width: Float): Int {
        val (f, t, s) = latest.value
        if (s == null) return 1
        val sp = (t - f).coerceAtLeast(1).toFloat()
        val xs = (s.first - f) / sp * width
        val xe = (s.last - f) / sp * width
        return if (abs(x - xs) <= abs(x - xe)) 0 else 1
    }
    fun moveHandle(handle: Int, x: Float, width: Float) {
        val (f, t, s) = latest.value
        val at = timeAt(x, width)
        if (s == null) { if (handle == 0) onSelect(at, t) else onSelect(f, at); return }
        if (handle == 0) onSelect(at, s.last) else onSelect(s.first, at)
    }

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(120.dp)
            .pointerInput(Unit) {
                detectTapGestures { o ->
                    val (f, t, _) = latest.value
                    val b = (o.x / size.width * buckets.size).toInt().coerceIn(0, buckets.size - 1)
                    val block = latestBlocks.value.firstOrNull { b in it }
                    if (block != null) {
                        val per = (t - f).toDouble() / buckets.size
                        onBlock(f + (block.first * per).toLong(), f + ((block.last + 1) * per).toLong())
                    } else {
                        moveHandle(nearestHandle(o.x, size.width.toFloat()), o.x, size.width.toFloat())
                    }
                }
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { o -> dragging = nearestHandle(o.x, size.width.toFloat()); moveHandle(dragging, o.x, size.width.toFloat()) },
                    onDragEnd = { dragging = -1 },
                    onDragCancel = { dragging = -1 },
                ) { change, _ ->
                    change.consume()
                    if (dragging >= 0) moveHandle(dragging, change.position.x, size.width.toFloat())
                }
            }
    ) {
        val w = size.width
        val h = size.height
        val mid = h / 2
        val step = w / buckets.size
        val path = wavePath(buckets, w, mid * 0.92f * reveal.value, mid)

        // Whole buffer, dim.
        drawLine(Color.White.copy(alpha = 0.12f), Offset(0f, mid), Offset(w, mid), 1.dp.toPx())
        drawPath(path, Color.White.copy(alpha = 0.22f))
        // Talking blocks, bright.
        for (b in blocks) {
            clipRect(b.first * step, 0f, (b.last + 1) * step, h) { drawPath(path, Color.White.copy(alpha = 0.6f)) }
        }
        // Selection, green with a glow and handles.
        if (sel != null) {
            val xs = selStart * w
            val xe = selEnd * w
            clipRect(xs, 0f, xe, h) {
                drawRect(Green.copy(alpha = 0.10f))
                drawPath(path, Green.copy(alpha = 0.35f), style = Stroke(8.dp.toPx()))
                drawPath(path, Brush.verticalGradient(listOf(Color(0xFF7CF5A8), Green, Color(0xFF0E7A36))))
            }
            for (x in listOf(xs, xe)) {
                drawLine(Green, Offset(x, 0f), Offset(x, h), 2.dp.toPx())
                drawCircle(Color.White, 7.dp.toPx(), Offset(x, 10.dp.toPx()))
                drawCircle(Green, 4.dp.toPx(), Offset(x, 10.dp.toPx()))
            }
            if (playheadMs != null) {
                val px = ((playheadMs - fromMs) / span * w).coerceIn(xs, xe)
                drawLine(Color.White, Offset(px, 4.dp.toPx()), Offset(px, h - 4.dp.toPx()), 2.dp.toPx())
            }
        }
    }
}

/** Closed envelope through the bucket levels, mirrored around [mid], smoothed with quadratic curves. */
private fun DrawScope.wavePath(buckets: IntArray, w: Float, amp: Float, mid: Float): Path {
    val n = buckets.size
    val step = w / n
    val minA = 1.5.dp.toPx()
    fun a(i: Int) = if (buckets[i] < 0) 0f else minA + (buckets[i] / 255f) * amp
    val p = Path()
    p.moveTo(0f, mid - a(0))
    for (i in 0 until n - 1) {
        val x0 = i * step + step / 2; val x1 = x0 + step
        val y0 = mid - a(i); val y1 = mid - a(i + 1)
        p.quadraticTo(x0, y0, (x0 + x1) / 2, (y0 + y1) / 2)
    }
    p.lineTo(w, mid - a(n - 1))
    p.lineTo(w, mid + a(n - 1))
    for (i in n - 1 downTo 1) {
        val x0 = i * step + step / 2; val x1 = x0 - step
        val y0 = mid + a(i); val y1 = mid + a(i - 1)
        p.quadraticTo(x0, y0, (x0 + x1) / 2, (y0 + y1) / 2)
    }
    p.lineTo(0f, mid + a(0))
    p.close()
    return p
}

private fun statusTitle(s: Status): String = when (s) {
    Status.STOPPED -> "Not listening"
    Status.STARTING -> "Starting…"
    Status.RECORDING -> "Listening"
    Status.PAUSED -> "Paused"
    Status.RETRYING -> "Waiting for mic"
}

private fun statusDetail(s: Status): String? = when (s) {
    Status.PAUSED -> "Another app has the microphone. Resumes by itself when it is free."
    Status.STOPPED -> "Nothing is recorded while stopped, and it will not restart by itself."
    else -> null
}
