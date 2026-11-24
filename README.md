# 牵手远程 HandLink

<p align="center">
  <img src="logo.svg" width="128" height="128" alt="HandLink" />
</p>

<h3 align="center">两台手机，一块屏幕</h3>

<p align="center">
  不管隔多远，都能看到对方在做什么。
</p>

---

## 这是什么

一个给两个人用的远程屏幕共享 App。

**只做三件事：**

1. **让对方看我的屏幕** —— 打开开关，对方就能实时看到你在做什么
2. **看对方的屏幕** —— 点一下设备，对方手机的画面就出现在你屏幕上
3. **直接操作** —— 不只是看，还能直接点、滑、打字

## 特点

| | |
|---|---|
| **低延迟** | 基于 scrcpy 内核 + WebRTC，延迟 100ms 级别 |
| **高清** | 最高 1920p / 60fps / 20Mbps，动态码率自适应 |
| **跨网络** | 异地也能用，支持 NAT 穿透和中继 |
| **免 Root** | 用系统录屏授权即可，不需要刷机 |
| **可选完整控制** | 装 Shizuku 后可以完整操作对方手机 |

## 怎么用

### 两台手机都要装

下载 APK，各自装好。

### 一台当「看的人」

1. 打开 App，填服务器地址
2. 登录
3. 点设备列表里的那台手机
4. 看画面，直接操作

### 一台当「被看的人」

1. 打开 App
2. 点底部「我的」→「让对方看我的屏幕」
3. 点「让对方连进来」
4. 系统弹出录屏授权 → 点「立即开始」

### 想要完整控制？（可选）

默认用系统录屏，只能看不太能操作。

想完整控制，被控端需要装 **Shizuku** 并激活：

1. 装 Shizuku App
2. 打开无线调试：设置 → 开发者选项 → 无线调试
3. 在 Shizuku 里点「配对」，输入 6 位配对码
4. 回到「牵手远程」→「我的」→「让对方看我的屏幕」→ 执行引擎选 **Shizuku**
5. 点「请求 Shizuku 授权」→ 允许

> 注意：Shizuku 在手机重启后需要重新激活一次（约 30 秒）

## 服务器

自建的信令服务 + TURN 中继：

```
信令：http://你的服务器:8443
```

服务端用 [HandLink](https://github.com/hhoneypot0-ui/HandLink) 的 Docker 镜像：

```bash
docker run -d --name cp-aio --network host --restart unless-stopped \
  -e PUBLIC_IP=你的公网IP \
  -e USE_TLS=false \
  -e SIGNALING_PORT=8443 \
  -e TURN_USER=用户名 \
  -e TURN_PASSWORD=密码 \
  buutuu/scrcpy-over-webrtc:latest
```

需要在防火墙放行：

| 协议 | 端口 | 用途 |
|---|---|---|
| TCP | 8443 | 信令服务器 |
| UDP | 3478 | TURN 中继 |

## 自己编译

```bash
# 需要 JDK 17、Android SDK 35
./gradlew :app:assembleDebug
```

产物在 `app/build/outputs/apk/debug/`

## 技术栈

- Kotlin 2.0.21 + Jetpack Compose
- WebRTC（H.264 硬件编解码）
- scrcpy 内核
- OkHttp + WebSocket

## 说明

本项目的技术方案和部分代码基于开源项目 [WebrtcPhone-Android](https://github.com/hqw700/WebrtcPhone-Android)
（作者 [hqw700](https://github.com/hqw700)，Apache-2.0）开发，
服务端使用 [ScrcpyOverWebRTC](https://github.com/hqw700/ScrcpyOverWebRTC)。

界面、品牌、功能和文案为本项目定制，底层依赖：

- [scrcpy](https://github.com/Genymobile/scrcpy) — Apache-2.0
- [Shizuku](https://github.com/RikkaApps/Shizuku) — Apache-2.0
- [WebRTC](https://webrtc.org/) — BSD-3-Clause

## 许可

Apache License 2.0

---

<p align="center">
  <sub>做给我们的</sub>
</p>