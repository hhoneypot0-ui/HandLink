import java.text.SimpleDateFormat
import java.util.Date

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.yuyu.handlink"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.yuyu.handlink"
        minSdk = 26
        targetSdk = 34
        // 版本规范：
        //   versionName 语义化：主.次.修订
        //     主 —— 不兼容变更
        //     次 —— 新功能（协议/权限/协商）
        //     修订 —— 参数调整与 bug 修复
        //   versionCode 单调递增，每次发版 +1，用于判断谁装的是新的
        //
        // 历史：
        //   0.1      初版
        //   0.2.0    双向解码能力协商、ColorOS 保活引导、安卓16 中断恢复
        //   0.2.1    拖动改分段续接（修"根本动不了"）
        //   0.2.2    中继码率 3Mbps 限流（修"卡"）
        //   0.2.3    H.264 High Profile（修"糊"）
        //   0.3.0    集成 Shizuku 真实触摸注入（修"划不动"），无障碍降级保留
        //   0.4.0    接入 WebRTC：自适应码率/帧率，解决"卡"和"糊"的根本方案
        //   0.5.0    改用官方 ScreenCapturerAndroid（对齐 ColorOS），码率策略优于它
        //   0.4.6    音频静音看门狗：playback capture 采到全零 6 秒且媒体流活跃
        //            → 自动切麦克风拾外放（修 A93s 被控端"链路通但静音"）
        //   0.4.7    按用户要求砍掉"打电话"效应：删 WebRTC 麦克风音频轨（人声环境音
        //            不再上传）；麦克风兜底改为默认关闭（仅 flag 文件开启）
        //   0.4.12   RTC 全链路埋点补齐：ICE 状态/offer/answer/forceCodec 所有静默分支
        //            写 rtc_diag.txt（实测音频通但视频0帧、旧日志无任何 RTC: 行）
        //   0.4.13   修复看门狗从未启动：running 标志无人置 true → 线程秒退。
        //            实测 0.4.12：offer h264=false（M74 AAR 软编不含 OpenH264，只剩 VP8），
        //            ICE CONNECTED 但 bytesSent 无从得知
        //   0.4.14   修双 offer 竞态：start() 时 addTrack 自动 offer + 配对后显式 offer
        //            同飞 → viewer 连收两个 offer 回两个 answer，SDP 对不齐 → 0 帧。
        //            negotiating 锁 + 非 STABLE 先回滚 + handleOffer/handleAnswer 状态守卫
        //   0.4.15   viewer 侧入站看门狗：inbound bytesReceived/framesReceived/
        //            framesDecoded/decoderImplementation 写 rtc_diag.txt
        //   0.4.16   根治双 offer：压住 start() 建轨期的自动 offer，只留配对后的显式 offer。
        //            0.4.15 实测 viewer framesDecoded 在涨但 EglRenderer 0 帧
        //            （渲染器挂在第一个 offer 的死接收轨上）
        //   0.4.17   渲染器补挂已存在的 videoTrack：offer 常先于 Compose attach 到达，
        //            0.4.16 实测 framesDecoded 涨但 EglRenderer 0 帧=sink 从未加上
        //   0.4.18   根治 nativeAddIceCandidate SIGSEGV（一加 viewer 三次闪退）：
        //            watchdog 线程 close/recreate pc 与 OkHttp 信令线程 addIceCandidate
        //            竞争 native pc_ 指针 → pcGate 锁串行化所有 pc 生命周期操作
        //   0.4.19   engineAlive 标志根治 SIGSEGV：pcGate 挡不住 factory.dispose
        //            （崩溃在 addIceCandidate 内部，锁已持有）——M74 的 native pc_
        //            在 factory 销毁后是野指针，唯一安全做法是所有信令入口先查标志
        //   0.4.20   码率失控根治：0.4.19 实测 host 80 秒 outbound 236MB(≈24Mbps)，
        //            热点上行打爆 → ICE DISCONNECTED → 触摸通道死。
        //            encodings.maxBitrate=4M + maxFramerate=30 + 重开 CPU 过载探测
        //   0.4.21 用户实测 0.4.20"超卡超模糊不跟手"：VP8 软编在 A93s CPU 打爆。
        //          默认 useRtc=false 切硬编 H264 旧管线（WebRTC 硬编攻关中）
        versionCode = 80
        versionName = "0.6.0"

        // ABI 过滤：只保留 arm64-v8a。
        // WebRTC 的 .so 每架构 5~8MB，四架构全打进去 APK 直接 35MB；
        // 一加15 / Reno14 都是 arm64，其余架构纯属浪费。
        ndk {
            abiFilters.clear()
            abiFilters.add("arm64-v8a")
        }
        // 构建时间，App 里直接显示，一眼看出装的是哪次编译
        val buildStamp = SimpleDateFormat("MM-dd HH:mm")
            .format(Date())
        buildConfigField("String", "BUILD_TIME", "\"$buildStamp\"")
    }

    packaging {
        jniLibs {
            // 保留 .so 原样，不二次压缩，避免破坏 ELF 结构加剧对齐问题
            useLegacyPackaging = false
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
        aidl = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.10"
    }

    // 输出文件名带上版本与时间：握手包不再混淆
    // 形如：牵手远程-0.2.3-(23)-1105-1431.apk
    applicationVariants.all {
        outputs.all {
            val o = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            val stamp = SimpleDateFormat("MMdd-HHmm")
                .format(Date())
            o.outputFileName = "牵手远程-${versionName}-(${versionCode})-$stamp.apk"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.02.00")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.java-websocket:Java-WebSocket:1.5.6")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Shizuku：拿到 shell 权限，用 InputManager.injectInputEvent 注入真实触摸事件。
    // 无障碍的 GestureDescription 只能"模拟一次完整手势"，会被系统识别/取消，
    // 永远做不到跟手；Shizuku 注入的是和真实手指完全同等的 MotionEvent。
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("dev.rikka.tools.refine:annotation:4.4.0")

    // WebRTC 官方库：解决"卡"和"糊"的根本方案。
    // 拆 ColorOS 的 ShareScreen 发现它用的是 com.oplus.ortc（基于 WebRTC），
    // 关键能力是 availableOutgoingBitrate / BitrateAllocation —— 实时探测带宽并动态调码率。
    // 我们用固定码率是开环，碰到网络波动就只能丢帧，画面自然又卡又糊。
    implementation("org.webrtc:google-webrtc:1.0.32006")
}
