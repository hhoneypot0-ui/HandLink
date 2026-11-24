package com.yuyu.handlink

import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent

/**
 * Shizuku UserService 实现：运行在 **shell(uid 2000) 权限的独立进程**里。
 *
 * ===================== 关键约束（照着写才不会崩） =====================
 *
 * 1) 这个类会被 Shizuku 用 app_process 加载到新进程，
 *    **不能碰 App 的 Context**。registerReceiver / getContentResolver /
 *    getSystemService(Context.xxx) 全部不可用，只能直接构造系统 Binder 代理。
 *
 * 2) 隐藏 API 在这里**可用** —— 这正是整个方案的意义。
 *    App 进程调 injectInputEvent 会被拦，这个进程不会。
 *
 * 3) 坐标必须是**设备真实像素**，不能用归一化值。
 *
 * ===================== 为什么不用 input 命令 =====================
 *
 * 参考 pebble-screen-mirror 的踩坑记录：
 *   逐点调用 `input motionevent` 每个点要起一个 app_process（约 20ms），
 *   整条轨迹会被拉长到无法使用。
 * 所以这里直接用 Binder 调 InputManager，单次调用开销是微秒级。
 */
class ShizukuTouchService : IShizukuTouch.Stub() {

    companion object {
        private const val TAG = "HandLinkShizukuSvc"
    }

    private var inputManager: Any? = null
    private var injectMethod: java.lang.reflect.Method? = null
    private var downTime = 0L
    private var lastX = 0f
    private var lastY = 0f

    override fun init(): Boolean {
        return try {
            // 直接拿 "input" 服务的 Binder，再由 IInputManager$Stub.asInterface 转成接口。
            // 这里不能走 Context.getSystemService —— UserService 进程没有合法 Context。
            val sm = Class.forName("android.os.ServiceManager")
            val getService = sm.getMethod("getService", String::class.java)
            val binder = getService.invoke(null, "input") as? IBinder
                ?: run {
                    Log.e(TAG, "input service binder is null")
                    return false
                }

            val stub = Class.forName("android.hardware.input.IInputManager\$Stub")
            val asInterface = stub.getMethod("asInterface", IBinder::class.java)
            inputManager = asInterface.invoke(null, binder)

            // injectInputEvent 在不同版本上签名不同：
            //   老版本：injectInputEvent(InputEvent, int mode)
            //   新版本：有的机器加了 verify 参数，按需兼容
            val cls = inputManager!!.javaClass
            injectMethod = try {
                cls.getMethod(
                    "injectInputEvent",
                    android.view.InputEvent::class.java,
                    Int::class.javaPrimitiveType
                )
            } catch (e: NoSuchMethodException) {
                cls.getMethod(
                    "injectInputEvent",
                    android.view.InputEvent::class.java,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType
                )
            }

            Log.i(TAG, "init ok, inputManager=${inputManager!!.javaClass.name}, " +
                "method=${injectMethod!!.name}/${injectMethod!!.parameterCount}")
            true
        } catch (e: Throwable) {
            Log.e(TAG, "init failed", e)
            false
        }
    }

    override fun touch(action: Int, x: Float, y: Float): Boolean {
        val method = injectMethod ?: return false
        val im = inputManager ?: return false
        val now = SystemClock.uptimeMillis()

        val motionAction = when (action) {
            0 -> MotionEvent.ACTION_DOWN
            1 -> MotionEvent.ACTION_MOVE
            2 -> MotionEvent.ACTION_UP
            else -> return false
        }

        if (motionAction == MotionEvent.ACTION_DOWN) {
            downTime = now
        }
        lastX = x
        lastY = y

        // downTime 整个手势保持一致、eventTime 递增，系统才认这是同一次按压
        val ev = MotionEvent.obtain(downTime, now, motionAction, x, y, 0)
        ev.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
        return try {
            val ok = if (method.parameterCount == 2) {
                method.invoke(im, ev, 0) as? Boolean ?: false
            } else {
                // 3 参数版本：最后一个是 verify，传 0 不校验
                method.invoke(im, ev, 0, 0) as? Boolean ?: false
            }
            if (!ok) Log.w(TAG, "inject action=$motionAction failed at ($x,$y)")
            ok
        } catch (e: Throwable) {
            Log.w(TAG, "inject threw", e)
            false
        } finally {
            ev.recycle()
        }
    }

    override fun tap(x: Float, y: Float, durationMs: Long): Boolean {
        if (!touch(0, x, y)) return false
        try {
            Thread.sleep(durationMs.coerceIn(20L, 2000L))
        } catch (e: InterruptedException) { }
        return touch(2, x, y)
    }

    /**
     * Shizuku 保留事务（16777114），服务端销毁时调用。
     * 这里直接结束进程，否则 UserService 会残留。
     */
    override fun destroy() {
        Log.i(TAG, "destroy requested")
        try {
            System.exit(0)
        } catch (e: Throwable) { }
    }

    override fun info(): String {
        val im = inputManager ?: return "not initialized"
        return "${im.javaClass.name} | ${injectMethod?.name}/${injectMethod?.parameterCount}"
    }
}