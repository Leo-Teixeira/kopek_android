package com.lteixeira.kopekkit

import org.junit.Assert.assertEquals
import org.junit.Test

class KopekKitTest {
    @Test
    fun `la version du SDK suit celle du web`() {
        assertEquals("0.1.0", KopekKit.version)
    }
}
