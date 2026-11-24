# -*- coding: utf-8 -*-
"""
牵手远程 HandLink 全自动测试矩阵
用法：两台手机插回 USB 后运行  python -X utf8 test_matrix.py
产出：C:\\dev\\handlink\\testout\\ 下的截图 + 报告.md
方向二（自动全跑）：一加被控 ↔ A93s 控制
方向一（视频/声音自动，触摸注入需先在 A93s 手动开无障碍，脚本会提示）：A93s 被控 ↔ 一加控制
"""
import subprocess, time, re, os, sys

ADB = "adb"  # TODO: 指向你自己的 platform-tools
APK = r"C:\dev\handlink\app\build\outputs\apk\debug\app-debug.apk"
OUT = r"C:\dev\handlink\testout"
ONE = "SERIAL_DEVICE_1"   # 你的测试机1序列号
A93 = "SERIAL_DEVICE_2"   # 你的测试机2序列号
PKG = "com.yuyu.handlink"
ACT = PKG + "/.MainActivity"

os.makedirs(OUT, exist_ok=True)
results = []  # (方向, 项目, PASS/FAIL/手动, 证据)

def sh(serial, cmd, timeout=30):
    return subprocess.run([ADB, "-s", serial, "shell", cmd],
                          capture_output=True, text=True, timeout=timeout).stdout

def adb_devices():
    out = subprocess.run([ADB, "devices"], capture_output=True, text=True).stdout
    return set(re.findall(r"^(serial)?\s*(\S+)\s+device", out, re.M) and
               re.findall(r"^(\S+)\s+device", out, re.M) or [])

def wait_devices(secs=28800):
    end = time.time() + secs
    while time.time() < end:
        d = adb_devices()
        if ONE in d and A93 in d:
            return True
        time.sleep(5)
    return False

def tap(serial, x, y):
    sh(serial, f"input tap {int(x)} {int(y)}")

def swipe(serial, x1, y1, x2, y2, ms=300):
    sh(serial, f"input swipe {int(x1)} {int(y1)} {int(x2)} {int(y2)} {int(ms)}")

def dump(serial):
    sh(serial, "uiautomator dump /sdcard/t.xml >/dev/null 2>&1")
    return sh(serial, "cat /sdcard/t.xml")

def find_node(serial, needle):
    """找控件中心坐标：先精确匹配再子串匹配。
    子串匹配会误中弹窗标题（如"开始"命中"要开始使用…录制吗？"），
    点到不可点的标题文字导致流程卡死——必须精确优先。"""
    xml = dump(serial)
    m = re.search(r'text="' + re.escape(needle) + r'"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
    if m:
        x1, y1, x2, y2 = map(int, m.groups())
        return (x1 + x2) // 2, (y1 + y2) // 2
    m = re.search(r'text="([^"]*' + re.escape(needle) + r'[^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
    if not m:
        return None
    x1, y1, x2, y2 = map(int, m.groups()[1:])
    return (x1 + x2) // 2, (y1 + y2) // 2

def wake(serial):
    sh(serial, "input keyevent KEYCODE_WAKEUP")
    sh(serial, "wm dismiss-keyguard")
    time.sleep(1)

def tap_text(serial, needle, retries=6, wait=1.0):
    for _ in range(retries):
        p = find_node(serial, needle)
        if p:
            tap(serial, *p)
            return True
        time.sleep(wait)
    return False

def screencap(serial, name):
    path = os.path.join(OUT, name + ".png")
    with open(path, "wb") as f:
        subprocess.run([ADB, "-s", serial, "exec-out", "screencap", "-p"],
                       stdout=f, timeout=30)
    return path

def logcat(serial, tags, grep=""):
    cmd = "logcat -d " + " ".join(f"{t}:V" for t in tags) + " *:S"
    if grep:
        cmd += f" | grep -E '{grep}'"
    return sh(serial, cmd + " | tail -40")

def clear_log(serial):
    sh(serial, "logcat -c")

def install(serial):
    r = subprocess.run([ADB, "-s", serial, "install", "-r", APK],
                       capture_output=True, text=True, timeout=300)
    return "Success" in r.stdout

def sweep_popups(serial):
    """清扫 ColorOS 干扰弹窗：无障碍启动警告→保持开启；其余可关的通用取消"""
    tap_text(serial, "保持开启", retries=2, wait=1.0)

def enable_a11y_oneplus():
    sh(ONE, f"settings put secure enabled_accessibility_services {PKG}/com.yuyu.handlink.RemoteControlService")
    sh(ONE, "settings put secure accessibility_enabled 1")
    time.sleep(3)
    # ColorOS 会弹"检测到启动无障碍服务"安全警告，不点掉会挡住后续所有流程
    sweep_popups(ONE)
    return "RemoteControlService" in sh(ONE, "dumpsys accessibility")

def start_controlled(serial):
    """把 serial 变成被控端：进被控页→开始共享→系统授权选整个屏幕→开始"""
    wake(serial)
    sweep_popups(serial)
    sh(serial, "am force-stop " + PKG)
    sh(serial, "am start -n " + ACT)
    time.sleep(3)
    sweep_popups(serial)  # 重装后开无障碍可能又弹警告
    if not tap_text(serial, "这一台是被控的手机", retries=10):
        return False, "找不到被控入口"
    time.sleep(1.5)
    # 必须用完整按钮文本：说明文字"换档后重新点开始共享生效"也含"开始共享"，
    # 子串匹配会先命中那段文字，点到非按钮区域导致弹窗永不出现。
    if not tap_text(serial, "开始共享，允许被控制"):
        return False, "找不到开始共享按钮"
    # 系统录屏授权弹窗：Android 14+ 是 spinner（单个应用/整个屏幕），Android 11 是直接"立即开始"
    # 弹窗出现时间不定，最多等 12 秒
    for _ in range(6):
        time.sleep(2)
        if find_node(serial, "单个应用") or find_node(serial, "立即开始") or find_node(serial, "开始录制"):
            break
    if find_node(serial, "单个应用"):
        tap_text(serial, "单个应用")
        time.sleep(1)
        tap_text(serial, "整个屏幕")
        time.sleep(1)
        tap_text(serial, "开始") or tap_text(serial, "下一步")
        time.sleep(1)
        tap_text(serial, "开始")
    else:
        tap_text(serial, "立即开始") or tap_text(serial, "开始录制") or tap_text(serial, "开始")
    for _ in range(10):
        time.sleep(1.5)
        code = find_node(serial, "配对码")
        xml = dump(serial)
        m = re.search(r'text="(\d{6})"', xml)
        if m:
            return True, m.group(1)
    return False, "共享未起来"

def get_ip(serial):
    xml = dump(serial)
    m = re.search(r'本机 IP：(\d+\.\d+\.\d+\.\d+)', xml)
    return m.group(1) if m else None

def controller_connect(serial, ip, code):
    wake(serial)
    sweep_popups(serial)
    sh(serial, "am force-stop " + PKG)
    sh(serial, "am start -n " + ACT)
    time.sleep(3)
    if not tap_text(serial, "用这台手机控制对方"):
        return False, "找不到控制入口"
    time.sleep(1.5)
    # 确保局域网模式：找"对方 IP"字段
    if not find_node(serial, "对方 IP"):
        tap_text(serial, "局域网直连")
        time.sleep(1)
    p = find_node(serial, "对方 IP")
    if not p:
        return False, "找不到 IP 输入框"
    tap(serial, *p)  # OutlinedTextField 的标签在框内，点标签即聚焦输入框
    time.sleep(1.2)
    sh(serial, f"input text '{ip}'")
    p2 = find_node(serial, "配对码")
    if not p2:
        return False, "找不到配对码框"
    tap(serial, *p2)
    time.sleep(1.2)
    sh(serial, f"input text '{code}'")
    time.sleep(0.5)
    sh(serial, "input keyevent 4")  # 收键盘
    time.sleep(0.5)
    if not tap_text(serial, "发起连接"):
        return False, "发起连接点不到"
    return True, "已发起"

def approve_consent(serial):
    wake(serial)
    sweep_popups(serial)
    return tap_text(serial, "允许", retries=25, wait=2.0)

def record(direction, item, ok, evidence=""):
    results.append((direction, item, "PASS" if ok else "FAIL", evidence))
    print(f"[{direction}] {item}: {'PASS' if ok else 'FAIL'}  {evidence[:120]}")

def main():
    print("等待两台设备…")
    if not wait_devices():
        print("!! 设备没插上，退出"); sys.exit(1)
    print("安装 APK…")
    ok1, ok2 = install(ONE), install(A93)
    if not (ok1 and ok2):
        print("!! 安装失败", ok1, ok2); sys.exit(1)

    # ---------- 方向二：一加被控 ↔ A93s 控制 ----------
    D = "一加被控→A93s控制"
    print("== 方向二 ==")
    ok, info = start_controlled(ONE)
    if not ok:
        record(D, "被控端启动", False, info); report(); return
    record(D, "被控端启动+录屏授权", True, "配对码 " + info)
    code = info
    ip = get_ip(ONE)
    a11y = enable_a11y_oneplus()
    record(D, "一加无障碍绑定", a11y, "")
    clear_log(ONE); clear_log(A93)

    ok, info = controller_connect(A93, ip, code)
    record(D, "A93s 发起连接", ok, info)
    ok = approve_consent(ONE)
    record(D, "同意框弹出并可确认", ok, "")
    time.sleep(6)
    screencap(A93, "d2_controller_view")
    screencap(ONE, "d2_controlled_view")

    # 视频：A93s 端 RemoteDecoder 出帧
    vid = logcat(A93, ["RemoteDecoder", "RemoteLink"], "start|first output|no output")
    dec_ok = "first output frame rendered" in vid or "start " in vid
    record(D, "画面正常(解码器启动/出帧)", dec_ok, vid.strip().splitlines()[-1] if vid.strip() else "无日志")

    # 声音
    aud = logcat(A93, ["HandLinkAudio"], "prebuffer|stats|config injected")
    record(D, "控制端有声音", "prebuffer filled" in aud or "player started" in aud,
           [l for l in aud.splitlines() if "stats" in l][-1] if "stats" in aud else aud[:100])

    # 点击透传：在 A93s 画面中央点一下，看一加 HandLinkInput
    tap(A93, 540, 1100)
    time.sleep(2)
    inp = logcat(ONE, ["HandLinkInput"], "handleInput|dispatchGesture")
    record(D, "点击落点/注入", "handleInput" in inp and "dispatchGesture dur=40ms -> true" in inp,
           inp.strip().splitlines()[-1] if inp.strip() else "无注入日志")

    # 上滑
    swipe(A93, 540, 1400, 540, 600, 300)
    time.sleep(2)
    inp = logcat(ONE, ["HandLinkInput"], "handleInput")
    record(D, "上滑生效", inp.count("handleInput") >= 3, f"注入事件数 {inp.count('handleInput')}")

    # 下滑
    n_before = inp.count("handleInput")
    swipe(A93, 540, 600, 540, 1400, 300)
    time.sleep(2)
    inp2 = logcat(ONE, ["HandLinkInput"], "handleInput")
    record(D, "下滑生效", inp2.count("handleInput") > n_before, "")

    # 短距离滑动
    swipe(A93, 540, 1100, 540, 950, 250)
    time.sleep(2)
    inp3 = logcat(ONE, ["HandLinkInput"], "handleInput")
    record(D, "短距离滑动生效", len(inp3) > len(inp2), "")

    # 长按
    swipe(A93, 540, 1100, 540, 1102, 900)
    time.sleep(2)
    inp4 = logcat(ONE, ["HandLinkInput"], "handleInput|dur=")
    record(D, "长按生效", "dur=650ms" in inp4 or "lp" in inp4,
           [l for l in inp4.splitlines() if "dur" in l][-1] if "dur" in inp4 else "")

    # 切后台 5 秒回来画面正常
    sh(A93, "input keyevent KEYCODE_HOME"); time.sleep(5)
    sh(A93, "am start -n " + ACT); time.sleep(5)
    screencap(A93, "d2_after_bg5s")
    logs = logcat(A93, ["RemoteDecoder", "RemoteLink"], "start|attachSurface|size")
    record(D, "切后台5秒回来画面正常", "start " in logs or "rebuild" in logs,
           logs.strip().splitlines()[-1] if logs.strip() else "")

    # 切后台 30 秒自动重连
    sh(A93, "input keyevent KEYCODE_HOME"); time.sleep(32)
    sh(A93, "am start -n " + ACT); time.sleep(6)
    logs = logcat(A93, ["RemoteLink", "RemoteDecoder"], "reconnect|retry open|start ")
    record(D, "切后台30秒回来自动重连", bool(logs.strip()),
           logs.strip().splitlines()[-1] if logs.strip() else "无重连日志")
    screencap(A93, "d2_after_bg30s")

    # 画质按钮（方向二顺带在被控端测）
    sh(ONE, "am start -n " + ACT); time.sleep(2)
    tap_text(ONE, "停止共享") and time.sleep(1)
    before = dump(ONE)
    ok = tap_text(ONE, "流畅")
    time.sleep(1)
    after = dump(ONE)
    record(D, "画质按钮可点选", ok and "当前：流畅" in after,
           "点'流畅'后当前档文案已切换" if "当前：流畅" in after else "文案未变，按钮可能仍失效")

    # ---------- 方向一：A93s 被控 ↔ 一加 控制 ----------
    D = "A93s被控→一加控制"
    print("== 方向一 ==")
    ok, info = start_controlled(A93)
    if not ok:
        record(D, "被控端启动", False, info); report(); return
    record(D, "被控端启动+录屏授权", True, "配对码 " + info)
    code = info
    ip = get_ip(A93)
    # A93s 无障碍：无 root 只能 UI 手动开，脚本尝试 su，失败则标记手动
    a11y = "RemoteControlService" in sh(A93, "dumpsys accessibility")
    if not a11y:
        sh(A93, f"su -c \"settings put secure enabled_accessibility_services {PKG}/com.yuyu.handlink.RemoteControlService\"")
        sh(A93, "su -c \"settings put secure accessibility_enabled 1\"")
        time.sleep(2)
        a11y = "RemoteControlService" in sh(A93, "dumpsys accessibility")
    clear_log(ONE); clear_log(A93)

    ok, info = controller_connect(ONE, ip, code)
    record(D, "一加发起连接", ok, info)
    ok = approve_consent(A93)
    record(D, "同意框弹出并可确认", ok, "")
    time.sleep(6)
    screencap(ONE, "d1_controller_view")

    vid = logcat(ONE, ["RemoteDecoder", "RemoteLink"], "start|first output|no output")
    record(D, "画面正常(解码器启动/出帧)", "first output frame rendered" in vid or "start " in vid,
           vid.strip().splitlines()[-1] if vid.strip() else "无日志")

    aud = logcat(ONE, ["HandLinkAudio"], "prebuffer|stats|config injected")
    record(D, "控制端有声音", "prebuffer filled" in aud or "player started" in aud,
           [l for l in aud.splitlines() if "stats" in l][-1] if "stats" in aud else "")

    if a11y:
        tap(ONE, 636, 1100); time.sleep(2)
        inp = logcat(A93, ["HandLinkInput"], "handleInput|dispatchGesture")
        record(D, "点击落点/注入", "handleInput" in inp,
               inp.strip().splitlines()[-1] if inp.strip() else "无注入日志")
        swipe(ONE, 636, 1400, 636, 600, 300); time.sleep(2)
        inp2 = logcat(A93, ["HandLinkInput"], "handleInput")
        record(D, "上滑生效", len(inp2) > len(inp), "")
    else:
        record(D, "触摸注入(需先在A93s手动开无障碍:设置→无障碍→已下载的应用→牵手远程·被控)", False, "MANUAL")

    # 切后台 30 秒自动重连（一加端）
    sh(ONE, "input keyevent KEYCODE_HOME"); time.sleep(32)
    sh(ONE, "am start -n " + ACT); time.sleep(6)
    logs = logcat(ONE, ["RemoteLink", "RemoteDecoder"], "reconnect|retry open|start ")
    record(D, "切后台30秒回来自动重连", bool(logs.strip()),
           logs.strip().splitlines()[-1] if logs.strip() else "无重连日志")

    report()

def report():
    lines = ["# 测试矩阵报告  %s\n" % time.strftime("%Y-%m-%d %H:%M")]
    w = max(len(r[1]) for r in results) if results else 10
    for d, item, st, ev in results:
        lines.append(f"- [{d}] {item}: **{st}**  {ev}")
    txt = "\n".join(lines)
    with open(os.path.join(OUT, "报告.md"), "w", encoding="utf-8") as f:
        f.write(txt)
    print("\n" + txt)
    print("\n截图与报告在 " + OUT)

if __name__ == "__main__":
    main()
