package com.clipport.app

import com.clipport.app.transport.ReconnectStateMachine
import org.junit.Assert.*
import org.junit.Test

class ReconnectStateMachineTest {

    @Test
    fun testInitialState() {
        var scheduledDelay = -1L
        val sm = ReconnectStateMachine(
            scheduler = { delay, _ -> scheduledDelay = delay },
            cancelScheduled = { scheduledDelay = -1L },
            onExecuteConnect = {}
        )

        assertEquals(ReconnectStateMachine.State.IDLE, sm.state)
        assertEquals(0, sm.currentAttempt)
    }

    @Test
    fun testExponentialBackoffSequenceAndCircuitBreaker() {
        var scheduledDelay = -1L
        var scheduledAction: (() -> Unit)? = null
        var connectExecutedCount = 0

        val sm = ReconnectStateMachine(
            maxAttempts = 5,
            backoffDelaysMs = longArrayOf(3000L, 6000L, 12000L, 60000L),
            scheduler = { delay, action ->
                scheduledDelay = delay
                scheduledAction = action
            },
            cancelScheduled = {
                scheduledDelay = -1L
                scheduledAction = null
            },
            onExecuteConnect = {
                connectExecutedCount++
            }
        )

        // 1. 断开连接 -> 触发第一次退避（3s）
        sm.onDisconnected()
        assertEquals(ReconnectStateMachine.State.BACKOFF, sm.state)
        assertEquals(3000L, scheduledDelay)
        assertEquals(0, sm.currentAttempt)

        // 定时器触发 -> CONNECTING 并执行连接
        scheduledAction?.invoke()
        assertEquals(ReconnectStateMachine.State.CONNECTING, sm.state)
        assertEquals(1, connectExecutedCount)

        // 2. 第一次连接失败 -> attempt=1，延时 6s
        sm.onConnectFailed("fail 1")
        assertEquals(ReconnectStateMachine.State.BACKOFF, sm.state)
        assertEquals(1, sm.currentAttempt)
        assertEquals(6000L, scheduledDelay)

        // 3. 第二次失败 -> attempt=2，延时 12s
        sm.onConnectFailed("fail 2")
        assertEquals(ReconnectStateMachine.State.BACKOFF, sm.state)
        assertEquals(2, sm.currentAttempt)
        assertEquals(12000L, scheduledDelay)

        // 4. 第三次失败 -> attempt=3，延时 60s
        sm.onConnectFailed("fail 3")
        assertEquals(ReconnectStateMachine.State.BACKOFF, sm.state)
        assertEquals(3, sm.currentAttempt)
        assertEquals(60000L, scheduledDelay)

        // 5. 第四次失败 -> attempt=4，延时 60s
        sm.onConnectFailed("fail 4")
        assertEquals(ReconnectStateMachine.State.BACKOFF, sm.state)
        assertEquals(4, sm.currentAttempt)
        assertEquals(60000L, scheduledDelay)

        // 6. 第五次失败 -> attempt=5 >= 5 -> 进入 FROZEN 熔断休眠！
        sm.onConnectFailed("fail 5")
        assertEquals(ReconnectStateMachine.State.FROZEN, sm.state)
        assertEquals(5, sm.currentAttempt)
        assertEquals(-1L, scheduledDelay) // 定时器已被取消

        // 在 FROZEN 状态下断开事件被忽略，不继续轮询
        sm.onDisconnected()
        assertEquals(ReconnectStateMachine.State.FROZEN, sm.state)
        assertEquals(-1L, scheduledDelay)

        // 7. 被动事件唤醒 (收到 UDP 在线广播包)
        sm.wakeUp("udp_announced")
        assertEquals(ReconnectStateMachine.State.CONNECTING, sm.state)
        assertEquals(0, sm.currentAttempt)
        assertEquals(2, connectExecutedCount) // 立即执行了一次连接

        // 8. 连接成功 -> 恢复 IDLE
        sm.onConnected()
        assertEquals(ReconnectStateMachine.State.IDLE, sm.state)
        assertEquals(0, sm.currentAttempt)
    }

    @Test
    fun testResetClearsTimerAndAttempt() {
        var scheduledDelay = -1L
        val sm = ReconnectStateMachine(
            scheduler = { delay, _ -> scheduledDelay = delay },
            cancelScheduled = { scheduledDelay = -1L },
            onExecuteConnect = {}
        )

        sm.onDisconnected()
        assertEquals(ReconnectStateMachine.State.BACKOFF, sm.state)
        assertTrue(scheduledDelay > 0)

        sm.reset()
        assertEquals(ReconnectStateMachine.State.IDLE, sm.state)
        assertEquals(0, sm.currentAttempt)
        assertEquals(-1L, scheduledDelay)
    }
}
