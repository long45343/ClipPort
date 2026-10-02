package com.clipport.app.transport

/**
 * Android 重连退避与熔断状态机（D-30=A）：
 * - 指数退避序列：3s -> 6s -> 12s -> 60s
 * - 连续 5 次失败进入 FROZEN 熔断休眠（零发包、零耗电）
 * - 收到 UDP 在线广播、网络恢复或手动交互时被动唤醒
 * - 调度器接口抽象，便于单元测试瞬间执行
 */
class ReconnectStateMachine(
    private val maxAttempts: Int = 5,
    private val backoffDelaysMs: LongArray = longArrayOf(3000L, 6000L, 12000L, 60000L),
    private val scheduler: (delayMs: Long, action: () -> Unit) -> Unit,
    private val cancelScheduled: () -> Unit,
    private val onExecuteConnect: () -> Unit,
    private val onStateChanged: (State, Long, String?) -> Unit = { _, _, _ -> },
) {
    enum class State {
        IDLE,       // 闲置或已连通
        BACKOFF,    // 退避中（等待定时器触发）
        CONNECTING, // 正在执行建连
        FROZEN      // 连续失败超限，休眠熔断
    }

    var state: State = State.IDLE
        private set

    var currentAttempt: Int = 0
        private set

    /** 计算当前尝试对应的退避毫秒数 */
    fun nextDelayMs(): Long {
        if (backoffDelaysMs.isEmpty()) return 3000L
        val idx = currentAttempt.coerceAtLeast(0).coerceAtMost(backoffDelaysMs.size - 1)
        return backoffDelaysMs[idx]
    }

    /** 物理链路已连通：重置计数器与状态 */
    @Synchronized
    fun onConnected() {
        cancelScheduled()
        currentAttempt = 0
        state = State.IDLE
        onStateChanged(State.IDLE, 0L, "connected")
    }

    /** 链路断开：触发计划重连 */
    @Synchronized
    fun onDisconnected() {
        if (state == State.FROZEN) return
        scheduleNextAttempt("disconnected")
    }

    /** 连接单次尝试失败：推进计数器并评估熔断 */
    @Synchronized
    fun onConnectFailed(errorMsg: String? = null) {
        currentAttempt++
        if (currentAttempt >= maxAttempts) {
            cancelScheduled()
            state = State.FROZEN
            onStateChanged(State.FROZEN, 0L, errorMsg ?: "exceeded_max_attempts")
        } else {
            scheduleNextAttempt(errorMsg ?: "connect_failed")
        }
    }

    /** 被动外部事件唤醒（UDP广播宣告、网络恢复、手动点击等） */
    @Synchronized
    fun wakeUp(reason: String) {
        cancelScheduled()
        currentAttempt = 0
        state = State.CONNECTING
        onStateChanged(State.CONNECTING, 0L, reason)
        onExecuteConnect()
    }

    /** 彻底重置状态机 */
    @Synchronized
    fun reset() {
        cancelScheduled()
        currentAttempt = 0
        state = State.IDLE
        onStateChanged(State.IDLE, 0L, "reset")
    }

    private fun scheduleNextAttempt(reason: String) {
        cancelScheduled()
        val delay = nextDelayMs()
        state = State.BACKOFF
        onStateChanged(State.BACKOFF, delay, reason)
        scheduler(delay) {
            synchronized(this) {
                if (state == State.BACKOFF) {
                    state = State.CONNECTING
                    onStateChanged(State.CONNECTING, 0L, "timer_fired")
                    onExecuteConnect()
                }
            }
        }
    }
}
