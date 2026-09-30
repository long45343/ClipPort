package com.clipport.app

import com.clipport.app.clip.DedupeWindow
import org.junit.Assert.*
import org.junit.Test

class DedupeWindowTest {

    @Test
    fun seen_FirstTime_ReturnsFalse_SecondTime_ReturnsTrue() {
        val dedupe = DedupeWindow(maxSize = 128, windowMs = 30_000)
        val dev = "device-123"
        val seq = 100L

        assertFalse(dedupe.seen(dev, seq)) // 首次未见 -> false
        assertTrue(dedupe.seen(dev, seq))  // 重复见到 -> true
    }

    @Test
    fun seen_DifferentDev_Or_DifferentSeq_ReturnsFalse() {
        val dedupe = DedupeWindow(maxSize = 128, windowMs = 30_000)
        assertFalse(dedupe.seen("dev-A", 1L))
        assertFalse(dedupe.seen("dev-B", 1L)) // 设备不同
        assertFalse(dedupe.seen("dev-A", 2L)) // seq 不同
    }

    @Test
    fun lru_EvictsOldest_WhenExceedingMaxSize() {
        val dedupe = DedupeWindow(maxSize = 3, windowMs = 30_000)
        val dev = "dev-test"

        assertFalse(dedupe.seen(dev, 1L))
        assertFalse(dedupe.seen(dev, 2L))
        assertFalse(dedupe.seen(dev, 3L))

        // 插入第 4 条，最旧的 1L 应当被淘汰
        assertFalse(dedupe.seen(dev, 4L))

        // 验证 1L 再次出现时不再被判定为重复
        assertFalse(dedupe.seen(dev, 1L))
    }

    @Test
    fun expired_Entries_AreRemoved() {
        val dedupe = DedupeWindow(maxSize = 10, windowMs = 50)
        val dev = "dev-exp"

        assertFalse(dedupe.seen(dev, 999L))
        assertTrue(dedupe.seen(dev, 999L)) // 立即重复

        Thread.sleep(70) // 超过 50ms 窗口

        assertFalse(dedupe.seen(dev, 999L)) // 过期后重新视作新条目
    }
}
