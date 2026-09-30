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
}
