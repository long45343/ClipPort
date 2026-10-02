package com.clipport.app

import com.clipport.app.clip.ClipConst
import com.clipport.app.clip.ClipFilter
import org.junit.Assert.*
import org.junit.Test

class ClipFilterTest {

    @Test
    fun testSelfLabelConstant() {
        assertEquals("clipportClipData", ClipConst.SELF_LABEL)
    }

    @Test
    fun testSameTimestamp_NullOrInvalid() {
        assertFalse(ClipFilter.isSameTimestamp(null, 1000L))
        assertFalse(ClipFilter.isSameTimestamp(null, 0L))
        assertFalse(ClipFilter.isSameTimestamp(null, -1L))
    }

    @Test
    fun testSameContent_IdenticalText() {
        assertTrue(ClipFilter.sameContent("abc", null, null, "abc", null, null))
    }

    @Test
    fun testSameContent_DifferentText() {
        assertFalse(ClipFilter.sameContent("abc", null, null, "abd", null, null))
    }

    @Test
    fun testSameContent_NullVsNonNull() {
        assertFalse(ClipFilter.sameContent(null, null, null, "abc", null, null))
        assertFalse(ClipFilter.sameContent("abc", null, null, null, null, null))
    }

    @Test
    fun testSameContent_ImageByteArrayContentComparison() {
        val a = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        val b = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        val c = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x48)
        // 内容相同但引用不同 → 视为一致（字节级比对，而非引用比对）
        assertTrue(ClipFilter.sameContent(null, null, a, null, null, b))
        assertFalse(ClipFilter.sameContent(null, null, a, null, null, c))
        assertFalse(ClipFilter.sameContent(null, null, a, null, null, null))
        assertFalse(ClipFilter.sameContent(null, null, null, null, null, a))
    }

    @Test
    fun testSameContent_HtmlField() {
        assertTrue(ClipFilter.sameContent("t", "<b>x</b>", null, "t", "<b>x</b>", null))
        assertFalse(ClipFilter.sameContent("t", "<b>x</b>", null, "t", "<i>x</i>", null))
    }
}
