package com.rollingrecorder

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Blip (the horned green blob) and Bolt (the one-eyed orange antenna bot), the two voices from the
 * Turnwise web app, redrawn with depth and given moods that follow what the app is doing.
 */
enum class Mood { CHAT, LISTEN, THINK, SLEEP, CELEBRATE }

private val BLIP = Color(0xFF1DB954)
private val BLIP_LIGHT = Color(0xFF8CF7B4)
private val BLIP_DARK = Color(0xFF0A5E2A)
private val BOLT = Color(0xFFF68B35)
private val BOLT_LIGHT = Color(0xFFFFD0A0)
private val BOLT_DARK = Color(0xFFA9501A)
private val INK = Color(0xFF141414)
private val BLUSH = Color(0xFFFF7A9A)
private val CONFETTI = listOf(BLIP, BOLT, Color(0xFF7C9CFF), Color(0xFFE056FD), Color(0xFFFFD54F))

/**
 * The pair, 168 × 60 units like the web SVG. Tap them and they celebrate; they also celebrate
 * whenever [celebrate] changes (pass a counter that ticks on good news).
 */
@Composable
fun Duo(mood: Mood, modifier: Modifier = Modifier, celebrate: Any? = null) {
    var taps by remember { mutableIntStateOf(0) }
    var party by remember { mutableStateOf(false) }
    val initial = remember { celebrate }
    LaunchedEffect(celebrate) { if (celebrate != initial) { party = true; delay(2_400); party = false } }
    LaunchedEffect(taps) { if (taps > 0) { party = true; delay(1_600); party = false } }
    val m = if (party) Mood.CELEBRATE else mood
    val t by rememberInfiniteTransition(label = "duo")
        .animateFloat(0f, 60_000f, infiniteRepeatable(tween(60_000, easing = LinearEasing)), label = "t")
    Canvas(modifier.aspectRatio(168f / 60f).pointerInput(Unit) { detectTapGestures { taps++ } }) {
        scale(size.width / 168f, size.height / 60f, pivot = Offset.Zero) { drawDuo(m, t) }
    }
}

// ---------- timing helpers (t in ms) ----------

private fun s(x: Float) = sin(x.toDouble()).toFloat()

/** easeOutBack: a bubble pops slightly past full size, then settles. */
private fun popIn(x: Float): Float { val c1 = 1.70158f; val c3 = c1 + 1; val y = x - 1; return 1 + c3 * y * y * y + c1 * y * y }

/** 0 → pop to 1 → hold → shrink to 0 across the cycle fractions a..d. */
private fun envelope(p: Float, a: Float, b: Float, c: Float, d: Float): Float = when {
    p < a || p > d -> 0f
    p < b -> popIn((p - a) / (b - a))
    p < c -> 1f
    else -> 1f - (p - c) / (d - c)
}

/** 1 = open, down to 0.1 = shut, a quick blink every 4.6 s. */
private fun blink(t: Float, offset: Float): Float {
    val ph = ((t + offset) % 4600f) / 4600f
    return if (ph < 0.92f) 1f else 1f - 0.9f * (1f - abs(ph - 0.96f) / 0.04f).coerceIn(0f, 1f)
}

// ---------- drawing ----------

private fun DrawScope.drawDuo(m: Mood, t: Float) {
    val cyc = (t % 5000f) / 5000f
    val talk1 = m == Mood.CHAT && cyc in 0.06f..0.48f
    val talk2 = m == Mood.CHAT && cyc in 0.56f..0.98f
    val breathe = 1f + 0.025f * s(t / 900f)
    fun hop(off: Float) = if (m == Mood.CELEBRATE) -7f * abs(s((t + off) / 650f * PI.toFloat())) else 0f
    fun sway(on: Boolean, start: Float) = if (on) 3f * s((cyc - start) / 0.12f * 2 * PI.toFloat()) else 0f
    fun squash(on: Boolean) = if (on) 1f + 0.05f * abs(s(t / 120f)) else if (m == Mood.CHAT) 1f else breathe

    // Where the eyes point: at whoever is talking, down at the sound, up while thinking.
    val look1 = when { talk2 -> Offset(1.2f, 0f); m == Mood.LISTEN -> Offset(0.8f, 0.8f); m == Mood.THINK -> Offset(0.4f, -1.4f); else -> Offset(0.6f, 0.3f) }
    val look2 = when { talk1 -> Offset(-1.8f, 0f); m == Mood.LISTEN -> Offset(-1f, 1f); m == Mood.THINK -> Offset(-0.5f, -2f); else -> Offset(-0.8f, 0.3f) }

    if (m == Mood.CELEBRATE) confetti(t)
    if (m == Mood.LISTEN) soundBars(t)

    val h1 = hop(0f); val h2 = hop(325f)
    groundShadow(30f, h1); groundShadow(132f, h2)

    withTransform({
        translate(0f, h1 + if (talk1) -1.5f * abs(s((cyc - 0.06f) / 0.12f * 2 * PI.toFloat())) else 0f)
        rotate(sway(talk1, 0.06f), Offset(30f, 54f))
        scale(1f, squash(talk1), Offset(30f, 54f))
    }) { blip(m, t, talk1, look1) }

    withTransform({
        translate(0f, h2 + if (talk2) -1.5f * abs(s((cyc - 0.56f) / 0.12f * 2 * PI.toFloat())) else 0f)
        rotate(sway(talk2, 0.56f), Offset(132f, 54f))
        scale(1f, squash(talk2), Offset(132f, 54f))
    }) { bolt(m, t, talk2, look2) }

    when (m) {
        Mood.CHAT -> {
            bubble(46f, 18f, envelope(cyc, 0.06f, 0.10f, 0.44f, 0.48f), Offset(46f, 30f), left = true, t = t)
            bubble(84f, 16f, envelope(cyc, 0.56f, 0.60f, 0.94f, 0.98f), Offset(120f, 30f), left = false, t = t + 500f)
        }
        Mood.THINK -> {
            bubble(46f, 18f, 0.85f + 0.05f * s(t / 400f), Offset(46f, 30f), left = true, t = t)
            bubble(84f, 16f, 0.85f + 0.05f * s(t / 400f + 2f), Offset(120f, 30f), left = false, t = t + 330f)
        }
        Mood.SLEEP -> snores(t)
        else -> {}
    }
}

private fun DrawScope.groundShadow(cx: Float, hop: Float) {
    val k = 1f + hop / 18f   // smaller and fainter while in the air
    drawOval(Color.Black.copy(alpha = 0.35f * k), Offset(cx - 17f * k, 54.5f), Size(34f * k, 3.5f))
}

private fun DrawScope.blip(m: Mood, t: Float, talking: Boolean, look: Offset) {
    val body = Path().apply {
        moveTo(12f, 54f)
        cubicTo(10f, 40f, 16f, 28f, 30f, 28f)
        cubicTo(44f, 28f, 50f, 40f, 48f, 54f)
        close()
    }
    val skin = Brush.radialGradient(listOf(BLIP_LIGHT, BLIP, BLIP_DARK), center = Offset(23f, 33f), radius = 28f)
    val horns = Path().apply {
        moveTo(22f, 30f); lineTo(25f, 20f); lineTo(30f, 29f); close()
        moveTo(34f, 29f); lineTo(39f, 20f); lineTo(42f, 30f); close()
    }
    drawPath(horns, Brush.verticalGradient(listOf(BLIP_LIGHT, BLIP_DARK), 18f, 31f))
    drawPath(body, skin)
    drawOval(Color.White.copy(alpha = 0.35f), Offset(18f, 32f), Size(6f, 3.5f))       // shine
    cheeks(listOf(Offset(18.5f, 46f), Offset(41.5f, 46f)), m, talking)
    for (cx in listOf(24f, 36f)) eye(Offset(cx, 40f), 4f, 1.8f, m, blink(t, 0f), look)
    mouth(Offset(30f, 49f), 5f, m, t, talking)
}

private fun DrawScope.bolt(m: Mood, t: Float, talking: Boolean, look: Offset) {
    val lit = talking || m == Mood.LISTEN || m == Mood.THINK || m == Mood.CELEBRATE
    val pulse = 0.5f + 0.5f * s(t / (if (m == Mood.THINK) 180f else 300f))
    if (m == Mood.LISTEN) for (k in 0..1) {   // radio rings: it is picking up sound
        val p = ((t / 1400f) + k * 0.5f) % 1f
        drawCircle(BOLT_LIGHT.copy(alpha = (1 - p) * 0.7f), 5f + 13f * p, Offset(132f, 14f), style = Stroke(1.2f))
    }
    if (lit) drawCircle(Brush.radialGradient(listOf(BOLT_LIGHT.copy(alpha = 0.8f * pulse), Color.Transparent), Offset(132f, 14f), 10f), 10f, Offset(132f, 14f))
    drawRect(BOLT_DARK, Offset(131f, 14f), Size(2f, 8f))
    drawCircle(if (lit) BOLT_LIGHT else BOLT, 4f, Offset(132f, 14f))
    drawRoundRect(
        Brush.radialGradient(listOf(BOLT_LIGHT, BOLT, BOLT_DARK), center = Offset(123f, 27f), radius = 34f),
        Offset(114f, 20f), Size(36f, 34f), CornerRadius(15f),
    )
    drawOval(Color.White.copy(alpha = 0.35f), Offset(118f, 24f), Size(7f, 4f))           // shine
    cheeks(listOf(Offset(120.5f, 45f), Offset(143.5f, 45f)), m, talking)
    eye(Offset(132f, 36f), 7.5f, 3f, m, blink(t, 1700f), Offset(look.x * 1.5f, look.y * 1.5f))
    mouth(Offset(132f, 48f), 8f, m, t, talking)
}

private fun DrawScope.cheeks(at: List<Offset>, m: Mood, talking: Boolean) {
    val a = if (talking || m == Mood.CELEBRATE) 0.55f else 0.25f
    at.forEach { drawCircle(BLUSH.copy(alpha = a), 2.6f, it) }
}

private fun DrawScope.eye(c: Offset, r: Float, pupil: Float, m: Mood, open: Float, look: Offset) {
    when (m) {
        Mood.SLEEP -> drawArc(INK, 20f, 140f, false, Offset(c.x - r * 0.8f, c.y - r * 0.6f), Size(r * 1.6f, r * 1.0f), style = Stroke(1.4f, cap = StrokeCap.Round))
        Mood.CELEBRATE -> drawArc(INK, 200f, 140f, false, Offset(c.x - r * 0.8f, c.y - r * 0.2f), Size(r * 1.6f, r * 1.2f), style = Stroke(1.6f, cap = StrokeCap.Round))
        else -> {
            val wide = if (m == Mood.LISTEN) 1.1f else 1f
            drawOval(Color.White, Offset(c.x - r * wide, c.y - r * open * wide), Size(2 * r * wide, 2 * r * open * wide))
            if (open > 0.35f) {
                val pc = Offset(c.x + look.x, c.y + look.y)
                drawCircle(INK, pupil, pc)
                drawCircle(Color.White, pupil * 0.35f, Offset(pc.x - pupil * 0.35f, pc.y - pupil * 0.35f))   // catchlight
            }
        }
    }
}

private fun DrawScope.mouth(c: Offset, w: Float, m: Mood, t: Float, talking: Boolean) {
    when {
        talking -> { val h = 1.5f + 2.2f * abs(s(t / 110f)); drawOval(INK, Offset(c.x - w * 0.35f, c.y - h / 2), Size(w * 0.7f, h)) }
        m == Mood.CELEBRATE -> drawArc(INK, 0f, 180f, true, Offset(c.x - w * 0.5f, c.y - w * 0.35f), Size(w, w * 0.8f))
        m == Mood.THINK -> drawLine(INK, Offset(c.x - w * 0.3f, c.y), Offset(c.x + w * 0.3f, c.y + 0.6f), 1.4f, StrokeCap.Round)
        m == Mood.LISTEN || m == Mood.SLEEP -> drawCircle(INK, 1.2f, c)
        else -> drawPath(Path().apply { moveTo(c.x - w * 0.5f, c.y - 1f); quadraticTo(c.x, c.y + 3f, c.x + w * 0.5f, c.y - 1f) }, INK, style = Stroke(1.6f, cap = StrokeCap.Round))
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
        drawRoundRect(Color.Black.copy(alpha = 0.3f), Offset(x + 1f, y + 2f), Size(36f, 20f), CornerRadius(10f))   // drop shadow
        drawPath(tail, Raised)
        drawRoundRect(Raised, Offset(x, y), Size(36f, 20f), CornerRadius(10f))
        drawRoundRect(Color.White.copy(alpha = 0.12f), Offset(x, y), Size(36f, 20f), CornerRadius(10f), style = Stroke(0.8f))
        for (i in 0..2) {
            val dy = -2f * maxOf(0f, s(t / 1000f * 2 * PI.toFloat() - i * 0.9f))
            drawCircle(if (left) BLIP_LIGHT else BOLT_LIGHT, 2.2f, Offset(x + 10f + i * 8f, y + 10f + dy))
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

/** Floating z's over Bolt while they doze. */
private fun DrawScope.snores(t: Float) {
    for (k in 0..2) {
        val p = ((t / 2600f) + k / 3f) % 1f
        val size = 3f + 3.5f * p
        val x = 144f + 12f * p
        val y = 14f - 20f * p
        val z = Path().apply { moveTo(x, y); lineTo(x + size, y); lineTo(x, y + size); lineTo(x + size, y + size) }
        drawPath(z, Mist.copy(alpha = 1f - p), style = Stroke(1.2f, cap = StrokeCap.Round))
    }
}

private fun DrawScope.confetti(t: Float) {
    for (i in 0 until 16) {
        val p = ((t / 1100f) + i * 0.137f) % 1f
        val x = (i * 37f + 11f) % 168f + 4f * s(t / 300f + i)
        val y = -8f + 70f * p
        rotate(t / 4f + i * 40f, Offset(x, y)) {
            drawRect(CONFETTI[i % CONFETTI.size].copy(alpha = 1f - p * 0.6f), Offset(x - 1.6f, y - 0.8f), Size(3.2f, 1.6f))
        }
    }
}
