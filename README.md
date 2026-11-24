# 牵手远程 HandLink

> 两台手机，一块屏幕 —— 面向情侣/亲密关系的 Android 远程互控 App。
> 单 APK 双角色：任何一台手机既能当**被控端**（截屏+媒体声推流），也能当**控制端**（实时画面+纯触摸透传）。

English: A couple-oriented Android remote-control app. One APK, two roles: the *host* streams its screen
and media audio; the *viewer* sees a live picture and controls it with raw touch passthrough —
it should feel exactly like touching your own phone.

## 它解决什么问题

异地恋的两部手机，互相"牵手"：

- **被控端**开启共享后给出 6 位配对码，无需账号体系；
- **控制端**输入配对码 + 被控端 IP（局域网直连）或仅配对码（经中继服务器异地连接）；
- 被控端本机弹出**允许确认**（隐私红线：没有本机点允许，谁也看不到）；
- 连接后控制端整屏就是对方的手机：滑动、长按、点按 1:1 透传，**没有任何多余按钮、输入框、边缘手势**——手感目标 = "像操作自己的手机"。

## 功能现状（诚实版）

| 能力 | 状态 |
|---|---|
| 局域网 720p H264 硬编推流（6.8Mbps，低延迟配置） | ✅ 流畅清晰，触摸秒跟 |
| 被控端媒体声（AudioPlaybackCapture → AAC） | ✅ 只传媒体声，刻意不传麦克风（不做"电话"） |
| 无障碍 dispatchGesture 触摸注入 | ✅ Android 11+ 实测 |
| 配对确认弹窗 + 状态栏通知可随时停止 | ✅ |
| 自建中继（房间制 WebSocket 信令 + coturn TURN） | ⚙️ 基建已上线，公网场景调优中 |
| WebRTC 通道（自适应码率） | ⚙️ 链路已通；受限于官方预编译库不含 H264 软编，当前默认走原生 H264 管线 |

已知边界：被控端为低端 MTK 机型时，WebRTC 官方 AAR 硬编探测失败；
项目内已实现软编降级与看门狗，详见 `docs/ARCHITECTURE.md`。

## 架构速览

```
┌───────── 被控端 ─────────┐        ┌───────── 控制端 ─────────┐
│ MediaProjection          │        │  SurfaceView 全屏渲染     │
│  └ VirtualDisplay 720x1600│       │  pointerInput → 归一化触摸 │
│ MediaCodec H264 硬编 6.8M │─视频帧→│  MediaCodec 硬解          │
│ AudioPlaybackCapture AAC  │─媒体声→│  AudioTrack 低延迟播放     │
│ MediaProjection 音频       │        │                           │
│ HandlinkServer (WS :8723) │←─触摸──│  RemoteLink (WS client)   │
│ RemoteControlService      │        │                           │
│  └ dispatchGesture 注入    │        │                           │
└───────────┬──────────────┘        └───────────┬───────────────┘
            │        局域网直连 或 中继           │
            └──── relay.js (信令房间) + coturn (TURN 兜底) ────┘
                     WebRTC PeerConnection（视频+触摸 DataChannel）
```

三通道设计：**视频/音频**走 WebSocket 二进制帧（原生管线）或 WebRTC；
**触摸**走 WebSocket 文本或 WebRTC DataChannel；**信令**（offer/answer/ICE/配对）走同一通道复用。

## 目录结构

```
app/                  Android 应用（Kotlin + Jetpack Compose，单 APK 双角色）
relay/relay.js        房间制 WebSocket 信令中继（Node，无依赖之外的东西）
docs/                 架构与踩坑文档
tools/                PC 端联调脚本（WebRTC 观看端、中继协议仿真）
```

## 构建

1. Android Studio（AGP 8.x），`minSdk 29`（AudioPlaybackCapture 需要 Android 10+），仅 arm64；
2. 把 `AppState.kt` 里的 `YOUR_RELAY_HOST` 换成你自己的中继地址，
   `RtcEngine.kt` 里的 `REPLACE_WITH_YOUR_TURN_SECRET` 换成你的 coturn RESTful 密钥；
3. `./gradlew assembleDebug`。

中继部署：`node relay/relay.js`（默认 8724 端口），TURN 用 coturn `use-auth-secret` 模式，密钥与 App 一致。

## 隐私与安全设计

- 被控端每次连接都需本机弹窗允许，超时 60s 自动拒绝；
- 状态栏常驻通知，一键停止共享；
- 配对码一次性，可点按刷新；
- 刻意**不采集麦克风**：只传被控端正在播放的媒体声，避免变成"远程监听"；
- 中继只转发密文信令/帧，不落盘内容。

## 免责声明

本项目仅供学习研究与亲密关系内的互信使用。未经对方明确同意对他人设备进行远程控制
属于违法行为。使用者需自行确保符合当地法律与平台规则。

## License

MIT © HandLink contributors
