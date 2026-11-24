# AGENTS.md

牵手远程（HandLink）Android 客户端。基于 WebRTC + scrcpy agent 的远程屏幕共享。

先读 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) 了解整体架构。

## 环境与构建

- JDK 17
- Android SDK 35
- Gradle 8.10.2（wrapper 自带）

```bash
./gradlew :app:assembleDebug
```

APK 产物：`app/build/outputs/apk/debug/牵手远程-v<版本>-debug.apk`

## 项目结构

```
app/src/main/java/com/yuyu/handlink/
├── core/          基础：网络、安全、工具
├── data/          数据层：API、模型、仓库、设置
├── rtc/           WebRTC：信令、会话、DataChannel
├── host/          被控端：屏幕采集、信令、无障碍
└── ui/            Compose 界面
```

## 关键约束

**触控坐标系**：使用设备逻辑分辨率（`device_info.displays[0]`），不是视频帧尺寸。用错会被 agent 静默丢弃。

**WebRTC 单线程**：所有 PeerConnection / DataChannel 操作必须在同一个 executor 上执行。跨线程会崩溃。

**EglBase 进程级复用**：EglBase 随会话释放会导致重连后渲染黑屏。

**DataChannel 关闭后访问会 SIGSEGV**：send/state 必须在锁内判断 closed，try/catch 拦不住 native 崩溃。

## 定制说明

本项目从 [HandLink](https://github.com/hhoneypot0-ui/HandLink) 定制而来，主要改动：

| 项目 | 原值 | 现值 |
|---|---|---|
| 包名 | `com.yuyu.handlink` | `com.yuyu.handlink` |
| 应用名 | 牵手远程 | 牵手远程 |
| 主题色 | `#3D7BFF`（蓝） | `#FF98BE`（粉） |
| 图标 | 蓝色云 | 粉色 H + 心形 |
| 底部导航 | 设备 / 管理 / 我的 | 手机 / 我的 |
| 功能 | 全套 | 精简到 5 项 |

## 服务端

```
信令：http://YOUR_SERVER_HOST:8443   # 部署时替换为你自己的服务器地址
镜像：buutuu/scrcpy-over-webrtc:latest
```