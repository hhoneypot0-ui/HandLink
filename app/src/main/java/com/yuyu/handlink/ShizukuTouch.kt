package com.yuyu.handlink

import android.content.ComponentName
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import rikka.shizuku.Shizuku

/**
 * Shizuku 触摸通道（App 侧）。
 *
 * 实际注入工作在 [ShizukuTouchService] 里，那个类会被 Shizuku 加载到
 * **shell(uid 2000) 权限的独立进程**中执行 —— 这是能调用隐藏 API 的前提。
 *
 * ===================== 走过的弯路 =====================
 *
 * 最初我直接在 App 进程里用 ShizukuBinderWrapper 包一层 IInputManager 去反射调用。
 * 表面上看通了，实际注入全部失败：
 *   · App 进程受 SELinux 限制，调 injectInputEvent 被拒
 *   · 反射隐藏 API 还会被 HiddenApiRestrictions 拦
 *
 * GitHub 上同类项目（MAA-Meow 等）无一例外都强调：
 *   "在 Shizuku 用户服务进程中构造 Context 并反射访问 Hidden API"
 *
 * 所以现在改成标准的 UserService 模式：
 *    App → Binder → Shizuku 服务端 → UserService 进程(shell) → InputManager
 */
object ShizukuTouch {

    private const val TAG = "HandLinkShizuku"

    /** 通道是否可用（Shizuku 已启动 + 已授权 + UserService 已绑定 + init 成功） */
    @Volatile var available = false
        private set

    @Volatile private var remote: IShizukuTouch? = null
    @Volatile private var binding = false

    private var downTime = 0L

    // ---------------- 绑定 ----------------

    private val userServiceArgs by lazy {
        Shizuku.UserServiceArgs(
            ComponentName(
                "com.yuyu.handlink",
                ShizukuTouchService::class.java.name
            )
        )
            .daemon(false)
            .processNameSuffix("touch")
            .debuggable(false)
            .version(BuildConfig.VERSION_CODE)
    }

    private val connection = object : android.content.ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            try {
                val s = IShizukuTouch.Stub.asInterface(service)
                remote = s
                val ok = s?.init() ?: false
                available = ok
                binding = false
                if (ok) {
                    Log.i(TAG, "UserService ready: ${s?.info()}")
                } else {
                    Log.e(TAG, "UserService init returned false")
                }
            } catch (e: Throwable) {
                Log.e(TAG, "onServiceConnected failed", e)
                available = false
                binding = false
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "UserService disconnected")
            remote = null
            available = false
        }
    }

    /** 尝试建立通道。Shizuku 就绪后调用一次即可。 */
    fun bind(): Boolean {
        if (available || binding) return available
        return try {
            if (!Shizuku.pingBinder()) {
                Log.w(TAG, "Shizuku not running")
                return false
            }
            if (Shizuku.isPreV11()) {
                Log.w(TAG, "Shizuku pre-v11 unsupported")
                return false
            }
            if (Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "permission not granted")
                return false
            }
            binding = true
            Shizuku.bindUserService(userServiceArgs, connection)
            Log.i(TAG, "bindUserService requested")
            true
        } catch (e: Throwable) {
            Log.e(TAG, "bind failed", e)
            binding = false
            false
        }
    }

    /** 主动重绑，用于 Shizuku 刚启动的场景 */
    fun rebind() {
        try {
            Shizuku.unbindUserService(userServiceArgs, connection, true)
        } catch (e: Throwable) { }
        available = false
        remote = null
        binding = false
        bind()
    }

    // ---------------- 注入 ----------------

    fun down(x: Float, y: Float): Boolean {
        downTime = SystemClock.uptimeMillis()
        return invoke(0, x, y)
    }

    fun move(x: Float, y: Float): Boolean = invoke(1, x, y)

    fun up(x: Float, y: Float): Boolean = invoke(2, x, y)

    fun tap(x: Float, y: Float, durationMs: Long = 40L): Boolean {
        val r = remote ?: return false
        return try {
            r.tap(x, y, durationMs)
        } catch (e: Throwable) {
            Log.w(TAG, "tap failed", e)
            false
        }
    }

    private fun invoke(action: Int, x: Float, y: Float): Boolean {
        val r = remote ?: return false
        return try {
            r.touch(action, x, y)
        } catch (e: Throwable) {
            Log.w(TAG, "touch($action) failed", e)
            false
        }
    }
}