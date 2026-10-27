package com.rollingrecorder

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.Locale

// ---------- look ----------

val CARD = RoundedCornerShape(24.dp)

val TILE_GRADIENTS = listOf(
    listOf(Color(0xFF1DB954), Color(0xFF0E5C2B)),
    listOf(Color(0xFF7C9CFF), Color(0xFF2B3A8C)),
    listOf(Color(0xFFFF8A65), Color(0xFF8C3A1E)),
    listOf(Color(0xFFE056FD), Color(0xFF6A1B9A)),
    listOf(Color(0xFF26C6DA), Color(0xFF00636F)),
)

/** A rounded dark panel with a consistent inner rhythm. */
@Composable
fun Panel(gap: Dp = 12.dp, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(shape = CARD, color = MaterialTheme.colorScheme.surface, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(gap)) { content() }
    }
}

@Composable
fun SectionTitle(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 4.dp))
}

@Composable
fun PageTitle(title: String) {
    Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
}

@Composable
fun Pill(label: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    FilterChip(
        selected = selected, onClick = onClick, enabled = enabled,
        label = { Text(label, style = MaterialTheme.typography.labelLarge) },
        shape = CircleShape, border = null,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            labelColor = Color.White,
            selectedContainerColor = Green,
            selectedLabelColor = Color.Black,
        ),
        modifier = Modifier.height(40.dp),
    )
}

@Composable
fun Banner(text: String, error: Boolean = false) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (error) Color(0xFF3A1A1A) else Color(0xFF3A2E12),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text, style = MaterialTheme.typography.bodyMedium,
            color = if (error) Color(0xFFFF9B9B) else Color(0xFFFFD58A),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

/** Back arrow on the left, screen actions on the right. */
@Composable
fun TopBar(onBack: () -> Unit, backLabel: String, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back to $backLabel", tint = Color.White) }
        Text(backLabel, style = MaterialTheme.typography.bodyLarge, color = Mist)
        Spacer(Modifier.weight(1f))
        actions()
    }
}

/** A coloured initial for a speaker, the same hue the web app gives them. */
@Composable
fun VoiceChip(name: String, size: Dp = 28.dp) {
    Box(Modifier.size(size).background(Voices.color(name), CircleShape), contentAlignment = Alignment.Center) {
        Text(
            name.removePrefix("Speaker ").take(1).uppercase(),
            style = MaterialTheme.typography.labelSmall, color = Color.Black,
        )
    }
}

// ---------- dialogs ----------

@Composable
fun PromptDialog(
    title: String, initial: String, onDismiss: () -> Unit, onDone: (String) -> Unit,
    placeholder: String = "", message: String? = null, confirm: String = "OK",
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Raised,
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (message != null) Text(message, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(text, { text = it }, singleLine = true, placeholder = { Text(placeholder) }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { onDone(text) }) { Text(confirm, color = Green) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Mist) } },
    )
}

@Composable
fun ConfirmDialog(title: String, message: String, confirm: String, destructive: Boolean, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Raised,
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        text = { if (message.isNotEmpty()) Text(message, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm, color = if (destructive) MaterialTheme.colorScheme.error else Green) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Mist) } },
    )
}

/** Existing folders, a new one, or none. Calls [onPick] with "" for no folder. */
@Composable
fun FolderDialog(current: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var folders by remember { mutableStateOf<List<String>?>(null) }
    var naming by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { folders = runCatching { Api.folders().keys.filter { it.isNotBlank() }.sorted() }.getOrDefault(emptyList()) }
    if (naming) {
        PromptDialog("New folder", "", onDismiss, { if (it.isNotBlank()) onPick(it.trim()) else onDismiss() }, placeholder = "Folder name")
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Raised,
        title = { Text("Move to folder", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column {
                (folders ?: emptyList()).forEach { f ->
                    TextButton(onClick = { onPick(f) }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (f == current) "$f  ✓" else f, color = Color.White, modifier = Modifier.fillMaxWidth())
                    }
                }
                TextButton(onClick = { naming = true }, modifier = Modifier.fillMaxWidth()) { Text("New folder…", color = Green, modifier = Modifier.fillMaxWidth()) }
                TextButton(onClick = { onPick("") }, modifier = Modifier.fillMaxWidth()) { Text("No folder", color = Mist, modifier = Modifier.fillMaxWidth()) }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Mist) } },
    )
}

// ---------- markdown (the server only writes **bold**, "- " bullets and [n] citations) ----------

object Md {
    data class Span(val text: String, val bold: Boolean = false, val cite: Int? = null)
    data class Block(val bullet: Boolean, val spans: List<Span>)

    private val BOLD = Regex("""\*\*(.+?)\*\*""")
    private val CITE = Regex("""\[(\d+)]""")
    private val BULLET = Regex("""^[-*•] """)

    fun blocks(s: String): List<Block> = s.split('\n').map { it.trim() }.filter { it.isNotEmpty() }.map { l ->
        val bullet = BULLET.containsMatchIn(l)
        Block(bullet, spans(if (bullet) l.substring(2) else l))
    }

    fun spans(line: String): List<Span> {
        val out = mutableListOf<Span>()
        var at = 0
        for (m in BOLD.findAll(line)) {
            cites(line.substring(at, m.range.first), false, out)
            cites(m.groupValues[1], true, out)
            at = m.range.last + 1
        }
        cites(line.substring(at), false, out)
        return out
    }

    private fun cites(s: String, bold: Boolean, out: MutableList<Span>) {
        var at = 0
        for (m in CITE.findAll(s)) {
            if (m.range.first > at) out += Span(s.substring(at, m.range.first), bold)
            out += Span(m.value, bold, m.groupValues[1].toInt())
            at = m.range.last + 1
        }
        if (at < s.length) out += Span(s.substring(at), bold)
    }
}

@Composable
fun MdText(text: String, onCite: ((Int) -> Unit)? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Md.blocks(text).forEach { b ->
            val ann = buildAnnotatedString {
                b.spans.forEach { sp ->
                    val cite = sp.cite
                    when {
                        cite != null && onCite != null -> withLink(
                            LinkAnnotation.Clickable("cite$cite", TextLinkStyles(SpanStyle(color = Green, fontWeight = FontWeight.Bold))) { onCite(cite) },
                        ) { append(sp.text) }
                        sp.bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(sp.text) }
                        else -> append(sp.text)
                    }
                }
            }
            if (b.bullet) Row {
                Text("•", color = Green, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(16.dp))
                Text(ann, style = MaterialTheme.typography.bodyMedium)
            } else Text(ann, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

// ---------- speaker colours (ported from the web app so a person looks the same in both) ----------

object Voices {
    private val PALETTE = intArrayOf(212, 340, 152, 28, 268, 184, 48, 4, 112, 300)
    private val NUMBERED = Regex("""^Speaker (\d+)$""")

    /** Named voices in order of first appearance, oldest note first, so a colour never changes. */
    var palette by mutableStateOf<List<String>>(emptyList())

    fun learn(notesNewestFirst: List<NoteSummary>) {
        val seen = mutableListOf<String>()
        for (n in notesNewestFirst.asReversed()) for (who in n.speakers) if (!NUMBERED.matches(who) && who !in seen) seen += who
        if (seen != palette) palette = seen
    }

    fun hue(name: String, palette: List<String>): Int {
        val i = palette.indexOf(name)
        if (i >= 0) return (PALETTE[i % 10] + (i / 10) * 12) % 360
        NUMBERED.find(name)?.let { return (PALETTE[(it.groupValues[1].toInt() - 1).mod(10)] + 18) % 360 }
        var h = 2166136261L.toInt()   // FNV-1a over UTF-16 units, as Math.imul does it
        for (c in name) { h = h xor c.code; h *= 16777619 }
        return ((h.toLong() and 0xffffffffL) % 360).toInt()
    }

    fun color(name: String): Color = Color.hsl(hue(name, palette).toFloat(), 0.75f, 0.68f)
}

// ---------- data loading ----------

fun errorText(t: Throwable): String = when (t) {
    is ApiError -> t.message ?: "Turnwise answered ${t.code}"
    is UnknownHostException, is ConnectException, is SocketTimeoutException -> "Can’t reach Turnwise. Is Tailscale on? Retrying…"
    else -> t.message ?: t.javaClass.simpleName
}

@Composable
fun rememberResumed(): Boolean {
    val state by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    return state.isAtLeast(Lifecycle.State.RESUMED)
}

class Polled<T> {
    var value by mutableStateOf<T?>(null)
    var error by mutableStateOf<String?>(null)
    var tick by mutableIntStateOf(0)
        private set
    fun refresh() { tick++ }
}

/**
 * Loads [load] for [key], then again after [every] ms while it returns a number (e.g. while transcribing).
 * Starts from the last response cached under [cacheKey], so a screen you come back to paints at once.
 * Network failures retry with backoff; server errors stop and show. Paused while the app is in the background.
 */
@Composable
fun <T> rememberPolled(key: String, cacheKey: String = key, every: (T) -> Long? = { null }, load: suspend () -> T): Polled<T> {
    @Suppress("UNCHECKED_CAST")
    val p = remember(key) { Polled<T>().also { it.value = PollCache[cacheKey] as T? } }
    val resumed = rememberResumed()
    val loader by rememberUpdatedState(load)
    val interval by rememberUpdatedState(every)
    LaunchedEffect(key, p.tick, resumed) {
        if (!resumed) return@LaunchedEffect
        var backoff = 2_000L
        while (true) {
            val next = try {
                val v = loader()
                p.value = v
                PollCache[cacheKey] = v
                p.error = null
                backoff = 2_000L
                interval(v)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                p.error = errorText(e)
                if (e is ApiError) null else backoff.also { backoff = (backoff * 2).coerceAtMost(15_000L) }
            }
            delay(next ?: return@LaunchedEffect)
        }
    }
    return p
}

// ---------- formatting ----------

val STATE_LABELS = mapOf(
    "uploading" to "Uploading", "queued" to "Queued", "recording" to "Recording", "convert" to "Converting",
    "denoise" to "Removing noise", "diarize" to "Finding speakers", "transcribe" to "Transcribing",
    "translate" to "Hinglish + English", "summarize" to "Summary + title", "done" to "", "error" to "Failed",
)
val STEPS = listOf("denoise", "diarize", "transcribe", "translate", "summarize")

fun fmtDur(ms: Long): String {
    val totalMin = ms / 60_000
    val h = totalMin / 60
    val m = totalMin % 60
    return when {
        ms < 60_000 -> "${ms / 1000}s"
        h > 0 -> "${h}h ${m}m"
        else -> "${m}m"
    }
}

fun fmtClock(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** Seconds as the web app shows turn times: 03:07, or 1:03:07 past an hour. */
fun tsLabel(sec: Double): String {
    val s = sec.toLong().coerceAtLeast(0)
    return (if (s >= 3600) "${s / 3600}:" else "") + "%02d:%02d".format(s % 3600 / 60, s % 60)
}

fun durLabel(sec: Double?): String = if (sec == null) "" else "${maxOf(1L, Math.round(sec / 60))} min"

fun elapsedLabel(sec: Double): String { val s = sec.toLong().coerceAtLeast(0); return if (s >= 60) "${s / 60}m ${s % 60}s" else "${s}s" }

fun clock(ms: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))

fun timeLabel(iso: String): String = parseCreated(iso)?.format(DateTimeFormatter.ofPattern("HH:mm")) ?: ""

fun dateTimeLabel(iso: String): String = parseCreated(iso)?.format(DateTimeFormatter.ofPattern("d MMM, HH:mm")) ?: ""

fun dayLabel(iso: String): String {
    val d = parseCreated(iso)?.toLocalDate() ?: return "Earlier"
    val today = LocalDate.now()
    return when (ChronoUnit.DAYS.between(d, today)) {
        0L -> "Today"
        1L -> "Yesterday"
        else -> d.format(DateTimeFormatter.ofPattern(if (d.year == today.year) "EEE, d MMM" else "EEE, d MMM yyyy"))
    }
}

/** "Clip_2026-10-27_1030_58min.m4a" reads as "27 Oct 10:30"; anything else is shown as named. */
fun clipTitle(name: String): String {
    val m = Regex("""(\d{4})-(\d{2})-(\d{2})_(\d{2})(\d{2})""").find(name) ?: return name.removeSuffix(".m4a")
    val (y, mo, d, h, mi) = m.destructured
    return runCatching {
        SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(SimpleDateFormat("yyyy-MM-dd HHmm", Locale.US).parse("$y-$mo-$d $h$mi")!!)
    }.getOrDefault(name.removeSuffix(".m4a"))
}

fun granted(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: ActivityNotFoundException) {
    }
}

fun shareClip(context: Context, uri: Uri) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "audio/mp4"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "Share clip"))
}
