package com.clipport.app.clip

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.clipport.app.xposed.ClipboardFloatingActivity
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * 非 Root 模式下的剪贴板读取降级守护线程（D-19=A 决策，对齐 TextCascade-v2 ClipboardSources.kt）。
 * 通过读取 `logcat -b system` 监控系统剪贴板访问拒绝日志，
 * 一旦捕获到本应用后台读取受限日志，立刻拉起 ClipboardFloatingActivity 获焦读取并送入流水线。
 *
 * 特性：
 * 1. 5s~300s 指数退避重启机制；
 * 2. 连续 6 次启动失败自动熔断；
 * 3. 异步 stderr 排水，防止底层子进程管道缓冲区打满阻塞；
 * 4. generation 线程世代控制，彻底避免并发脏读和多重实例。
 */
class LogcatClipboardWatcher(
    private val context: Context,
    private val onLog: (String) -> Unit = {}
) {
    companion object {
        private const val TAG = "LogcatWatcher"
        private const val LOGCAT_RESTART_DELAY_MS = 5000L
        private const val LOGCAT_MAX_RESTART_DELAY_MS = 300_000L
        private const val LOGCAT_STABLE_RESET_MS = 60_000L
        private const val MAX_LOGCAT_FAILURES = 6
        private const val STDERR_JOIN_TIMEOUT_MS = 500L

        internal fun isClipboardDenialLog(line: String, targetPackage: String): Boolean {
            if (!line.contains(targetPackage, ignoreCase = false)) return false
            return line.contains("Denying clipboard access", ignoreCase = true) ||
                   line.contains("clipboard", ignoreCase = true)
        }
    }

    private val lifecycleLock = Any()
    private var generation = 0L
    @Volatile private var logcatWorker: Thread? = null
    @Volatile private var logcatProcess: Process? = null
    private var lastLaunchMs = 0L
    private var consecutiveFailures = 0
    private var logcatStableStartTimeMs = 0L

    fun start() {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) return
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_LOGS) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            onLog("未持有 READ_LOGS 权限，非Root日志监听未启动（可由 Shizuku/ADB 授予）")
            return
        }

        val currentWorkerId: Long
        synchronized(lifecycleLock) {
            generation++
            currentWorkerId = generation
            runCatching { logcatProcess?.destroy() }
            logcatWorker?.interrupt()
            logcatProcess = null
            consecutiveFailures = 0
            logcatStableStartTimeMs = 0L
        }

        val worker = thread(name = "clipport-read-logs", isDaemon = true, start = false) {
            while (true) {
                synchronized(lifecycleLock) {
                    if (currentWorkerId != generation) return@thread
                }

                var process: Process? = null
                var stderrThread: Thread? = null
                var outputObserved = false

                try {
                    val timeStamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
                        .format(Date(System.currentTimeMillis()))
                    val command = arrayOf(
                        "logcat",
                        "-b", "system",
                        "-T", timeStamp,
                        "ClipboardService:E",
                        "SemClipboardService:E",
                        "MiuiClipboardService:E",
                        "HwClipboardService:E",
                        "ClipboardManager:E",
                        "*:S"
                    )
                    val proc = Runtime.getRuntime().exec(command)
                    synchronized(lifecycleLock) {
                        if (currentWorkerId != generation) {
                            proc.destroy()
                            return@thread
                        }
                        process = proc
                        logcatProcess = proc
                    }

                    // 异步排空 stderr，防止进程卡死
                    stderrThread = thread(name = "clipport-logcat-stderr", isDaemon = true) {
                        proc.errorStream.use { input ->
                            val buffer = ByteArray(4096)
                            while (synchronized(lifecycleLock) { currentWorkerId == generation }) {
                                if (input.read(buffer) < 0) break
                            }
                        }
                    }

                    val reader = BufferedReader(InputStreamReader(proc.inputStream))
                    reader.useLines { lines ->
                        for (line in lines) {
                            synchronized(lifecycleLock) {
                                if (currentWorkerId != generation) return@useLines
                            }
                            var shouldLaunch = false
                            synchronized(lifecycleLock) {
                                if (currentWorkerId == generation) {
                                    outputObserved = true
                                    val now = System.currentTimeMillis()
                                    if (logcatStableStartTimeMs == 0L) {
                                        logcatStableStartTimeMs = now
                                    } else if (now - logcatStableStartTimeMs >= LOGCAT_STABLE_RESET_MS) {
                                        consecutiveFailures = 0
                                        logcatStableStartTimeMs = now
                                    }
                                    if (isClipboardDenialLog(line, context.packageName) && now - lastLaunchMs > 1000) {
                                        lastLaunchMs = now
                                        shouldLaunch = true
                                    }
                                }
                            }
                            if (shouldLaunch) {
                                try {
                                    context.startActivity(ClipboardFloatingActivity.intent(context))
                                } catch (e: Exception) {
                                    Log.w(TAG, "launch floating activity failed", e)
                                }
                            }
                        }
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "logcat loop failure", e)
                } finally {
                    runCatching { process?.destroy() }
                    runCatching { process?.inputStream?.close() }
                    runCatching { process?.errorStream?.close() }
                    stderrThread?.let { runCatching { it.join(STDERR_JOIN_TIMEOUT_MS) } }
                    synchronized(lifecycleLock) {
                        if (logcatProcess === process) logcatProcess = null
                        if (currentWorkerId != generation) return@thread
                    }
                }

                var shouldPause = false
                synchronized(lifecycleLock) {
                    if (currentWorkerId == generation) {
                        val stableForMs = if (logcatStableStartTimeMs == 0L) 0L else System.currentTimeMillis() - logcatStableStartTimeMs
                        if (!outputObserved || stableForMs < LOGCAT_STABLE_RESET_MS) {
                            consecutiveFailures++
                            logcatStableStartTimeMs = 0L
                            shouldPause = consecutiveFailures >= MAX_LOGCAT_FAILURES
                        }
                    }
                }

                if (synchronized(lifecycleLock) { currentWorkerId != generation }) return@thread
                if (shouldPause) {
                    onLog("非Root日志监听由于多次失败已暂停，请检查系统日志权限")
                    return@thread
                }

                val exponent = (consecutiveFailures - 1).coerceIn(0, 6)
                val delay = minOf(LOGCAT_MAX_RESTART_DELAY_MS, LOGCAT_RESTART_DELAY_MS shl exponent)
                try {
                    Thread.sleep(delay)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@thread
                }
            }
        }

        synchronized(lifecycleLock) {
            logcatWorker = worker
            worker.start()
        }
        onLog("非Root日志监听已启动（READ_LOGS 守护中）")
    }

    fun stop() {
        val workerToJoin: Thread?
        synchronized(lifecycleLock) {
            generation++
            runCatching { logcatProcess?.destroy() }
            logcatWorker?.interrupt()
            logcatProcess = null
            workerToJoin = logcatWorker
            logcatWorker = null
        }
        workerToJoin?.let { runCatching { it.join(STDERR_JOIN_TIMEOUT_MS + 500L) } }
    }
}
