package org.koitharu.kotatsu.local

import org.junit.Assert.assertEquals
import org.junit.Test
import org.koitharu.kotatsu.local.data.stableKey

class StableKeyTest {

    @Test
    fun linksWithoutSigningStayAsTheyAre() {
        assertEquals("https://a.b/p/1.jpg", stableKey("https://a.b/p/1.jpg"))
        assertEquals("https://a.b/p?id=7&w=2#x", stableKey("https://a.b/p?id=7&w=2#x"))
    }

    @Test
    fun signingParametersAreDropped() {
        assertEquals("https://a.b/p/1.jpg", stableKey("https://a.b/p/1.jpg?Expires=1&Signature=abc"))
        assertEquals("https://a.b/p?id=7", stableKey("https://a.b/p?id=7&X-Amz-Signature=z&X-Amz-Date=1"))
        assertEquals("https://a.b/p?id=7#f", stableKey("https://a.b/p?Expires=9&id=7#f"))
    }

    @Test
    fun differentImagesKeepDifferentKeys() {
        assertEquals(false, stableKey("https://a.b/p?id=7&sig=1") == stableKey("https://a.b/p?id=8&sig=1"))
    }
}
