# WebRTC (JNI 反射调用 Java 层，必须整体保留)
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# Shizuku 的兼容进程入口由反射调用。
-keepclassmembers class rikka.shizuku.Shizuku {
    private static *** newProcess(java.lang.String[], java.lang.String[], java.lang.String);
}

# OkHttp
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# 终端 WebView JS 桥
-keepclassmembers class yunyun.xiangxiang.handlink.ui.stream.terminal.TerminalBridge {
    @android.webkit.JavascriptInterface <methods>;
}

# 发布包去掉 verbose/debug 日志
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}

# 应用自身的 JS 桥与数据模型（反射/序列化访问）
-keep class yunyun.xiangxiang.handlink.data.model.** { *; }
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# 保留 Compose 运行时需要的注解
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,AnnotationDefault
