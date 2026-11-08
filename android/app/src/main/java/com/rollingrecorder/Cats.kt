package com.rollingrecorder

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Frito (the sleek black cat, left: the watcher, upright, chin up, tail hooked) and Ramona (the big tabby,
 * right: the loaf, white chin, pink nose, belly-up sleeper), drawn in a 168 × 60 box.
 * Every mood is a different scene. docs/cats-preview.html renders the same drawing in a browser for design work.
 *
 *  CHAT       facing each other, meowing in turns, speech bubbles
 *  LISTEN     ears swivel to the sound bars; black cat wide-eyed, tabby half-lidded
 *  THINK      black cat looks up at the wall; tabby contemplates the yarn tangle; thought bubbles
 *  SLEEP      black cat curled in a loaf; tabby flat on its back, paws in the air, snoring
 *  CELEBRATE  the feather toy swings down from above: black cat rears and swats, tabby bats from the loaf
 */
enum class Mood { CHAT, LISTEN, THINK, SLEEP, CELEBRATE }

val MOOD_CAPTIONS = mapOf(
    Mood.CHAT to "Frito and Ramona, keeping each other company.",
    Mood.LISTEN to "Ears up. Frito's on watch, Ramona's unimpressed.",
    Mood.THINK to "Frito's thinking. Ramona's thinking about yarn.",
    Mood.SLEEP to "Nap time. Tap to wake them.",
    Mood.CELEBRATE to "Feather time!",
)

private val BLACK = Color(0xFF1F1F25)
private val BLACK_LIGHT = Color(0xFF50505C)
private val BLACK_DARK = Color(0xFF0A0A0D)
private val BLACK_EYE = Color(0xFFBED34A)
private val TABBY = Color(0xFF8E7D64)
private val TABBY_LIGHT = Color(0xFFC6B699)
private val TABBY_DARK = Color(0xFF4E4234)
private val STRIPE = Color(0xFF352C24)
private val TABBY_EYE = Color(0xFF9FD07E)
private val BELLY = Color(0xFFD9C7AD)
private val MUZZLE = Color(0xFFF1E9DB)
private val NOSE = Color(0xFFDC8F8C)
private val EAR_PINK = Color(0xFFC98A86)
private val INK = Color(0xFF141414)
private val BLUSH = Color(0xFFFF7A9A)
private val YARN = Color(0xFFC8323A)
private val FEATHER = Color(0xFFA9DDE3)
private val FEATHER_DARK = Color(0xFF6FB3BF)
private val CLIP = Color(0xFF2F6FD6)

/**
 * The pair. Tap them and they celebrate; they also celebrate whenever [celebrate] changes
 * (pass a counter that ticks on good news). [caption] adds a one-line description of the mood underneath.
 */
@Composable
fun Duo(mood: Mood, modifier: Modifier = Modifier, celebrate: Any? = null, caption: Boolean = false) {
    var taps by remember { mutableIntStateOf(0) }
    var party by remember { mutableStateOf(false) }
    val initial = remember { celebrate }
    LaunchedEffect(celebrate) { if (celebrate != initial) { party = true; delay(2_400); party = false } }
    LaunchedEffect(taps) { if (taps > 0) { party = true; delay(1_600); party = false } }
    val m = if (party) Mood.CELEBRATE else mood
    val t by rememberInfiniteTransition(label = "duo")
        .animateFloat(0f, 60_000f, infiniteRepeatable(tween(60_000, easing = LinearEasing)), label = "t")
    val context = LocalContext.current
    val art = remember { CatArt.load(context) }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.fillMaxWidth().aspectRatio(168f / 60f).pointerInput(Unit) { detectTapGestures { taps++ } }) {
            scale(size.width / 168f, size.height / 60f, pivot = Offset.Zero) { if (art != null) drawSprites(m, t, art) else drawDuo(m, t) }
        }
        if (caption) Text(MOOD_CAPTIONS.getValue(m), style = MaterialTheme.typography.bodySmall, color = Mist, modifier = Modifier.padding(top = 4.dp))
    }
}

// ---------- rendered sprites (assets/cats/*.png, made by ~/cats-art/gen.py) ----------

/** The generated renders, keyed "<cat>_<pose>[_blink|_meow]". Null when the assets are not in the build. */
object CatArt {
    private var cache: Map<String, ImageBitmap>? = null
    fun load(context: Context): Map<String, ImageBitmap>? {
        cache?.let { return it }
        val am = context.assets
        val names = runCatching { am.list("cats")?.filter { it.endsWith(".png") } }.getOrNull().orEmpty()
        if (names.none { it.startsWith("frito_sit") } || names.none { it.startsWith("ramona_sit") }) return null
        return names.associate { n -> n.removeSuffix(".png") to am.open("cats/$n").use { BitmapFactory.decodeStream(it).asImageBitmap() } }.also { cache = it }
    }
}

private val POSE = mapOf(Mood.CHAT to "sit", Mood.LISTEN to "alert", Mood.THINK to "think", Mood.SLEEP to "sleep", Mood.CELEBRATE to "bow")

/** A render drawn with its feet on the ground line, [h] units tall, centered on [cx]; width follows the image. */
private fun DrawScope.sprite(img: ImageBitmap, cx: Float, h: Float, flipX: Boolean = false) {
    val k = h / img.height
    val w = img.width * k
    withTransform({
        if (flipX) scale(-1f, 1f, Offset(cx, 0f))
        scale(k, k, Offset.Zero)
    }) { drawImage(img, topLeft = Offset((cx - w / 2) / k, (54f - h) / k)) }
}

/** Which variant to show now: a 130 ms blink every 4.6 s, mouth open in bursts while talking. */
private fun variant(art: Map<String, ImageBitmap>, base: String, t: Float, phase: Float, talking: Boolean): ImageBitmap? {
    val ph = ((t + phase) % 4600f) / 4600f
    if (ph > 0.95f && ph < 0.978f) art["${base}_blink"]?.let { return it }
    if (talking && ((t / 220f).toInt() % 2 == 0)) art["${base}_meow"]?.let { return it }
    return art[base]
}

private fun DrawScope.drawSprites(m: Mood, t: Float, art: Map<String, ImageBitmap>) {
    val cyc = (t % 5000f) / 5000f
    val talk1 = m == Mood.CHAT && cyc in 0.06f..0.48f
    val talk2 = m == Mood.CHAT && cyc in 0.56f..0.98f
    val pose = POSE.getValue(m) + if (m == Mood.SLEEP) "_blink" else ""   // asleep: the eyes-closed edit is the base
    val hop = if (m == Mood.CELEBRATE) -6f * abs(s(t / 650f * PI.toFloat())) else 0f
    if (m == Mood.CELEBRATE) plumes(t)
    if (m == Mood.THINK) yarnTangle()
    if (m == Mood.LISTEN) soundBars(t)
    groundShadow(32f, hop, if (m == Mood.SLEEP) 18f else 15f); groundShadow(134f, 0f, if (m == Mood.SLEEP) 26f else 22f)
    val h1 = if (m == Mood.SLEEP) 30f else 50f
    val h2 = if (m == Mood.SLEEP) 32f else 48f
    withTransform({
        translate(0f, hop + if (talk1) -1.5f * abs(s((cyc - 0.06f) / 0.12f * 2 * PI.toFloat())) else 0f)
        scale(1f, 1f + 0.015f * s(t / 900f), Offset(32f, 54f))
    }) { (variant(art, "frito_$pose", t, 0f, talk1) ?: art.getValue("frito_sit")).let { sprite(it, 32f, h1) } }
    withTransform({
        translate(0f, if (talk2) -1f * abs(s((cyc - 0.56f) / 0.12f * 2 * PI.toFloat())) else 0f)
        scale(1f, 1f + 0.012f * s(t / 1100f + 1f), Offset(134f, 54f))
    }) { (variant(art, "ramona_$pose", t, 1700f, talk2) ?: art.getValue("ramona_sit")).let { sprite(it, 134f, h2) } }
    when (m) {
        Mood.CHAT -> { bubble(52f, 4f, envelope(cyc, 0.06f, 0.10f, 0.44f, 0.48f), Offset(52f, 18f), true, t); bubble(78f, 2f, envelope(cyc, 0.56f, 0.60f, 0.94f, 0.98f), Offset(114f, 18f), false, t + 500f) }
        Mood.THINK -> { bubble(52f, 4f, 0.85f + 0.05f * s(t / 400f), Offset(52f, 18f), true, t); bubble(78f, 2f, 0.85f + 0.05f * s(t / 400f + 2f), Offset(114f, 18f), false, t + 330f) }
        Mood.SLEEP -> snores(t)
        Mood.CELEBRATE -> feather(t)
        else -> {}
    }
}

// ---------- timing helpers (t in ms) ----------

private fun s(x: Float) = sin(x.toDouble()).toFloat()
private fun popIn(x: Float): Float { val c1 = 1.70158f; val c3 = c1 + 1; val y = x - 1; return 1 + c3 * y * y * y + c1 * y * y }
private fun envelope(p: Float, a: Float, b: Float, c: Float, d: Float): Float = when {
    p < a || p > d -> 0f
    p < b -> popIn((p - a) / (b - a))
    p < c -> 1f
    else -> 1f - (p - c) / (d - c)
}
private fun blink(t: Float, offset: Float): Float {
    val ph = ((t + offset) % 4600f) / 4600f
    return if (ph < 0.92f) 1f else 1f - 0.9f * (1f - abs(ph - 0.96f) / 0.04f).coerceIn(0f, 1f)
}
private fun bez(p0: Offset, p1: Offset, p2: Offset, p3: Offset, u: Float): Offset {
    val v = 1 - u
    return Offset(v * v * v * p0.x + 3 * v * v * u * p1.x + 3 * v * u * u * p2.x + u * u * u * p3.x, v * v * v * p0.y + 3 * v * v * u * p1.y + 3 * v * u * u * p2.y + u * u * u * p3.y)
}
private fun aim(from: Offset, to: Offset): Offset { val dx = to.x - from.x; val dy = to.y - from.y; val d = hypot(dx, dy).takeIf { it > 0 } ?: 1f; return Offset(1.8f * dx / d, 1.8f * dy / d) }
private fun featherAt(t: Float): Offset { val a = 24f * s(t / 520f) * PI.toFloat() / 180f; return Offset(84f + 38f * sin(a), -4f + 38f * cos(a)) }

// ---------- the scene ----------

private fun DrawScope.drawDuo(m: Mood, t: Float) {
    val cyc = (t % 5000f) / 5000f
    val talk1 = m == Mood.CHAT && cyc in 0.06f..0.48f
    val talk2 = m == Mood.CHAT && cyc in 0.56f..0.98f
    val breathe = 1f + 0.02f * s(t / 900f)
    val hop1 = if (m == Mood.CELEBRATE) -7f * abs(s(t / 650f * PI.toFloat())) else 0f   // only the light one jumps
    fun sway(on: Boolean, start: Float) = if (on) 2f * s((cyc - start) / 0.12f * 2 * PI.toFloat()) else 0f
    fun squash(on: Boolean) = if (on) 1f + 0.04f * abs(s(t / 120f)) else if (m == Mood.CHAT) 1f else breathe
    val f = featherAt(t)
    val look1 = when { m == Mood.CELEBRATE -> aim(Offset(30f, 22f), f); talk2 -> Offset(1.2f, 0f); m == Mood.LISTEN -> Offset(0.8f, 0.8f); m == Mood.THINK -> Offset(0.4f, -1.4f); else -> Offset(0.6f, 0.3f) }
    val look2 = when { m == Mood.CELEBRATE -> aim(Offset(132f, 26f), f); talk1 -> Offset(-1.8f, 0f); m == Mood.LISTEN -> Offset(-1f, 1f); m == Mood.THINK -> Offset(-1.2f, 1.6f); else -> Offset(-0.8f, 0.3f) }
    if (m == Mood.CELEBRATE) plumes(t)
    if (m == Mood.THINK) yarnTangle()
    if (m == Mood.LISTEN) soundBars(t)
    groundShadow(30f, hop1, if (m == Mood.SLEEP) 17f else 14f); groundShadow(133f, 0f, if (m == Mood.SLEEP) 28f else 26f)
    withTransform({
        translate(0f, hop1 + if (talk1) -1.5f * abs(s((cyc - 0.06f) / 0.12f * 2 * PI.toFloat())) else 0f)
        rotate(sway(talk1, 0.06f), Offset(30f, 54f))
        scale(1f, squash(talk1), Offset(30f, 54f))
    }) { blackCat(m, t, talk1, look1) }
    withTransform({
        translate(0f, if (talk2) -1f * abs(s((cyc - 0.56f) / 0.12f * 2 * PI.toFloat())) else 0f)
        scale(1f, squash(talk2), Offset(132f, 54f))
    }) { tabby(m, t, talk2, look2) }
    when (m) {
        Mood.CHAT -> { bubble(46f, 16f, envelope(cyc, 0.06f, 0.10f, 0.44f, 0.48f), Offset(46f, 28f), true, t); bubble(76f, 14f, envelope(cyc, 0.56f, 0.60f, 0.94f, 0.98f), Offset(112f, 28f), false, t + 500f) }
        Mood.THINK -> { bubble(46f, 16f, 0.85f + 0.05f * s(t / 400f), Offset(46f, 28f), true, t); bubble(76f, 14f, 0.85f + 0.05f * s(t / 400f + 2f), Offset(112f, 28f), false, t + 330f) }
        Mood.SLEEP -> snores(t)
        Mood.CELEBRATE -> feather(t)
        else -> {}
    }
}

private fun DrawScope.groundShadow(cx: Float, hop: Float, half: Float) {
    val k = 1f + hop / 18f
    drawOval(Color.Black.copy(alpha = 0.35f * k), Offset(cx - half * k, 54.5f), Size(2 * half * k, 3.5f))
}

// ---------- the black cat: slim, upright, glossy; the watcher ----------

private fun DrawScope.blackCat(m: Mood, t: Float, talking: Boolean, look: Offset) {
    val rim = BLACK_LIGHT.copy(alpha = 0.6f)
    if (m == Mood.SLEEP) { blackCurled(t); return }
    val fur = Brush.radialGradient(listOf(BLACK_LIGHT, BLACK, BLACK_DARK), Offset(25f, 28f), 30f)
    val sw = s(t / (if (m == Mood.LISTEN) 320f else 900f)) * (if (m == Mood.THINK) 7f else 5f)
    val tail = Path().apply {
        if (m == Mood.CELEBRATE) { val q = 1.5f * s(t / 90f); moveTo(40f, 52f); cubicTo(48f, 52f, 50f + q, 40f, 47f + q, 30f); cubicTo(46f + q, 26f, 50f + q, 24f, 52f + q, 27f) }   // straight up, quivering: the greeting
        else { moveTo(40f, 52f); cubicTo(52f, 54f, 55f + sw, 42f, 49f + sw * 0.6f, 33f); cubicTo(47f + sw * 0.6f, 29f, 51f + sw * 0.6f, 27f, 53f + sw * 0.6f, 30f) }
    }
    drawPath(tail, BLACK, style = Stroke(3.6f, cap = StrokeCap.Round)); drawPath(tail, rim, style = Stroke(0.8f, cap = StrokeCap.Round))
    val body = Path().apply { moveTo(18f, 54f); cubicTo(16f, 45f, 21f, 36f, 30f, 35f); cubicTo(39f, 36f, 44f, 45f, 42f, 54f); close() }
    drawPath(body, fur); drawPath(body, rim, style = Stroke(0.8f))
    drawPath(Path().apply { moveTo(37f, 39f); quadraticTo(42f, 44f, 41f, 51f) }, Color.White.copy(alpha = 0.12f), style = Stroke(1.2f, cap = StrokeCap.Round))
    for (px in listOf(22f, 31f)) drawOval(rim, Offset(px, 51.5f), Size(7f, 3f), style = Stroke(0.7f))
    if (m == Mood.CELEBRATE) {   // a front paw up, swatting at the feather
        val r = 4f * maxOf(0f, s(t / 260f)); val paw = Offset(45f + r, 35f - r * 0.8f)
        drawLine(BLACK, Offset(37f, 47f), paw, 4f, StrokeCap.Round); drawLine(rim, Offset(37f, 47f), paw, 0.8f, StrokeCap.Round)
        drawCircle(BLACK, 2.4f, paw); drawCircle(rim, 2.4f, paw, style = Stroke(0.8f))
    }
    val tilt = if (m == Mood.THINK) -10f else if (talking) -4f * abs(s(t / 150f)) else 0f   // chin up at the wall while thinking
    rotate(tilt, Offset(30f, 34f)) {
        val hy = 23f
        ears(Offset(30f, hy), 11f, m, t, fur, EAR_PINK.copy(alpha = 0.8f), rim, 1f)
        drawOval(fur, Offset(17f, hy - 11f), Size(26f, 22f)); drawOval(rim, Offset(17f, hy - 11f), Size(26f, 22f), style = Stroke(0.8f))
        drawOval(Color.White.copy(alpha = 0.16f), Offset(21f, hy - 8f), Size(7f, 3.5f))
        cheeks(listOf(Offset(21.5f, hy + 3.5f), Offset(38.5f, hy + 3.5f)), m, talking, 0.35f)
        val style = if (m == Mood.CELEBRATE || m == Mood.LISTEN) Eye.WIDE else Eye.OPEN
        val lk = if (m == Mood.THINK) Offset(look.x, -1.6f) else look
        catEye(Offset(24.5f, hy - 0.5f), 4.2f, BLACK_EYE, style, blink(t, 0f), lk, BLACK_LIGHT)
        catEye(Offset(35.5f, hy - 0.5f), 4.2f, BLACK_EYE, style, blink(t, 0f), lk, BLACK_LIGHT)
        nose(Offset(30f, hy + 4.6f), BLACK_LIGHT, 0.8f)
        catMouth(Offset(30f, hy + 5.6f), m, t, talking, BLACK_LIGHT, 0.9f)
        whiskers(Offset(30f, hy + 4.6f), Color.White.copy(alpha = 0.3f), 10f)
    }
}

/** Asleep: a tight loaf on the sill, tail wrapped round the front. */
private fun DrawScope.blackCurled(t: Float) {
    val rim = BLACK_LIGHT.copy(alpha = 0.6f); val br = 0.6f * s(t / 1100f)
    val fur = Brush.radialGradient(listOf(BLACK_LIGHT, BLACK, BLACK_DARK), Offset(26f, 44f), 22f)
    drawOval(fur, Offset(14f, 40f - br), Size(32f, 15f + br)); drawOval(rim, Offset(14f, 40f - br), Size(32f, 15f + br), style = Stroke(0.8f))
    val tail = Path().apply { moveTo(44f, 50f); quadraticTo(40f, 56f, 22f, 54.5f); quadraticTo(16f, 54f, 15f, 51f) }
    drawPath(tail, BLACK, style = Stroke(3f, cap = StrokeCap.Round)); drawPath(tail, rim, style = Stroke(0.7f, cap = StrokeCap.Round))
    ears(Offset(23f, 44f), 8f, Mood.SLEEP, t, fur, EAR_PINK.copy(alpha = 0.7f), rim, 1f)
    drawOval(fur, Offset(13.5f, 37.5f), Size(19f, 15f)); drawOval(rim, Offset(13.5f, 37.5f), Size(19f, 15f), style = Stroke(0.8f))
    catEye(Offset(19f, 44.5f), 2.8f, BLACK_EYE, Eye.SHUT, 1f, Offset.Zero, BLACK_LIGHT); catEye(Offset(27f, 44.5f), 2.8f, BLACK_EYE, Eye.SHUT, 1f, Offset.Zero, BLACK_LIGHT)
    nose(Offset(23f, 48f), BLACK_LIGHT, 0.7f); whiskers(Offset(23f, 48f), Color.White.copy(alpha = 0.25f), 7f)
}

// ---------- the tabby: round and heavy, mackerel stripes, white chin; the loaf ----------

private fun DrawScope.tabby(m: Mood, t: Float, talking: Boolean, look: Offset) {
    if (m == Mood.SLEEP) { tabbySprawl(t); return }
    val fur = Brush.radialGradient(listOf(TABBY_LIGHT, TABBY, TABBY_DARK), Offset(124f, 34f), 42f)
    val sw = s(t / (if (m == Mood.LISTEN) 320f else 1100f)) * 5f
    val p0 = Offset(158f, 47f); val p1 = Offset(168f, 53f); val p2 = Offset(158f + sw * 0.3f, 59.5f); val p3 = Offset(138f + sw, 58f)
    val tail = Path().apply { moveTo(p0.x, p0.y); cubicTo(p1.x, p1.y, p2.x, p2.y, p3.x, p3.y) }
    drawPath(tail, TABBY_DARK, style = Stroke(4.6f, cap = StrokeCap.Round)); drawPath(tail, TABBY, style = Stroke(2.8f, cap = StrokeCap.Round))
    for (u in listOf(0.35f, 0.6f, 0.85f)) drawCircle(STRIPE.copy(alpha = 0.7f), 1.6f, bez(p0, p1, p2, p3, u), style = Stroke(0.9f))   // tail rings
    val body = Path().apply { moveTo(104f, 55f); cubicTo(101f, 41f, 112f, 33f, 132f, 33f); cubicTo(152f, 33f, 163f, 41f, 160f, 55f); close() }
    drawPath(body, fur)
    for (i in 0..5) { val x = 110f + i * 9f; drawPath(Path().apply { moveTo(x, 39f); quadraticTo(x + 3f, 46f, x - 0.5f, 53f) }, STRIPE.copy(alpha = 0.55f), style = Stroke(1.8f, cap = StrokeCap.Round)) }
    drawOval(TABBY_LIGHT.copy(alpha = 0.4f), Offset(122f, 44f), Size(20f, 11f))
    val bat = if (m == Mood.CELEBRATE) maxOf(0f, s(t / 180f)) else 0f   // near paw bats at the feather
    paw(Offset(134f, 52f))
    rotate(-30f * bat, Offset(128f, 54f)) { paw(Offset(120f, 52f)) }
    val hy = 26f; val turn = if (talking) -3f * abs(s(t / 160f)) else 0f
    rotate(turn, Offset(132f, 36f)) {
        ears(Offset(132f, hy), 13f, m, t, fur, EAR_PINK, null, -1f)
        drawOval(fur, Offset(115f, hy - 13.5f), Size(34f, 27f))
        for (dx in listOf(-5f, 0f, 5f)) drawLine(STRIPE.copy(alpha = 0.75f), Offset(132f + dx, hy - 12f), Offset(132f + dx * 0.75f, hy - 6.5f), 1.5f, StrokeCap.Round)
        drawLine(STRIPE.copy(alpha = 0.6f), Offset(117.5f, hy - 1f), Offset(121f, hy + 1.5f), 1.2f, StrokeCap.Round); drawLine(STRIPE.copy(alpha = 0.6f), Offset(146.5f, hy - 1f), Offset(143f, hy + 1.5f), 1.2f, StrokeCap.Round)
        drawOval(MUZZLE, Offset(123.5f, hy + 2.5f), Size(17f, 9.5f))
        cheeks(listOf(Offset(119.5f, hy + 5.5f), Offset(144.5f, hy + 5.5f)), m, talking, 0.5f)
        val style = if (m == Mood.CELEBRATE) Eye.WIDE else Eye.OPEN
        val open = (if (m == Mood.LISTEN) 0.55f else 1f) * blink(t, 1700f)   // the half-lidded stare
        catEye(Offset(124.5f, hy - 1.5f), 4.6f, TABBY_EYE, style, open, look, STRIPE); catEye(Offset(139.5f, hy - 1.5f), 4.6f, TABBY_EYE, style, open, look, STRIPE)
        nose(Offset(132f, hy + 5f), NOSE, 0.9f); catMouth(Offset(132f, hy + 6.4f), m, t, talking, STRIPE, 1.1f); whiskers(Offset(132f, hy + 5.5f), Color.White.copy(alpha = 0.7f), 17f)
    }
}

private fun DrawScope.paw(c: Offset) {
    drawOval(TABBY_LIGHT.copy(alpha = 0.8f), Offset(c.x - 5f, c.y - 2f), Size(10f, 4f))
    for (dx in listOf(-1.7f, 1.7f)) drawLine(STRIPE.copy(alpha = 0.45f), Offset(c.x + dx, c.y + 0.2f), Offset(c.x + dx, c.y + 1.6f), 0.7f, StrokeCap.Round)
}

/** Asleep: flat on the back, belly up, paws in the air, one leg stretched out. */
private fun DrawScope.tabbySprawl(t: Float) {
    val fur = Brush.radialGradient(listOf(TABBY_LIGHT, TABBY, TABBY_DARK), Offset(130f, 42f), 40f); val br = 0.5f * s(t / 1100f)
    drawLine(TABBY_DARK, Offset(154f, 47f), Offset(165f, 41f), 4.5f, StrokeCap.Round); drawLine(TABBY, Offset(154f, 47f), Offset(165f, 41f), 2.8f, StrokeCap.Round)
    val body = Path().apply { moveTo(112f, 54.5f); cubicTo(110f, 38f - br, 126f, 35f - br, 138f, 36f - br); cubicTo(152f, 37f - br, 162f, 43f, 160f, 54.5f); close() }
    drawPath(body, fur)
    for (i in 0..4) { val x = 118f + i * 8f; drawPath(Path().apply { moveTo(x, 37f); quadraticTo(x + 2f, 42f, x + 1f, 46f) }, STRIPE.copy(alpha = 0.55f), style = Stroke(1.6f, cap = StrokeCap.Round)) }
    drawOval(BELLY.copy(alpha = 0.7f), Offset(122f, 42f), Size(32f, 12f))
    for ((x0, x1, ph) in listOf(Triple(128f, 125f, 0f), Triple(140f, 144f, 1.2f))) {
        val w = 1.5f * s(t / 700f + ph)
        drawLine(TABBY_DARK, Offset(x0, 41f), Offset(x1 + w, 31f), 4f, StrokeCap.Round); drawLine(TABBY, Offset(x0, 41f), Offset(x1 + w, 31f), 2.4f, StrokeCap.Round)
        drawCircle(TABBY_LIGHT.copy(alpha = 0.8f), 1.9f, Offset(x1 + w, 31f))
    }
    val hx = 109f; val hy = 45f
    rotate(-25f, Offset(hx, hy)) {
        ears(Offset(hx, hy), 10f, Mood.SLEEP, t, fur, EAR_PINK, null, 1f)
        drawOval(fur, Offset(hx - 12f, hy - 9.5f), Size(24f, 19f))
        for (dx in listOf(-3.5f, 0f, 3.5f)) drawLine(STRIPE.copy(alpha = 0.7f), Offset(hx + dx, hy - 9f), Offset(hx + dx * 0.75f, hy - 4.5f), 1.2f, StrokeCap.Round)
        drawOval(MUZZLE, Offset(hx - 6f, hy + 1.5f), Size(12f, 7f))
        cheeks(listOf(Offset(hx - 8.5f, hy + 3.5f), Offset(hx + 8.5f, hy + 3.5f)), Mood.SLEEP, false, 0.4f)
        catEye(Offset(hx - 5f, hy - 1f), 3.2f, TABBY_EYE, Eye.SHUT, 1f, Offset.Zero, STRIPE); catEye(Offset(hx + 5f, hy - 1f), 3.2f, TABBY_EYE, Eye.SHUT, 1f, Offset.Zero, STRIPE)
        nose(Offset(hx, hy + 3.4f), NOSE, 0.8f); whiskers(Offset(hx, hy + 3.8f), Color.White.copy(alpha = 0.55f), 11f)
    }
}

// ---------- props ----------

/** The red yarn mess on the rug that the tabby contemplates. */
private fun DrawScope.yarnTangle() {
    val p = Path().apply { moveTo(98f, 55f); cubicTo(104f, 48f, 110f, 58f, 106f, 51f); cubicTo(103f, 46f, 112f, 47f, 110f, 53f); cubicTo(109f, 57f, 100f, 56f, 104f, 50f); cubicTo(107f, 46f, 114f, 52f, 112f, 56f) }
    drawPath(p, YARN, style = Stroke(1.2f, cap = StrokeCap.Round))
    drawPath(Path().apply { moveTo(112f, 56f); quadraticTo(122f, 58f, 128f, 55f) }, YARN.copy(alpha = 0.8f), style = Stroke(1f, cap = StrokeCap.Round))
}

/** The feather toy on its string, swung from a hand above the picture. */
private fun DrawScope.feather(t: Float) {
    val a = 24f * s(t / 520f); val px = 84f; val py = -4f
    rotate(a, Offset(px, py)) {
        drawLine(Color.White.copy(alpha = 0.45f), Offset(px, py), Offset(px, 33f), 0.7f, StrokeCap.Round)
        for ((ang, len) in listOf(-34f to 15f, -11f to 18f, 11f to 18f, 34f to 15f)) rotate(ang + 3f * s(t / 300f), Offset(px, 33f)) {
            drawOval(FEATHER, Offset(px - 1.6f, 33f), Size(3.2f, len))
            drawLine(FEATHER_DARK.copy(alpha = 0.9f), Offset(px, 33.5f), Offset(px, 33f + len - 1f), 0.45f, StrokeCap.Round)
            var k = 4f
            while (k < len - 2f) { drawLine(Color.White.copy(alpha = 0.35f), Offset(px - 1.4f, 33f + k), Offset(px + 1.4f, 33f + k + 0.8f), 0.3f, StrokeCap.Round); k += 3f }
        }
        drawCircle(CLIP, 1.7f, Offset(px, 33f))
    }
}

/** Loose bits of feather drifting down. */
private fun DrawScope.plumes(t: Float) {
    for (i in 0 until 8) {
        val p = ((t / 1600f) + i * 0.137f) % 1f
        val x = (i * 41f + 23f) % 168f + 5f * s(t / 400f + i); val y = -6f + 70f * p
        rotate(t / 6f + i * 45f, Offset(x, y)) { drawOval(FEATHER.copy(alpha = 0.9f - p * 0.6f), Offset(x - 1.2f, y - 2.6f), Size(2.4f, 5.2f)) }
    }
}

// ---------- shared parts ----------

private enum class Eye { OPEN, WIDE, SHUT, HAPPY }

/** Two ears behind the head. [toward] is +1 when the middle of the picture is to this cat's right. */
private fun DrawScope.ears(c: Offset, r: Float, m: Mood, t: Float, fur: Brush, inner: Color, rim: Color?, toward: Float) {
    val up = if (m == Mood.LISTEN) 3f else if (m == Mood.SLEEP) -2.5f else 0f
    val swivel = if (m == Mood.LISTEN) 10f + 6f * s(t / 700f) else 0f                                  // the ear nearest the sound turns to it
    val twitch = if (m == Mood.LISTEN) 8f * maxOf(0f, s(t / 260f)) * maxOf(0f, s(t / 1900f)) else 0f
    fun ear(side: Float, tilt: Float) {
        val bx = c.x + side * r * 0.72f; val by = c.y - r * 0.35f
        rotate(side * tilt, Offset(bx, by)) {
            val p = Path().apply { moveTo(bx - side * r * 0.1f, by + r * 0.1f); lineTo(bx + side * r * 0.3f, by - r * 1.35f - up); lineTo(bx + side * r * 0.55f, by + r * 0.15f); close() }
            val q = Path().apply { moveTo(bx + side * r * 0.06f, by + r * 0.05f); lineTo(bx + side * r * 0.3f, by - r * 0.95f - up); lineTo(bx + side * r * 0.42f, by + r * 0.08f); close() }
            drawPath(p, fur); if (rim != null) drawPath(p, rim, style = Stroke(0.8f)); drawPath(q, inner)
        }
    }
    ear(-toward, if (m == Mood.THINK) 14f else twitch)
    ear(toward, if (m == Mood.SLEEP) 6f else swivel)
}

private fun DrawScope.cheeks(at: List<Offset>, m: Mood, talking: Boolean, k: Float) {
    val a = k * (if (talking || m == Mood.CELEBRATE) 1f else 0.6f)
    at.forEach { drawOval(BLUSH.copy(alpha = a), Offset(it.x - 3f, it.y - 1.6f), Size(6f, 3.2f)) }
}

/** A big almond cat eye with a double catchlight; pupils go round and wide when something is happening. */
private fun DrawScope.catEye(c: Offset, r: Float, iris: Color, style: Eye, open: Float, look: Offset, lid: Color) {
    when (style) {
        Eye.SHUT -> { drawArc(lid, 20f, 140f, false, Offset(c.x - r * 0.8f, c.y - r * 0.6f), Size(r * 1.6f, r * 1.0f), style = Stroke(1.3f, cap = StrokeCap.Round)); return }
        Eye.HAPPY -> { drawArc(lid, 200f, 140f, false, Offset(c.x - r * 0.8f, c.y - r * 0.2f), Size(r * 1.6f, r * 1.2f), style = Stroke(1.5f, cap = StrokeCap.Round)); return }
        else -> {}
    }
    val h = r * 0.9f * open
    val al = Path().apply { moveTo(c.x - r, c.y + h * 0.1f); quadraticTo(c.x, c.y - h * 2.1f, c.x + r, c.y + h * 0.1f); quadraticTo(c.x, c.y + h * 1.7f, c.x - r, c.y + h * 0.1f); close() }
    drawPath(al, iris); drawPath(al, lid.copy(alpha = 0.7f), style = Stroke(0.6f))
    if (open > 0.35f) {
        val wide = style == Eye.WIDE
        val pw = if (wide) r * 1.0f else r * 0.62f; val ph = if (wide) h * 0.9f else h * 0.8f
        val pc = Offset(c.x + look.x * 0.6f, c.y + look.y * 0.4f)
        drawOval(INK, Offset(pc.x - pw / 2, pc.y - ph), Size(pw, 2 * ph))
        drawCircle(Color.White.copy(alpha = 0.95f), r * 0.26f, Offset(pc.x - r * 0.25f, pc.y - h * 0.4f))
        drawCircle(Color.White.copy(alpha = 0.7f), r * 0.12f, Offset(pc.x + r * 0.22f, pc.y + h * 0.3f))
    }
}

private fun DrawScope.nose(c: Offset, color: Color, k: Float) {
    drawPath(Path().apply { moveTo(c.x - 1.7f * k, c.y - 1f * k); lineTo(c.x + 1.7f * k, c.y - 1f * k); lineTo(c.x, c.y + 1.3f * k); close() }, color)
}

/** The little "w" under the nose; opens into a meow while talking, widens into a smile when celebrating. */
private fun DrawScope.catMouth(c: Offset, m: Mood, t: Float, talking: Boolean, color: Color, w: Float) {
    val k = 1.6f * w
    if (talking) { val h = 1.2f + 2f * abs(s(t / 110f)); drawOval(color, Offset(c.x - k * 0.6f, c.y + 0.5f), Size(k * 1.2f, h)); return }
    val p = Path()
    if (m == Mood.CELEBRATE) { p.moveTo(c.x - k * 2.2f, c.y); p.quadraticTo(c.x - k, c.y + 2.4f, c.x, c.y + 0.8f); p.quadraticTo(c.x + k, c.y + 2.4f, c.x + k * 2.2f, c.y); drawPath(p, color, style = Stroke(1.1f, cap = StrokeCap.Round)) }
    else { p.moveTo(c.x - k * 2f, c.y + 0.6f); p.quadraticTo(c.x - k, c.y + 1.8f, c.x, c.y); p.quadraticTo(c.x + k, c.y + 1.8f, c.x + k * 2f, c.y + 0.6f); drawPath(p, color, style = Stroke(0.9f, cap = StrokeCap.Round)) }
}

private fun DrawScope.whiskers(c: Offset, color: Color, len: Float) {
    for (side in listOf(-1f, 1f)) for ((i, dy) in listOf(-2.5f, 0f, 2.5f).withIndex()) {
        drawLine(color, Offset(c.x + side * 4f, c.y - 1f + i), Offset(c.x + side * len, c.y + dy), 0.55f, StrokeCap.Round)
    }
}

/** A speech bubble with three bouncing dots, scaled from its tail. */
private fun DrawScope.bubble(x: Float, y: Float, k: Float, pivot: Offset, left: Boolean, t: Float) {
    if (k <= 0.01f) return
    scale(k, k, pivot) {
        val tail = Path().apply {
            if (left) { moveTo(x + 6f, y + 18f); lineTo(x + 1f, y + 26f); lineTo(x + 12f, y + 21f) }
            else { moveTo(x + 30f, y + 18f); lineTo(x + 35f, y + 26f); lineTo(x + 24f, y + 21f) }
            close()
        }
        drawRoundRect(Color.Black.copy(alpha = 0.3f), Offset(x + 1f, y + 2f), Size(36f, 20f), CornerRadius(10f))
        drawPath(tail, Raised)
        drawRoundRect(Raised, Offset(x, y), Size(36f, 20f), CornerRadius(10f))
        drawRoundRect(Color.White.copy(alpha = 0.12f), Offset(x, y), Size(36f, 20f), CornerRadius(10f), style = Stroke(0.8f))
        for (i in 0..2) {
            val dy = -2f * maxOf(0f, s(t / 1000f * 2 * PI.toFloat() - i * 0.9f))
            drawCircle(if (left) BLACK_EYE else TABBY_EYE, 2.2f, Offset(x + 10f + i * 8f, y + 10f + dy))
        }
    }
}

/** An equalizer between them: they are hearing something. */
private fun DrawScope.soundBars(t: Float) {
    for (i in 0..6) {
        val h = 3f + 9f * abs(s(t / (170f + i * 23f) + i))
        drawRoundRect(Color.White.copy(alpha = 0.55f), Offset(68f + i * 5f, 54f - h), Size(2.6f, h), CornerRadius(1.3f))
    }
}

/** Floating z's over the sprawled tabby. */
private fun DrawScope.snores(t: Float) {
    for (k in 0..2) {
        val p = ((t / 2600f) + k / 3f) % 1f
        val size = 3f + 3.5f * p; val x = 118f + 14f * p; val y = 30f - 20f * p
        val z = Path().apply { moveTo(x, y); lineTo(x + size, y); lineTo(x, y + size); lineTo(x + size, y + size) }
        drawPath(z, Mist.copy(alpha = 1f - p), style = Stroke(1.2f, cap = StrokeCap.Round))
    }
}
