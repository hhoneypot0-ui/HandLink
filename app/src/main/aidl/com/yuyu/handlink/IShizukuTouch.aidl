// IShizukuTouch.aidl
//
// Shizuku UserService 的 AIDL 接口。
//
// 为什么必须这样写：
//   触摸注入要调用隐藏 API InputManager.injectInputEvent()，
//   但普通 App 进程调用会被 SELinux 拒绝、还会被 HiddenApiRestrictions 拦截。
//
//   Shizuku 的 UserService 能把这份实现类加载到一个 **shell(uid 2000) 权限的独立进程**里运行，
//   那个进程用的就是 AOSP 的 app_process 环境，隐藏 API 完全可用。
//
//   调用链路：
//     牵手远程 App  →  Binder  →  Shizuku 服务端  →  UserService 进程(shell)  →  InputManager
//
// 事务码约定（来自 Shizuku 文档）：
//   destroy() 固定为 16777114，由 Shizuku 服务端调用以退出进程，不要改。

package com.yuyu.handlink;

interface IShizukuTouch {
    /** Shizuku 保留事务：销毁服务 */
    void destroy() = 16777114;

    /** 初始化，返回是否成功（能拿到 IInputManager 且方法可调用） */
    boolean init() = 1;

    /** 0=down 1=move 2=up，坐标是设备真实像素 */
    boolean touch(int action, float x, float y) = 2;

    /** 一次点击 */
    boolean tap(float x, float y, long durationMs) = 3;

    /** 诊断信息，比如拿到的 InputManager 类名 */
    String info() = 4;
}