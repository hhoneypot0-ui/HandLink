# HandLink 架构与踩坑实录

这份文档记录了一个"手机互控 App"从 scrcpy 思路到 WebRTC、再回到原生 H264 管线的完整工程决策链。
所有结论都来自两台真机（低端 MTK Android 11 + 旗舰 Android 17）的实测日志。

## 1. 推流管线的三次转向

### v1：MediaProjection + MediaCodec 硬编 H264 + WebSocket（当前默认）
- 720x1600@45fps，6.8Mbps，`KEY_LATENCY=0`、无 B 帧（scrcpy 同款低延迟配置）；
- 新连接先补发 SPS/PPS（`BUFFER_FLAG_CODEC_CONFIG`）再要一个同步帧，否则解码器永远起不来；
- 静止画面保活：`KEY_REPEAT_PREVIOUS_FRAME_AFTER=100ms`，不然投屏页看起来"卡死"。

### v2：WebRTC（自适应码率）
动机：网络在变，固定码率必然翻车。OPPO 官方互控用的 ortc 内部同样是
BitrateAllocation / DynamicFrameRateManager 这套自适应思路。

### v3：混合现状
WebRTC 链路全通（ICE/DTLS/SRTP/DataChannel），但**官方预编译 AAR 不含 OpenH264**
（Google 因专利费从预编译产物里剥离），低端 MTK 机 `DefaultVideoEncoderFactory`
硬编探测又失败 → offer 里只剩 VP8 → 只剩软编 VP8 一条路 → CPU 打爆、画质糊。
因此当前默认 `useRtc=false` 走 v1 硬编管线；WebRTC 保留并继续攻关
（升级路线：换 M121+ 的社区预编译库或自编译带 H264 的 libwebrtc）。

## 2. "黑屏"根因链（0.4.12 → 0.4.21，十版逐层剥离）

一个"控制端黑屏"问题，实际叠了 7 层 bug，每修一层露出下一层：

1. **无日志**：ColorOS 屏蔽第三方 logcat → 所有关键事件改写
   `getExternalFilesDir()/rtc_diag.txt`，adb pull 取证；
2. **看门狗没启动**：`running` 标志从未置 true，线程 while 条件直接退出——
   教训：埋点本身也要被测试；
3. **双 offer 竞态**：`addTrack` 触发 `onRenegotiationNeeded` 自动发一次 offer，
   配对允许后又显式发一次 → 对端建两条接收轨，渲染器挂在死轨上
   （解码统计在涨、屏幕 0 帧的诡异现象由此而来）。
   修复：negotiating 锁 + signalingState 守卫 + 建轨期自动 offer 压制；
4. **渲染器补挂**：Compose 的 AndroidView 工厂执行时 track 早已到达，
   `attachRenderer` 只注册"未来回调"不给已存在的 track 加 sink → 永远 0 帧。
   修复：注册回调的同时立即补挂现有 track（removeSink+addSink 保幂等）；
5. **native 崩溃**：看门狗线程 close/recreate PeerConnection 与信令线程的
   `nativeAddIceCandidate` 竞争已失效的 native 指针 → SIGSEGV（fault addr 尾缀恒定，
   典型的同一失效基址）。Java 对象还活着 ≠ native 对象还活着。
   修复：`engineAlive` 标志守卫所有信令入口 + pcGate 锁串行化 pc 生命周期；
6. **码率失控**：关掉 CPU 过载探测后软编全速狂飙，80 秒灌出 236MB（≈24Mbps），
   热点上行被打爆 → ICE DISCONNECTED → 触摸通道（DataChannel）死亡，
   表现为"看着正常但点不动"。修复：encodings.maxBitrate 封顶 + 重开过载探测；
7. **终极真相**：以上全修完，低端 MTK 软编 VP8 依然又卡又糊——
   这不是 bug，是路线问题，回到 v1 硬编管线。

## 3. 音频：只传"它正在放的"，不传"它周围的"

- 需求红线：能听到对方手机里的视频/音乐声，**绝不做成电话**（不传麦克风/环境音）；
- 曾经的元凶：WebRTC `createAudioSource()` 默认抓麦克风上传，整条轨删除；
- 现方案：`AudioPlaybackCapture`（Android 10+）→ AAC 硬编 → WebSocket；
- 坑：部分 MTK 机型 playback capture 返回全零 → 加能量探针（peak 采样）诊断；
  AudioTrack 会"假死"（playState=1 但 write 恒 0）→ 2 秒无成功写入即重建 track，
  并在 USAGE_MEDIA / VOICE_COMMUNICATION / NOTIFICATION 三种属性间轮转；
- 新连接必须补发 AAC 的 AudioSpecificConfig，否则解码器永远起不来（静音假象）。

## 4. 触摸：纯透传哲学

- 控制端整屏一个 `pointerInput`，Press/Move/Release 原样序列化发送，
  **不做本地 tap/长按/滑动判定**——判定交给被控端系统，才"像摸自己的屏"；
- Move 补发 Compose 的 historical 采样点，4ms 节流；
- 被控端 `dispatchGesture`（无障碍）注入，坐标按被控端真实分辨率归一化还原；
- 无障碍服务在 force-stop/重装后会被系统解绑，App 启动时自检并引导重开。

## 5. 中继与 NAT

- 自建 Node 房间制信令中继（join/pair/accept + rtc-offer/answer/ice 透传）；
- coturn RESTful 凭证（HMAC-SHA1 时效密钥）做 TURN 兜底；
- 信令双通道同发（局域网 socket + 中继）：写死优先级的做法导致过
  "offer 发进没人听的通道、永远黑屏"；
- ICE 埋点全程落盘：`ice CONNECTED/DISCONNECTED/FAILED` + 每 4s bytesSent 曲线，
  是定位第 6 节码率失控的直接证据。

## 6. 工程方法

- **文件诊断日志**是被国产 ROM 屏蔽 logcat 后唯一可靠的观测面；
- **看门狗必须测到"它真的在跑"**（running 标志 bug 让看门狗静默了三版）；
- 每版必升 versionCode、装机必 force-stop、APK 文件名带构建时间；
- 双 agent 协作 + 交接文档（谁改哪些文件、坑清单、设备状态），
  本项目 HANDOFF 流程沉淀了大部分上述结论。
