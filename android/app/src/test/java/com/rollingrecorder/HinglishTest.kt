package com.rollingrecorder

import org.junit.Assert.assertEquals
import org.junit.Test

class HinglishTest {
    @Test fun commonWords() = assertEquals("wo kitni stuped lag rahi hai na", Hinglish.latin("वो कितनी स्तुपेद लग रही है ना"))
    @Test fun mixedLine() = assertEquals("haan to ye jo peeosee hai uska timeline next week tak finalize kar dete hain",
        Hinglish.latin("हाँ तो ये जो पीओसी है उसका timeline next week तक finalize कर देते हैं"))
    @Test fun latinPassesThrough() = assertEquals("So basically, I don't know.", Hinglish.latin("So basically, I don't know."))
    @Test fun conjunctsAndNukta() = assertEquals("zaroor achha pyaar", Hinglish.latin("ज़रूर अच्छा प्यार"))
}

class CollapseRepeatsTest {
    private val m = LiveAsr::class.java.getDeclaredMethod("collapseRepeats", String::class.java).apply { isAccessible = true }
    private fun c(s: String) = m.invoke(LiveAsr, s) as String
    @Test fun loopedPhrase() = assertEquals("thank you so much thank you so much", c("thank you so much thank you so much thank you so much thank you so much thank you so much"))
    @Test fun loopedWord() = assertEquals("no, no, okay then", c("no, no, no, no, no, okay then"))
    @Test fun normalTextUntouched() = assertEquals("I think we can start now", c("I think we can start now"))
}
