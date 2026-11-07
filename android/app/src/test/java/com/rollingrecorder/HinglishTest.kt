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
