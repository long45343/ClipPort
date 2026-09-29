package com.clipport.app.xposed

import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import java.util.concurrent.atomic.AtomicBoolean

/**
 * LSPosed 模块入口（spec §6.1，复用 TextCascade-v2 已验证方案）：
 * hook system_server 的 ClipboardService.isDefaultIme 两个签名重载，
 * 仅当调用方包名 == 本应用时返回 true，其余 chain.proceed()（绝不影响其他应用）。
 */
class ClipboardHookInstaller(private val tryHook: (ClassLoader, String, Array<Class<*>>) -> Boolean) {
    val installed = AtomicBoolean(false)

    fun installHooks(classLoader: ClassLoader?): Boolean {
        val cl = classLoader ?: return false
        if (!installed.compareAndSet(false, true)) return false
        var success = false
        try {
            success = tryHook(cl, "clipport_isDefaultIme", arrayOf(Integer.TYPE, String::class.java))
            if (!success) success = tryHook(cl, "clipport_isDefaultIme2", arrayOf(Integer.TYPE, String::class.java, Integer.TYPE))
            return success
        } finally {
            if (!success) installed.set(false)
        }
    }
}

class XposedEntry : XposedModule() {
    companion object {
        private const val TAG = "ClipPortXposed"
        private const val OUR_PACKAGE = "com.clipport.app"
    }

    private val installer = ClipboardHookInstaller { cl, hookId, parameterTypes -> tryHookSignature(cl, hookId, parameterTypes) }

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        log(Log.INFO, TAG, "event=module_loaded process=${param.processName} api=$apiVersion framework=$frameworkName")
    }

    override fun onSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        log(Log.INFO, TAG, "event=system_server_starting")
        try {
            val success = installer.installHooks(param.classLoader)
            if (!success && installer.installed.get()) {
                log(Log.INFO, TAG, "event=install_skipped reason=already_installed")
            } else if (!success) {
                log(Log.WARN, TAG, "event=install_failed status_rolled_back")
            }
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "event=install_failed", t)
        }
    }

    private fun tryHookSignature(cl: ClassLoader, hookId: String, parameterTypes: Array<Class<*>>): Boolean {
        return try {
            val clipboardServiceClass = cl.loadClass("com.android.server.clipboard.ClipboardService")
            val method = clipboardServiceClass.getDeclaredMethod("isDefaultIme", *parameterTypes)
            method.isAccessible = true
            hook(method)
                .setId(hookId)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val args = chain.args
                    if (args.size < 2) return@intercept chain.proceed()
                    val packageName = args[1] as? String
                    if (OUR_PACKAGE == packageName) {
                        log(Log.DEBUG, TAG, "event=${hookId}_whitelisted package=$packageName")
                        return@intercept true
                    }
                    chain.proceed()
                }
            log(Log.INFO, TAG, "event=hook_registered id=$hookId params=${parameterTypes.joinToString { it.simpleName }}")
            true
        } catch (e: NoSuchMethodException) {
            log(Log.WARN, TAG, "event=signature_not_found id=$hookId")
            false
        } catch (e: Throwable) {
            log(Log.ERROR, TAG, "event=signature_hook_failed id=$hookId", e)
            false
        }
    }
}
