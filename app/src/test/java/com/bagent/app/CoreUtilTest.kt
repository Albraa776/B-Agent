package com.bagent.app

import com.bagent.app.core.util.JsonUtil
import com.bagent.app.core.util.Preview
import com.bagent.app.core.util.Redactor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreUtilTest {

    @Test
    fun parsesJsonObject() {
        val obj = JsonUtil.parseObject("""{"a":1,"b":"two"}""")
        assertNotNull(obj)
        assertEquals("1", obj!!["a"].toString())
    }

    @Test
    fun invalidJsonReturnsNull() {
        assertEquals(null, JsonUtil.parseObject("not json"))
    }

    @Test
    fun redactsRegisteredSecret() {
        Redactor.register("supersecretvalue")
        val out = Redactor.redact("token=supersecretvalue done")
        assertTrue(out.contains("***"))
        assertTrue(!out.contains("supersecretvalue"))
        Redactor.unregister("supersecretvalue")
    }

    @Test
    fun redactsBearerTokens() {
        val out = Redactor.redact("Authorization: Bearer abcdefghijklmnop")
        assertTrue(!out.contains("abcdefghijklmnop"))
    }

    @Test
    fun previewTruncatesLongInput() {
        val long = "x".repeat(50)
        val preview = Preview.of("\"$long\"", max = 10)
        assertTrue(preview.length <= 11)
    }
}
