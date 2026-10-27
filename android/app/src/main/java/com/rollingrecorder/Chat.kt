package com.rollingrecorder

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private fun modelLabel(m: String) = if (m == "claude") "Claude" else "Local model"

private class ChatsData(val sessions: List<ChatSummary>, val titles: Map<String, String>, val noteTitle: String?)

/** All chats ([note] = null, the Ask tab) or the chats about one recording. */
@Composable
fun ChatsScreen(note: String?, snackbar: SnackbarHostState, padding: PaddingValues, back: (() -> Unit)?, openChat: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val polled = rememberPolled("chats:${note.orEmpty()}") {
        val sessions = Api.chats(note)
        @Suppress("UNCHECKED_CAST")
        val notes = if (note == null) (PollCache["notes|"] as? List<NoteSummary>) ?: Api.notes() else emptyList()
        ChatsData(sessions, notes.associate { it.id to it.title }, note?.let { Api.note(it).title })
    }
    var creating by remember { mutableStateOf(false) }
    val data = polled.value

    LazyColumn(Modifier.fillMaxSize(), contentPadding = padding, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (back != null) TopBar(back, "Note")
                Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth()) {
                    Box(Modifier.weight(1f)) { PageTitle(data?.noteTitle ?: if (note == null) "Ask" else "Chats") }
                    if (note == null) Duo(if (creating) Mood.THINK else Mood.CHAT, Modifier.width(150.dp))
                }
                Text(
                    if (note == null) "Questions are answered from all your recordings, with the moments they came from."
                    else "Questions are answered from this recording.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Green).clickable(enabled = !creating) {
                        creating = true
                        scope.launch {
                            try { openChat(Api.newChat(note).id) } catch (t: Throwable) { snackbar.showSnackbar(errorText(t)) }
                            creating = false
                        }
                    }.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Add, null, tint = Color.Black)
                    Spacer(Modifier.width(12.dp))
                    Text("New chat", style = MaterialTheme.typography.labelLarge, color = Color.Black)
                }
                polled.error?.let { Banner(it, error = true) }
            }
        }
        if (data != null && data.sessions.isEmpty()) item { Text("No chats yet.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
        items(data?.sessions ?: emptyList(), key = { it.id }) { s ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { openChat(s.id) }.padding(vertical = 12.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(44.dp).background(Raised, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.AutoMirrored.Rounded.Chat, null, tint = if (s.model == "claude") Color(0xFFD9A66B) else Green, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(s.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(
                            java.text.SimpleDateFormat("d MMM, HH:mm", java.util.Locale.getDefault()).format(java.util.Date((s.updated * 1000).toLong())),
                            if (note == null && s.note != null) data?.titles?.get(s.note) ?: "a recording" else null,
                            if (s.model == "claude") "Claude" else "Local",
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private sealed interface ChatRow {
    val key: String
    data class Msg(val mi: Int) : ChatRow { override val key get() = "m$mi" }
    data class Src(val mi: Int, val k: Int, val src: Source) : ChatRow { override val key get() = "s$mi-$k" }
}

@Composable
fun ChatScreen(
    sid: String, player: PreviewPlayer, snackbar: SnackbarHostState, padding: PaddingValues,
    back: () -> Unit, openNote: (String, Double?) -> Unit, openChat: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val polled = rememberPolled("chat:$sid", every = { c -> if (c.answering) 2_000L else null }) { Api.chat(sid) }
    val me = rememberPolled("me") { Api.me() }
    var model by rememberSaveable { mutableStateOf<String?>(null) }
    var input by rememberSaveable { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var flash by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val chat = polled.value
    val chosen = model ?: chat?.model ?: "local"

    val rows = remember(chat) {
        chat?.messages?.flatMapIndexed { mi, m ->
            listOf<ChatRow>(ChatRow.Msg(mi)) + if (m.role == "assistant" && !m.pending) m.sources.mapIndexed { k, s -> ChatRow.Src(mi, k + 1, s) } else emptyList()
        } ?: emptyList()
    }
    val header = 1
    LaunchedEffect(chat?.messages?.size, chat?.answering) { if (rows.isNotEmpty()) listState.animateScrollToItem(rows.size - 1 + header) }

    fun ask() {
        val text = input.trim()
        if (text.isEmpty() || sending || chat?.answering == true) return
        sending = true
        scope.launch {
            try {
                polled.value = Api.ask(sid, text, chosen)
                input = ""
                polled.refresh()
            } catch (t: Throwable) { snackbar.showSnackbar("Could not ask: ${errorText(t)}") }
            sending = false
        }
    }
    fun playSource(s: Source) {
        scope.launch {
            try {
                @Suppress("UNCHECKED_CAST")
                val n = (PollCache["note:${s.note}"] as? Note) ?: Api.note(s.note).also { PollCache["note:${s.note}"] = it }
                playNote(player, n, s.start)
            } catch (t: Throwable) { snackbar.showSnackbar("Could not play: ${errorText(t)}") }
        }
    }

    val ime = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
    Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding(), bottom = maxOf(padding.calculateBottomPadding(), ime))) {
        Box(Modifier.padding(horizontal = 16.dp)) {
            TopBar(back, "Chats") {
                Box {
                    IconButton(onClick = { menu = true }, enabled = chat != null) { Icon(Icons.Rounded.MoreVert, "More actions", tint = Color.White) }
                    DropdownMenu(menu, { menu = false }, containerColor = Raised) {
                        DropdownMenuItem(text = { Text("New chat") }, onClick = {
                            menu = false
                            scope.launch { try { openChat(Api.newChat(chat?.note, chosen).id) } catch (t: Throwable) { snackbar.showSnackbar(errorText(t)) } }
                        })
                        DropdownMenuItem(text = { Text("Clear messages") }, onClick = { menu = false; dialog = "clear" })
                        DropdownMenuItem(text = { Text("Delete chat", color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; dialog = "delete" })
                    }
                }
            }
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(), state = listState,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(chat?.title ?: "Loading…", style = MaterialTheme.typography.titleMedium)
                    if (me.value?.owner == true) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("local" to "Local", "claude" to "Claude").forEach { (k, l) -> Pill(l, chosen == k) { model = k } }
                    }
                    polled.error?.let { Banner(it, error = true) }
                    if (chat != null && chat.messages.isEmpty()) Text(
                        "Ask anything about ${if (chat.note != null) "this recording" else "your recordings"}.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            items(rows, key = { it.key }) { r ->
                when (r) {
                    is ChatRow.Msg -> MessageView(chat!!.messages[r.mi]) { k ->
                        val i = rows.indexOfFirst { it is ChatRow.Src && it.mi == r.mi && it.k == k }
                        if (i >= 0) scope.launch {
                            listState.animateScrollToItem(i + header, -200)
                            flash = "s${r.mi}-$k"; delay(1_000); flash = null
                        }
                    }
                    is ChatRow.Src -> SourceCard(
                        r.k, r.src, flash = flash == r.key,
                        playing = player.noteId == r.src.note && player.playing && player.turnIndex() == r.src.turn,
                        onPlay = { if (player.noteId == r.src.note && player.playing && player.turnIndex() == r.src.turn) player.toggle() else playSource(r.src) },
                        onOpen = { openNote(r.src.note, r.src.start) },
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextField(
                input, { input = it }, placeholder = { Text("Ask a question") },
                enabled = chat != null && !chat.answering, shape = CircleShape, maxLines = 4,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Raised, unfocusedContainerColor = Raised, disabledContainerColor = Raised,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            val can = input.isNotBlank() && chat?.answering == false && !sending
            Box(
                Modifier.size(48.dp).background(if (can) Green else Raised, CircleShape).clickable(enabled = can) { ask() },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.AutoMirrored.Rounded.Send, "Send", tint = if (can) Color.Black else Mist) }
        }
    }

    when (dialog) {
        "clear" -> ConfirmDialog("Clear this chat?", "The questions and answers will be removed.", "Clear", true, { dialog = null }) {
            dialog = null
            scope.launch { try { Api.clearChat(sid); polled.refresh() } catch (t: Throwable) { snackbar.showSnackbar(errorText(t)) } }
        }
        "delete" -> ConfirmDialog("Delete this chat?", "", "Delete", true, { dialog = null }) {
            dialog = null
            scope.launch { try { Api.deleteChat(sid); back() } catch (t: Throwable) { snackbar.showSnackbar(errorText(t)) } }
        }
    }
}

@Composable
private fun MessageView(m: Message, onCite: (Int) -> Unit) {
    when {
        m.role == "user" -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Surface(shape = RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp), color = Green, modifier = Modifier.widthIn(max = 300.dp)) {
                Text(m.text, color = Color.Black, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
            }
        }
        m.pending -> {
            val now by produceState(System.currentTimeMillis() / 1000.0) { while (true) { value = System.currentTimeMillis() / 1000.0; delay(1_000) } }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Duo(Mood.THINK, Modifier.width(112.dp))
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("${modelLabel(m.model)} is writing", style = MaterialTheme.typography.bodyMedium)
                    Text("${m.detail} · ${elapsedLabel(now - m.ts)}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        m.error != null -> Banner(m.error, error = true)
        else -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            MdText(m.text, onCite)
            Text(modelLabel(m.model), style = MaterialTheme.typography.labelSmall, color = Mist)
        }
    }
}

@Composable
private fun SourceCard(k: Int, s: Source, flash: Boolean, playing: Boolean, onPlay: () -> Unit, onOpen: () -> Unit) {
    val border by animateColorAsState(if (flash || playing) Green else Color.Transparent, label = "src")
    Surface(shape = RoundedCornerShape(16.dp), color = Raised, modifier = Modifier.fillMaxWidth().border(2.dp, border, RoundedCornerShape(16.dp))) {
        Row(Modifier.padding(12.dp)) {
            Box(
                Modifier.size(40.dp).background(if (playing) Green else Color.White.copy(alpha = 0.1f), CircleShape).clickable(onClick = onPlay),
                contentAlignment = Alignment.Center,
            ) {
                if (playing) Bars(Color.Black) else Icon(Icons.Rounded.PlayArrow, "Play this moment", tint = Color.White)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f).clickable(onClick = onOpen)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("[$k] ", style = MaterialTheme.typography.labelSmall, color = Green)
                    VoiceChip(s.speaker, 18.dp)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "${s.speaker} · ${s.title} · ${tsLabel(s.start)}", style = MaterialTheme.typography.labelSmall, color = Mist,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(s.hinglish, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
                if (s.english.isNotBlank() && s.english != s.hinglish) Text(s.english, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

/** Four bouncing bars: "something is talking". */
@Composable
private fun Bars(color: Color = Green) {
    val t = rememberInfiniteTransition(label = "bars")
    Row(Modifier.height(18.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
        repeat(4) { i ->
            val h by t.animateFloat(0.3f, 1f, infiniteRepeatable(tween(420 + i * 90), RepeatMode.Reverse), label = "bar$i")
            Box(Modifier.width(3.dp).fillMaxHeightFraction(h).background(color, RoundedCornerShape(2.dp)))
        }
    }
}

private fun Modifier.fillMaxHeightFraction(f: Float) = this.then(Modifier.height((18 * f).dp))
