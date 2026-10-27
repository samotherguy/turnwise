package com.rollingrecorder

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Notes
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Screens pushed over the tabs. */
sealed interface Screen {
    data class NoteS(val id: String, val atSec: Double? = null) : Screen
    data class ChatsS(val note: String) : Screen
    data class ChatS(val id: String) : Screen
}

class Nav {
    val stack = mutableStateListOf<Screen>()
    var tab by mutableIntStateOf(0)
    fun push(s: Screen) { stack.add(s) }
    fun pop() { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) }
    fun goTab(i: Int) { stack.clear(); tab = i }
}

/** Last good response per screen, so revisiting paints at once while the fresh copy loads. */
object PollCache : HashMap<String, Any?>()

/** Bumped when the notes list must reload (upload, delete, rename). */
object Bus {
    var notesVersion by mutableIntStateOf(0)
}

class Uploads {
    var label by mutableStateOf<String?>(null)    // non-null while uploading
    var index by mutableIntStateOf(0)
    var total by mutableIntStateOf(0)
    var progress by mutableFloatStateOf(0f)
}

private data class Tab(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)
private val TABS = listOf(
    Tab("Notes", Icons.Rounded.Notes),
    Tab("Listen", Icons.Rounded.GraphicEq),
    Tab("Ask", Icons.AutoMirrored.Rounded.Chat),
    Tab("Clips", Icons.Rounded.LibraryMusic),
    Tab("Settings", Icons.Rounded.Settings),
)
const val TAB_LISTEN = 1
const val TAB_CLIPS = 3

@Composable
fun TurnwiseApp(incoming: MutableState<Intent?>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val player = remember { PreviewPlayer(context) }
    DisposableEffect(Unit) { onDispose { player.unload() } }
    val snackbar = remember { SnackbarHostState() }
    val listen = remember { ListenUi() }
    val nav = remember { Nav() }
    val uploads = remember { Uploads() }
    val saveable = rememberSaveableStateHolder()

    LaunchedEffect(listen.sel) { renderPreview(context, listen, player, snackbar) }
    LaunchedEffect(player.playing) { while (player.playing) { player.tick(); delay(100) } }

    val openNote: (String) -> Unit = { nav.push(Screen.NoteS(it)) }
    fun upload(uris: List<Uri>, folder: String) {
        if (uploads.label != null || uris.isEmpty()) return
        scope.launch { runUploads(context, uploads, uris, folder, snackbar, openNote) }
    }

    // Shared files (share sheet) and "Sent ✓" notification taps.
    LaunchedEffect(incoming.value) {
        val i = incoming.value ?: return@LaunchedEffect
        incoming.value = null
        i.getStringExtra(EXTRA_NOTE)?.let { nav.goTab(0); openNote(it); return@LaunchedEffect }
        val uris = when (i.action) {
            Intent.ACTION_SEND -> listOfNotNull(i.streamExtra())
            Intent.ACTION_SEND_MULTIPLE -> i.streamExtras()
            else -> emptyList()
        }
        if (uris.isNotEmpty()) { nav.goTab(0); upload(uris, "") }
    }

    BackHandler(nav.stack.isNotEmpty()) { nav.pop() }
    BackHandler(nav.stack.isEmpty() && nav.tab != 0) { nav.tab = 0 }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            Column {
                AnimatedVisibility(player.ready || listen.rendering, enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
                    MiniPlayer(
                        player, listen.rendering,
                        onOpen = {
                            val id = player.noteId
                            when {
                                id != null -> nav.push(Screen.NoteS(id))
                                player.title == "Selection" || listen.rendering -> nav.goTab(TAB_LISTEN)
                                else -> nav.goTab(TAB_CLIPS)
                            }
                        },
                        onClose = { if (player.title == "Selection") listen.sel = null; player.unload() },
                    )
                }
                NavigationBar(containerColor = Panel) {
                    TABS.forEachIndexed { i, t ->
                        NavigationBarItem(
                            selected = nav.tab == i,
                            onClick = { nav.goTab(i) },
                            icon = { Icon(t.icon, null) }, label = { Text(t.label, style = MaterialTheme.typography.labelSmall, maxLines = 1) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Color.Black, selectedTextColor = Color.White,
                                indicatorColor = Green, unselectedIconColor = Mist, unselectedTextColor = Mist,
                            ),
                        )
                    }
                }
            }
        },
    ) { padding ->
        val pad = PaddingValues(
            start = 16.dp, end = 16.dp,
            top = padding.calculateTopPadding() + 8.dp,
            bottom = padding.calculateBottomPadding() + 24.dp,
        )
        val target: Any = nav.stack.lastOrNull() ?: nav.tab
        Box(Modifier.fillMaxSize()) {
        AnimatedContent(
            target,
            transitionSpec = {
                if (targetState is Screen) (slideInHorizontally(tween(220)) { it / 6 } + fadeIn(tween(220))) togetherWith fadeOut(tween(120))
                else fadeIn(tween(200)) togetherWith fadeOut(tween(150))
            },
            label = "screen",
        ) { t ->
            saveable.SaveableStateProvider(t.toString()) {
                when (t) {
                    is Screen.NoteS -> NoteScreen(
                        t.id, t.atSec, player, snackbar, pad, back = nav::pop,
                        openChats = { nav.push(Screen.ChatsS(it)) },
                    )
                    is Screen.ChatsS -> ChatsScreen(t.note, snackbar, pad, back = nav::pop, openChat = { nav.push(Screen.ChatS(it)) })
                    is Screen.ChatS -> ChatScreen(
                        t.id, player, snackbar, padding, back = nav::pop,
                        openNote = { id, at -> nav.push(Screen.NoteS(id, at)) },
                        openChat = { nav.pop(); nav.push(Screen.ChatS(it)) },
                    )
                    0 -> NotesTab(snackbar, pad, uploads, openNote = openNote, upload = ::upload)
                    1 -> ListenTab(listen, player, snackbar, pad, openNote)
                    2 -> ChatsScreen(null, snackbar, pad, back = null, openChat = { nav.push(Screen.ChatS(it)) })
                    3 -> ClipsTab(player, snackbar, pad, openNote)
                    else -> SettingsTab(pad)
                }
            }
        }
        // Lists scroll under a solid status bar, not through the clock.
        Box(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars).background(Ink))
        }
    }
}

/** Spotify-style bar above the tabs: what is playing, who is talking, play/pause and turn skips. */
@Composable
private fun MiniPlayer(player: PreviewPlayer, rendering: Boolean, onOpen: () -> Unit, onClose: () -> Unit) {
    val title = when {
        rendering -> "Preparing…"
        player.title == "Selection" -> "Selected moment"
        player.noteId != null -> player.title
        else -> clipTitle(player.title)
    }
    val seg = player.segments.getOrNull(player.turnIndex())
    val who = seg?.let { player.speakerNames[it.speaker] ?: spkLabel(it.speaker) }
    Surface(color = Raised, modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Column {
            Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (who != null) VoiceChip(who, 40.dp) else Box(
                    Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).background(Brush.linearGradient(TILE_GRADIENTS[0])),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.GraphicEq, null, tint = Color.White, modifier = Modifier.size(22.dp)) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        when {
                            seg != null -> "$who · ${seg.hinglish ?: seg.text}"
                            player.ready -> "${fmtClock(player.positionMs)} / ${fmtClock(player.durationMs)}"
                            else -> ""
                        },
                        style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                if (player.segments.isNotEmpty()) IconButton(onClick = { player.stepTurn(-1) }) { Icon(Icons.Rounded.SkipPrevious, "Previous turn", tint = Color.White) }
                IconButton(onClick = { player.toggle() }, enabled = player.ready && !rendering) {
                    Icon(if (player.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (player.playing) "Pause" else "Play", tint = Color.White, modifier = Modifier.size(32.dp))
                }
                if (player.segments.isNotEmpty()) IconButton(onClick = { player.stepTurn(1) }) { Icon(Icons.Rounded.SkipNext, "Next turn", tint = Color.White) }
                IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Close player", tint = Mist) }
            }
            val f = if (player.durationMs > 0) player.positionMs.toFloat() / player.durationMs else 0f
            Box(Modifier.fillMaxWidth().height(2.dp).background(Color.White.copy(alpha = 0.15f))) {
                Box(Modifier.fillMaxWidth(f.coerceIn(0f, 1f)).height(2.dp).background(Green))
            }
        }
    }
}

const val EXTRA_NOTE = "com.rollingrecorder.NOTE"

@Suppress("DEPRECATION")
private fun Intent.streamExtra(): Uri? =
    if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else getParcelableExtra(Intent.EXTRA_STREAM)

@Suppress("DEPRECATION")
private fun Intent.streamExtras(): List<Uri> =
    (if (Build.VERSION.SDK_INT >= 33) getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) else getParcelableArrayListExtra(Intent.EXTRA_STREAM)) ?: emptyList()

private fun displayName(context: Context, uri: Uri): String =
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment ?: "recording"

/** The file's own timestamp when the provider knows it, so notes land on the day they were recorded. */
private fun modifiedMs(context: Context, uri: Uri): Long? =
    runCatching {
        context.contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }
    }.getOrNull()?.takeIf { it > 0 }

/** Uploads one file at a time (big files, one disk on the server), then opens the note when there was one. */
suspend fun runUploads(
    context: Context, up: Uploads, uris: List<Uri>, folder: String, snackbar: SnackbarHostState, openNote: (String) -> Unit,
) {
    val base = Settings(context).turnwiseUrl
    val done = mutableListOf<Turnwise.Uploaded>()
    var failed: String? = null
    up.total = uris.size
    for ((i, uri) in uris.withIndex()) {
        val name = displayName(context, uri)
        up.label = name
        up.index = i
        up.progress = 0f
        try {
            done += withContext(Dispatchers.IO) {
                Turnwise.upload(context, base, uri, name, modifiedMs(context, uri) ?: System.currentTimeMillis(), folder = folder) { up.progress = it }
            }
        } catch (t: Throwable) {
            failed = t.message ?: "failed"
        }
    }
    up.label = null
    Bus.notesVersion++
    if (uris.size == 1 && done.size == 1) {
        if (done[0].duplicate) snackbar.showSnackbar("Already transcribed. Opening it.")
        openNote(done[0].id)
        return
    }
    val queued = done.count { !it.duplicate }
    val dup = done.size - queued
    snackbar.showSnackbar(
        listOfNotNull(
            "$queued queued",
            if (dup > 0) "$dup already done" else null,
            if (uris.size > done.size) "${uris.size - done.size} failed ($failed)" else null,
        ).joinToString(", "),
    )
}
