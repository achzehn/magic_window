package com.github.lsposed.magicwindow.hook.steps

import android.content.ComponentName
import android.content.ContentValues
import android.net.Uri
import android.os.SystemClock
import com.github.lsposed.magicwindow.common.Constants
import com.github.lsposed.magicwindow.hook.RuleStore
import com.github.lsposed.magicwindow.hook.XLog
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 页面抓取工具：在 system_server 侧记录被打开过的 Activity 全类名，
 * 通过 ContentProvider 回传给模块 UI，供「参与分屏的页面」等字段一键填入。
 *
 * 落点选 `ActivityRecord` 的构造函数：每次启动一个页面都会新建一个 ActivityRecord，
 * 从 `mActivityComponent` 直接拿到 包名 + 类名，比 hook 各种 startActivity 分支稳定。
 *
 * 开关支持热切换：无论 [RuleStore.global] 里 captureEnabled 是真是假，开机时都把钩子挂上，
 * 每次回调再读一次开关（纯内存读）。这样在 UI 里打开开关后立刻就能抓，不必重启手机。
 *
 * 性能约束（关系到开机快慢）：ActivityRecord 的构造通常在 WindowManagerGlobalLock 之内执行，
 * 而向 ContentProvider 写入是一次跨进程同步调用，还可能顺带冷启动模块进程。
 * 所以 hook 回调里只往内存队列丢一条字符串，真正的回传交给后台线程批量做。
 */
object ActivityTracker {

    private const val MAX_SEEN = 4000

    /** 有界队列，写满就丢。抓取只是辅助功能，绝不允许因此阻塞窗口管理 */
    private const val QUEUE_CAPACITY = 512

    /** 开机后延迟一段时间再回传，避开开机高峰，也等 ContentProvider 可用 */
    private const val BOOT_DELAY_MS = 40_000L

    /** 每轮之间的聚合间隔，把零散记录攒成一批 */
    private const val BATCH_INTERVAL_MS = 2_000L

    private val seen = java.util.Collections.synchronizedSet(HashSet<String>())

    private val pending = ArrayBlockingQueue<String>(QUEUE_CAPACITY)

    private val senderStarted = AtomicBoolean(false)

    /** 字段读取失败只打一次日志，避免在 WM 锁热路径上刷屏 */
    private val fieldErrorLogged = AtomicBoolean(false)

    private val uri: Uri = Uri.parse(
        "content://${Constants.CAPTURE_AUTHORITY}/${Constants.CAPTURE_PATH}"
    )

    fun apply(systemServerClassLoader: ClassLoader) {
        // 始终挂钩：开关可以在 UI 里随时打开，不能因为开机时是关的就要求用户重启
        runCatching {
            val clazz = XposedHelpers.findClass(
                Constants.CLASS_ACTIVITY_RECORD, systemServerClassLoader
            )
            XposedBridge.hookAllConstructors(clazz, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    record(param.thisObject)
                }
            })
            XLog.i("已挂钩 ActivityRecord 构造（页面抓取）")
        }.onFailure { XLog.e("挂钩 ActivityRecord 失败", it) }
        startSender()
    }

    /** 运行在 WM 锁内，必须极快：只有字段读取、集合去重和一次入队 */
    private fun record(activityRecord: Any?) {
        if (activityRecord == null) return
        // 运行中可以随时在 UI 里关掉，不必重启
        if (!RuleStore.global().captureEnabled) return
        runCatching {
            val component = XposedHelpers.getObjectField(
                activityRecord, Constants.F_ACTIVITY_COMPONENT
            ) as? ComponentName ?: return
            val pkg = component.packageName
            val cls = component.className
            if (pkg.isEmpty() || cls.isEmpty()) return
            val key = "$pkg|$cls"
            if (!seen.add(key)) return
            if (seen.size > MAX_SEEN) seen.clear()
            if (!pending.offer(key)) return // 队列满则丢弃，不阻塞
        }.onFailure {
            // 新系统字段名若发生变化，靠这条日志暴露；只打一次
            if (fieldErrorLogged.compareAndSet(false, true)) {
                XLog.e("读取 ActivityRecord 组件字段失败，页面抓取不可用", it)
            }
        }
    }

    private fun startSender() {
        if (!senderStarted.compareAndSet(false, true)) return
        Thread {
            // 仅在开机后短期内静默：UI 运行中才打开开关时不必再等
            val sinceBoot = SystemClock.elapsedRealtime()
            val wait = BOOT_DELAY_MS - sinceBoot
            if (wait > 0) Thread.sleep(wait)
            while (true) {
                val batch = ArrayList<String>(QUEUE_CAPACITY)
                // take 阻塞等待第一条；单批发送失败不能让整个回传线程退出
                val gathered = runCatching {
                    batch.add(pending.take()) // 无记录时阻塞在这里，不耗 CPU
                    Thread.sleep(BATCH_INTERVAL_MS) // 攒一攒，减少跨进程次数
                    pending.drainTo(batch)
                }
                if (gathered.isFailure) {
                    XLog.e("页面抓取聚合失败", gathered.exceptionOrNull())
                    continue
                }
                if (batch.isNotEmpty()) {
                    runCatching { sendBatch(batch) }
                        .onFailure { XLog.e("回传页面失败，本批 ${batch.size} 条", it) }
                }
            }
        }.apply {
            name = "MagicWindow-capture"
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }.start()
    }

    private fun sendBatch(batch: List<String>) {
        val activityThread = XposedHelpers.callStaticMethod(
            XposedHelpers.findClass("android.app.ActivityThread", null),
            "currentActivityThread"
        ) ?: return
        val context = XposedHelpers.callMethod(activityThread, "getSystemContext") ?: return
        val resolver = XposedHelpers.callMethod(context, "getContentResolver")
        batch.forEach { key ->
            val split = key.indexOf('|')
            if (split <= 0) return@forEach
            val values = ContentValues().apply {
                put(Constants.CAPTURE_COL_PACKAGE, key.substring(0, split))
                put(Constants.CAPTURE_COL_ACTIVITY, key.substring(split + 1))
            }
            XposedHelpers.callMethod(resolver, "insert", uri, values)
        }
        XLog.i("已回传 ${batch.size} 条页面记录")
    }
}
