package com.rollingrecorder

/**
 * Devanagari to Latin the way Hinglish is typed (wo, kya, nahi, hai, rahi), so live lines read the same
 * whichever script the recogniser picked for a window. Rule based and deterministic: no model, no delay.
 * Words already in Latin letters pass through untouched.
 */
object Hinglish {
    private val VOWELS = mapOf('\u0905' to "a", '\u0906' to "aa", '\u0907' to "i", '\u0908' to "ee", '\u0909' to "u", '\u090A' to "oo", '\u090B' to "ri",
        '\u090F' to "e", '\u0910' to "ai", '\u0913' to "o", '\u0914' to "au", '\u0911' to "o")
    private val MATRAS = mapOf('\u093E' to "aa", '\u093F' to "i", '\u0940' to "ee", '\u0941' to "u", '\u0942' to "oo", '\u0943' to "ri", '\u0947' to "e", '\u0948' to "ai",
        '\u094B' to "o", '\u094C' to "au", '\u0949' to "o", '\u0945' to "a")
    private val CONSONANTS = mapOf('\u0915' to "k", '\u0916' to "kh", '\u0917' to "g", '\u0918' to "gh", '\u0919' to "ng", '\u091A' to "ch", '\u091B' to "chh", '\u091C' to "j",
        '\u091D' to "jh", '\u091E' to "ny", '\u091F' to "t", '\u0920' to "th", '\u0921' to "d", '\u0922' to "dh", '\u0923' to "n", '\u0924' to "t", '\u0925' to "th", '\u0926' to "d",
        '\u0927' to "dh", '\u0928' to "n", '\u092A' to "p", '\u092B' to "ph", '\u092C' to "b", '\u092D' to "bh", '\u092E' to "m", '\u092F' to "y", '\u0930' to "r", '\u0932' to "l",
        '\u0935' to "w", '\u0936' to "sh", '\u0937' to "sh", '\u0938' to "s", '\u0939' to "h", '\u0958' to "q", '\u0959' to "kh", '\u095A' to "g", '\u095B' to "z", '\u095C' to "r",
        '\u095D' to "rh", '\u095E' to "f", '\u0933' to "l")
    private const val VIRAMA = '\u094D'
    private const val NUKTA = '\u093C'
    // Whole words whose typed form is not what the rules give.
    private val WORDS = mapOf("वो" to "wo", "वह" to "wo", "है" to "hai", "हैं" to "hain", "नहीं" to "nahi", "क्या" to "kya", "और" to "aur",
        "हाँ" to "haan", "हां" to "haan", "में" to "mein", "मैं" to "main", "ये" to "ye", "यह" to "ye", "के" to "ke", "को" to "ko", "की" to "ki",
        "का" to "ka", "से" to "se", "पर" to "par", "तो" to "to", "भी" to "bhi", "ना" to "na", "था" to "tha", "थी" to "thi", "थे" to "the",
        "हो" to "ho", "हम" to "hum", "तुम" to "tum", "आप" to "aap", "कर" to "kar", "रहा" to "raha", "रही" to "rahi", "रहे" to "rahe",
        "गया" to "gaya", "गयी" to "gayi", "गई" to "gayi", "गए" to "gaye", "लिए" to "liye", "वाला" to "wala", "वाली" to "wali", "अब" to "ab",
        "फिर" to "phir", "कुछ" to "kuch", "सब" to "sab", "बहुत" to "bahut", "ठीक" to "theek", "अच्छा" to "achha", "अच्छी" to "achhi", "जो" to "jo",
        "उसका" to "uska", "उसकी" to "uski", "इसका" to "iska", "कितनी" to "kitni", "कितना" to "kitna", "कैसे" to "kaise", "क्यों" to "kyun", "कब" to "kab",
        "कहाँ" to "kahan", "यहाँ" to "yahan", "वहाँ" to "wahan", "चाहिए" to "chahiye", "मतलब" to "matlab", "लेकिन" to "lekin", "अगर" to "agar", "तक" to "tak")

    fun latin(text: String): String {
        if (text.none { it in '\u0900'..'\u097F' }) return text
        val sb = StringBuilder()
        val word = StringBuilder()
        fun flush() { if (word.isNotEmpty()) { sb.append(WORDS[word.toString()] ?: transliterate(word.toString())); word.clear() } }
        for (ch in text) {
            if (ch in '\u0900'..'\u097F') word.append(ch) else { flush(); sb.append(if (ch == '\u0964') '.' else ch) }
        }
        flush()
        return sb.toString()
    }

    private fun transliterate(w: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < w.length) {
            var ch = w[i]
            if (ch == NUKTA || ch == VIRAMA) { i++; continue }
            // consonant (+ optional nukta) then: virama → bare, matra → that vowel, else inherent "a" unless word-final
            val next = w.getOrNull(i + 1)?.let { if (it == NUKTA) w.getOrNull(i + 2) else it }
            val base = if (w.getOrNull(i + 1) == NUKTA) CONSONANTS[w[i].nukta()] ?: CONSONANTS[ch] else CONSONANTS[ch]
            when {
                base != null -> {
                    out.append(base)
                    val skip = if (w.getOrNull(i + 1) == NUKTA) 2 else 1
                    when {
                        next == VIRAMA -> i += skip + 1
                        next != null && next in MATRAS -> { out.append(MATRAS[next]); i += skip + 1 }
                        next == '\u0902' || next == '\u0901' -> { out.append(if (i + skip + 1 >= w.length) "an" else "a"); i += skip }   // nasal handled below
                        else -> { if (i + skip < w.length) out.append("a"); i += skip }
                    }
                    continue
                }
                ch in VOWELS -> out.append(VOWELS[ch])
                ch == '\u0902' || ch == '\u0901' -> out.append("n")
                ch == '\u0903' -> out.append("h")
                ch in MATRAS -> out.append(MATRAS[ch])
                else -> out.append(ch)
            }
            i++
        }
        return out.toString()
    }

    private fun Char.nukta(): Char = when (this) { '\u0915' -> '\u0958'; '\u0916' -> '\u0959'; '\u0917' -> '\u095A'; '\u091C' -> '\u095B'; '\u0921' -> '\u095C'; '\u0922' -> '\u095D'; '\u092B' -> '\u095E'; else -> this }
}
