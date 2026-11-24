package com.yuyu.handlink

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Surface
import android.view.SurfaceHolder
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.runtime.DisposableEffect

class MainActivity : ComponentActivity() {

    private val notifLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val audioLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            android.util.Log.i("MainActivity", "RECORD_AUDIO granted=$granted")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        // ---- Shizuku 初始化：拿到 shell 权限后用真实 MotionEvent 注入触摸 ----
        // 这是"跟手"的前提。无障碍的 GestureDescription 会被系统识别/取消，
        // 永远做不到实时跟随。
        try {
            rikka.shizuku.Shizuku.addBinderReceivedListenerSticky {
                try {
                    if (rikka.shizuku.Shizuku.checkSelfPermission() ==
                        android.content.pm.PackageManager.PERMISSION_GRANTED
                    ) {
                        // 已授权：绑定 UserService（真正的触摸注入在 shell 进程里跑）
                        com.yuyu.handlink.ShizukuTouch.bind()
                    } else {
                        rikka.shizuku.Shizuku.requestPermission(1001)
                    }
                } catch (e: Throwable) {
                    android.util.Log.w("MainActivity", "shizuku init", e)
                }
            }
        } catch (e: Throwable) {
            android.util.Log.w("MainActivity", "shizuku not installed", e)
        }

        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) {
            notifLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        // 音频回退通道（Android 10 以下或 playback capture 失败时录麦克风）需要它
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            audioLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
        }
        setContent {
            MaterialTheme(colorScheme = handLinkScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) { AppRoot() }
            }
        }
        // 沉浸式全屏跟着全局状态走
        lifecycleScope.launch {
            AppState.immersive.collect { on -> applyImmersive(on) }
        }
    }

    override fun onResume() {
        super.onResume()
        AppState.uiVisible.value = true
        // 回到前台：Surface 是新的，通知连接重新挂上去（必要时自动重连）
        AppState.currentSurface?.let { s ->
            if (s.isValid) AppState.link.onForeground(s)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersive(AppState.immersive.value)
    }

    /** 沉浸式全屏：连上后画面铺满整块屏幕，状态栏和导航栏隐藏 */
    fun applyImmersive(on: Boolean) {
        if (Build.VERSION.SDK_INT >= 30) {
            val c = window.insetsController ?: return
            if (on) {
                window.setDecorFitsSystemWindows(false)
                c.hide(android.view.WindowInsets.Type.systemBars())
                c.systemBarsBehavior =
                    android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                window.setDecorFitsSystemWindows(true)
                c.show(android.view.WindowInsets.Type.systemBars())
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (on) (
                android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                    or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                ) else android.view.View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    /** 横竖屏切换：连上后可以横过来看，电视/电影更接近满屏 */
    fun applyOrientation(landscape: Boolean) {
        requestedOrientation = if (landscape) {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    /** 解除方向锁定，跟随系统自动旋转设置（控制端默认就是这个状态） */
    fun resetOrientation() {
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    override fun onPause() {
        super.onPause()
        AppState.uiVisible.value = false
    }
}

@Composable
fun AppRoot() {
    // 用 AppState 保存角色：切后台 Compose 组合会重建，remember 会丢，
    // 以前回来就被踢回选角色页（加上连接已断，看着就像黑屏）。
    val role by AppState.role.collectAsState()
    // 不再套外层 padding + verticalScroll：
    // 可滚动容器里 weight 会失效，底部主按钮就无法固定在屏幕下方。
    // 各界面自己管理边距，需要滚动的地方在页面内部滚动。
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        when (role) {
            null -> SelectRole(go = { AppState.role.value = it })
            "controlled" -> ControlledScreen(onBack = { AppState.role.value = null })
            else -> ControllerScreen(onBack = { AppState.role.value = null })
        }
    }

    // 同意对话框挂在根上：以前只在被控页渲染，用户停在首页/控制页时
    // 请求框根本不可见，超时自动拒绝（表现为"对方拒绝或超时"）。
    val consent by AppState.consentRequest.collectAsState()
    consent?.let { req ->
        AlertDialog(
            onDismissRequest = { }, // 不可外部取消，只能明确选择
            title = { Text("有人要控制你的手机") },
            text = {
                Text("「${req.name}」请求实时查看并操作本机屏幕。\n" +
                    "只有点“允许”后对方才能看到和操作；过程中状态栏有通知，可随时停止。")
            },
            confirmButton = {
                Button(onClick = {
                    AppState.consentRequest.value = null; req.decided(true)
                }) { Text("允许") }
            },
            dismissButton = {
                TextButton(onClick = {
                    AppState.consentRequest.value = null; req.decided(false)
                }) { Text("拒绝") }
            }
        )
    }
}

@Composable
fun SelectRole(go: (String) -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = Spec.pagePad),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.weight(1.15f))

        // 品牌区：图标 + 名字 + 一句话
        BrandMark()
        Spacer(Modifier.height(18.dp))
        Text(
            "牵手远程",
            fontSize = Spec.brandSize,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(6.dp))
        CaptionText("两台手机，一块屏幕", size = 13.sp)

        Spacer(Modifier.weight(0.85f))

        // 主按钮：被控（最常用场景）
        Button(
            onClick = { go("controlled") },
            modifier = Modifier.fillMaxWidth().height(Spec.buttonHeight),
            shape = Spec.buttonShape
        ) {
            Text("让这台手机被控制", fontSize = Spec.buttonTextSize, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = { go("controller") },
            modifier = Modifier.fillMaxWidth().height(Spec.buttonHeight),
            shape = Spec.buttonShape
        ) {
            Text("用这台手机控制对方", fontSize = Spec.buttonTextSize, fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.height(16.dp))
        CaptionText("首次使用需要开启无障碍权限")
        Spacer(Modifier.height(36.dp))
    }
}

/** 品牌标记：紫罗兰 H + 中间一颗心 */
@Composable
fun BrandMark(size: Dp = 104.dp) {
    val c = MaterialTheme.colorScheme.primary
    val bgColor = MaterialTheme.colorScheme.background
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val barW = w * 0.155f
        val barH = h * 0.70f
        val gapX = w * 0.255f
        val cx = w / 2f
        val cy = h / 2f
        val r = CornerRadius(barW / 2f, barW / 2f)

        // 两竖
        drawRoundRect(c, topLeft = Offset(cx - gapX - barW / 2f, cy - barH / 2f),
            size = Size(barW, barH), cornerRadius = r)
        drawRoundRect(c, topLeft = Offset(cx + gapX - barW / 2f, cy - barH / 2f),
            size = Size(barW, barH), cornerRadius = r)
        // 横杠
        val bh = h * 0.13f
        drawRoundRect(c, topLeft = Offset(cx - gapX, cy - bh / 2f),
            size = Size(gapX * 2f, bh),
            cornerRadius = CornerRadius(bh / 2f, bh / 2f))
        // 横杠上的心（用背景色挖出）
        drawHeart(Offset(cx, cy + h * 0.004f), w * 0.108f, bgColor)
    }
}

/** 画一颗心（多边形逼近） */
fun DrawScope.drawHeart(center: Offset, size: Float, color: Color) {
    val path = Path()
    val n = 90
    for (i in 0..n) {
        val t = (i.toFloat() / n) * 2f * Math.PI.toFloat()
        val st = Math.sin(t.toDouble()).toFloat()
        val sx = 16f * st * st * st
        val sy = 13f * Math.cos(t.toDouble()).toFloat() -
            5f * Math.cos(2.0 * t).toFloat() -
            2f * Math.cos(3.0 * t).toFloat() -
            Math.cos(4.0 * t).toFloat()
        val px = center.x + sx * size / 30f
        val py = center.y - sy * size / 30f
        if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
    }
    path.close()
    drawPath(path, color)
}


// ---------------- 被控端 ----------------

@Composable
fun ControlledScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val running by AppState.serviceRunning.collectAsState()
    val code by AppState.pairCode.collectAsState()
    val controller by AppState.activeController.collectAsState()
    val fps by AppState.fps.collectAsState()
    val relaySt by AppState.relayStatus.collectAsState()
    val projInterrupted by AppState.projectionInterrupted.collectAsState()
    var showSettings by remember { mutableStateOf(false) }

    val projLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK && res.data != null) {
            val i = Intent(ctx, CaptureService::class.java)
                .setAction(CaptureService.ACTION_START)
                .putExtra(CaptureService.EXTRA_RESULT_CODE, res.resultCode)
                .putExtra(CaptureService.EXTRA_RESULT_DATA, res.data)
            ctx.startForegroundService(i)
        }
    }

    // 权限状态必须实时刷新：用户从系统设置页授予权限返回后，
    // 如果只读一次，界面会一直停在"还需开启 N 项"，看起来像没生效。
    var overlayOk by remember { mutableStateOf(Settings.canDrawOverlays(ctx)) }
    var a11yOk by remember { mutableStateOf(accEnabled(ctx)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                overlayOk = Settings.canDrawOverlays(ctx)
                a11yOk = accEnabled(ctx)
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    // 无障碍服务绑定是系统异步回调，补一轮轮询，避免返回瞬间状态还没更新
    LaunchedEffect(Unit) {
        while (true) {
            delay(700L)
            val ov = Settings.canDrawOverlays(ctx)
            val a = accEnabled(ctx)
            if (ov != overlayOk) overlayOk = ov
            if (a != a11yOk) a11yOk = a
        }
    }
    val missing = (if (a11yOk) 0 else 1) + (if (overlayOk) 0 else 1)
    val readyAll = missing == 0

    if (showSettings) {
        ControlledSettings(onBack = { showSettings = false })
        return
    }

    Column(Modifier.fillMaxSize().fillMaxHeight().padding(horizontal = Spec.pagePad)) {

        // ---------- 顶栏 ----------
        Row(
            Modifier.fillMaxWidth().height(56.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "‹",
                fontSize = 22.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable { onBack() }.padding(end = 14.dp)
            )
            PageTitle("被控端", Modifier.weight(1f))
            Text(
                "⚙",
                fontSize = 19.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable { showSettings = true }.padding(6.dp)
            )
        }

        if (!running) {
            // ---------- 未开始：状态卡 ----------
            Card(
                Modifier.fillMaxWidth(),
                shape = Spec.cardShape,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 30.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    BrandMark(80.dp)
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "等待开始共享",
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(6.dp))
                    CaptionText("对方连接后可以看到并操作这台手机")
                }
            }
            Spacer(Modifier.height(Spec.gapM))

            // ---------- 权限条（合并成一条）----------
            if (projInterrupted) {
                Spacer(Modifier.height(12.dp))
                Card(
                    onClick = {
                        AppState.projectionInterrupted.value = false
                        val m = ctx.getSystemService(android.content.Context.MEDIA_PROJECTION_SERVICE)
                            as android.media.projection.MediaProjectionManager
                        projLauncher.launch(m.createScreenCaptureIntent())
                    },
                    shape = Spec.cardShape,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(
                            horizontal = Spec.cardPadH, vertical = 14.dp
                        ),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Canvas(Modifier.size(16.dp)) { drawCircle(Palette.Pending) }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "共享被系统暂停",
                                fontSize = Spec.bodySize,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.height(2.dp))
                            CaptionText("锁屏或来电后系统会暂停录屏，点这里恢复")
                        }
                        Text("\u203a", fontSize = 22.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            Card(
                Modifier.fillMaxWidth().clickable {
                    if (missing > 0) {
                        if (!a11yOk) {
                            ctx.startActivity(
                                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        } else {
                            ctx.startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:" + ctx.packageName)
                                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    }
                },
                shape = Spec.cardShape,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(
                        horizontal = Spec.cardPadH, vertical = 14.dp
                    ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Canvas(Modifier.size(16.dp)) {
                        drawCircle(if (missing == 0) Palette.Online else Palette.Pending)
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            when {
                                readyAll -> "权限已全部开启"
                                missing == 1 && a11yOk -> "还差悬浮窗权限"
                                missing == 1 && overlayOk -> "还差无障碍服务"
                                else -> "还差 2 项权限"
                            },
                            fontSize = Spec.bodySize,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(2.dp))
                        CaptionText(
                            when {
                                readyAll -> "可以开始共享了"
                                missing == 1 && a11yOk -> "不在前台时弹确认框要用"
                                missing == 1 && overlayOk -> "注入触摸必需"
                                else -> "无障碍服务 · 悬浮窗权限"
                            }
                        )
                    }
                    if (missing > 0) {
                        Text("›", fontSize = 22.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            Spacer(Modifier.height(Spec.gapM))

            // ---------- 使用说明 ----------
            Card(
                Modifier.fillMaxWidth(),
                shape = Spec.cardShape,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column(Modifier.padding(Spec.cardPadH, 16.dp)) {
                    Text(
                        "怎么用",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(8.dp))
                    CaptionText("开始后把配对码告诉对方")
                    Spacer(Modifier.height(4.dp))
                    CaptionText("对方连上就能看到这块屏幕")
                }
            }

            Spacer(Modifier.weight(1f))

            // ---------- 底部主按钮 ----------
            Button(
                onClick = {
                    val mgr = ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                        as MediaProjectionManager
                    val intent = mgr.createScreenCaptureIntent()
                    // 声明要抓"本机播放的音频"。不带这个标志时 MediaProjection 没有音频能力，
                    // AudioPlaybackCapture 的 AudioRecord 建得起来却永远收不到数据。
                    if (Build.VERSION.SDK_INT >= 29) {
                        intent.putExtra("android.media.projection.extra.EXTRA_AUDIO_CAPTURE", true)
                    }
                    projLauncher.launch(intent)
                },
                modifier = Modifier.fillMaxWidth().height(Spec.buttonHeight),
                shape = Spec.buttonShape
            ) {
                Text("开始共享", fontSize = Spec.buttonTextSize, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(24.dp))

        } else {
            // ---------- 共享中 ----------
            SharedScreen(
                code = code,
                controller = controller,
                fps = fps,
                relaySt = relaySt,
                onNewCode = {
                    AppState.pairCode.value = AppState.randomCode()
                    ctx.startService(
                        Intent(ctx, CaptureService::class.java)
                            .setAction(CaptureService.ACTION_NEW_CODE)
                    )
                },
                onStop = {
                    ctx.startService(
                        Intent(ctx, CaptureService::class.java)
                            .setAction(CaptureService.ACTION_STOP)
                    )
                }
            )
        }
    }
}

/** 共享中：配对码是绝对主角 */
@Composable
fun SharedScreen(
    code: String,
    controller: String?,
    fps: Int,
    relaySt: String,
    onNewCode: () -> Unit,
    onStop: () -> Unit
) {
    val ctx = LocalContext.current
    // IP 状态必须在函数顶层：放在 Column 内部会随重组被重置，
    // 表现为日志里已经取到地址但界面一直空白
    var ip by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        ip = localIpv4(ctx)
        var tries = 0
        while (ip == null && tries < 10) {
            delay(1000L)
            ip = localIpv4(ctx)
            tries++
        }
    }

    Column(Modifier.fillMaxSize()) {

        // 共享状态
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Canvas(Modifier.size(8.dp)) { drawCircle(Palette.Online) }
            Spacer(Modifier.width(6.dp))
            Text("共享中", fontSize = 12.sp, color = Palette.Online)
        }

        Spacer(Modifier.height(Spec.gapM))

        // 配对码卡
        Card(
            Modifier.fillMaxWidth(),
            shape = Spec.cardShape,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                Modifier.fillMaxWidth().padding(vertical = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CaptionText("把这个数字告诉对方")
                Spacer(Modifier.height(10.dp))
                Text(
                    code,
                    fontSize = Spec.codeSize,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 4.sp,
                    modifier = Modifier.clickable { onNewCode() }
                )
                Spacer(Modifier.height(10.dp))
                CaptionText(
                    "点按可换一个　·　中继 " + when (relaySt) {
                        "online" -> "在线"
                        "connecting" -> "连接中"
                        else -> "未启用"
                    },
                    color = if (relaySt == "online") Palette.Online
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                // 本机 IP：局域网直连时对方要填这个地址
                val ipShown = ip
                if (ipShown != null) {
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CaptionText("本机 IP：")
                        Text(
                            ipShown,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.clickable {
                                // 点一下就复制到剪贴板，省得手动记
                                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE)
                                    as android.content.ClipboardManager
                                cm.setPrimaryClip(
                                    android.content.ClipData.newPlainText("ip", ipShown)
                                )
                                android.widget.Toast.makeText(
                                    ctx, "IP 已复制", android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(Spec.gapM))

        // 连接方
        Card(
            Modifier.fillMaxWidth(),
            shape = Spec.cardShape,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Row(
                Modifier.fillMaxWidth().padding(Spec.cardPadH, 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Canvas(Modifier.size(30.dp)) {
                    drawCircle(
                        if (controller == null) Palette.Pending else Palette.Online,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 6f)
                    )
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (controller == null) "等待对方连接" else controller + " 已连接",
                        fontSize = Spec.bodySize,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(3.dp))
                    CaptionText(
                        if (controller == null) "把上面的配对码告诉对方"
                        else fps.toString() + " fps · 实时共享中"
                    )
                }
            }
        }

        Spacer(Modifier.weight(1f))

        // 结束共享（次要按钮，不抢眼但清楚）
        OutlinedButton(
            onClick = onStop,
            modifier = Modifier.fillMaxWidth().height(Spec.buttonHeight),
            shape = Spec.buttonShape,
            border = androidx.compose.foundation.BorderStroke(2.dp, Palette.Error.copy(alpha = 0.7f))
        ) {
            Text(
                "结束共享",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = Palette.Error
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** 被控端设置页：画质、中继地址 */
@Composable
fun ControlledSettings(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var qualityChoice by remember { mutableStateOf(VideoTuning.quality(ctx)) }

    Column(
        Modifier.fillMaxSize().padding(horizontal = Spec.pagePad)
            .verticalScroll(rememberScrollState())
    ) {
        Row(
            Modifier.fillMaxWidth().height(56.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "‹",
                fontSize = 22.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable { onBack() }.padding(end = 14.dp)
            )
            PageTitle("设置")
        }

        Text("画质", fontSize = 12.sp, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(8.dp))
        CaptionText("默认自动，会按手机性能选合适的清晰度")
        Spacer(Modifier.height(12.dp))

        // 两行两列
        listOf(
            listOf(VideoTuning.QUALITY_AUTO to "自动",
                   VideoTuning.QUALITY_SMOOTH to "流畅"),
            listOf(VideoTuning.QUALITY_BALANCED to "均衡",
                   VideoTuning.QUALITY_HD to "高清"),
        ).forEach { rowItems ->
            Row(
                Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                rowItems.forEach { (q, label) ->
                    val on = qualityChoice == q
                    if (on) {
                        Button(
                            { qualityChoice = q; VideoTuning.setQuality(ctx, q) },
                            Modifier.weight(1f).height(46.dp),
                            shape = Spec.chipShape
                        ) { Text(label, fontSize = 14.sp) }
                    } else {
                        OutlinedButton(
                            { qualityChoice = q; VideoTuning.setQuality(ctx, q) },
                            Modifier.weight(1f).height(46.dp),
                            shape = Spec.chipShape
                        ) { Text(label, fontSize = 14.sp) }
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        CaptionText("当前：" + VideoTuning.describe(ctx) + "　换档后需重新开始共享")

        Spacer(Modifier.height(24.dp))
        // 版本号：排查问题时先问"你装的是哪版"
        CaptionText(
            "版本 " + com.yuyu.handlink.BuildConfig.VERSION_NAME +
                " (" + com.yuyu.handlink.BuildConfig.VERSION_CODE + ") · " +
                com.yuyu.handlink.BuildConfig.BUILD_TIME,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )
        Spacer(Modifier.height(8.dp))

        Spacer(Modifier.height(28.dp))

        // ---- 后台保活（ColorOS 用户最容易踩的坑）----
        val oem = remember { OemCompat.detect(ctx) }
        Text("后台保活", fontSize = 12.sp, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(8.dp))
        CaptionText(
            if (oem.isColorOs)
                "${oem.brand} 系统会在后台冻结应用，导致共享中断。建议开启下面几项。"
            else
                "系统可能会清理后台，导致共享中断。建议开启下面几项。"
        )
        Spacer(Modifier.height(10.dp))
        OemCompat.keepAliveChecklist(ctx).forEach { line ->
            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                Text("·", fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 8.dp))
                Text(line, fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 19.sp)
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                {
                    if (!OemCompat.requestIgnoreBattery(ctx)) {
                        OemCompat.openAppDetail(ctx)
                    }
                },
                shape = RoundedCornerShape(Spec.buttonRadius),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp)
            ) { Text("省电白名单", fontSize = 13.sp) }
            if (oem.isColorOs) {
                OutlinedButton(
                    { OemCompat.openAppDetail(ctx) },
                    shape = RoundedCornerShape(Spec.buttonRadius),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp)
                ) { Text("耗电管理", fontSize = 13.sp) }
            }
        }
        Spacer(Modifier.height(16.dp))

        Card(
            Modifier.fillMaxWidth().clickable {
                qualityChoice = VideoTuning.QUALITY_MAX
                VideoTuning.setQuality(ctx, VideoTuning.QUALITY_MAX)
            },
            shape = Spec.cardShape,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Row(
                Modifier.fillMaxWidth().padding(Spec.cardPadH, 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("原画（不缩放）", fontSize = Spec.bodySize,
                        color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.height(2.dp))
                    CaptionText("性能较弱的手机可能卡顿")
                }
                if (qualityChoice == VideoTuning.QUALITY_MAX) {
                    Text("✓", fontSize = 18.sp, color = MaterialTheme.colorScheme.primary)
                }
            }
        }

        // 中继地址不在 UI 暴露：一切配置在后端（AppState.relayUrl 默认值），普通用户不需要看到
        Spacer(Modifier.height(24.dp))
    }
}

fun accEnabled(ctx: Context): Boolean {
    // 三重判断，因为不同 ROM 的行为不一样：
    // 1) 服务自己是否已连上（最准，绑定成功才有实例）
    if (RemoteControlService.isReady()) return true
    // 2) AccessibilityManager 列表（标准接口，但 ColorOS 有时返回空）
    try {
        val am = ctx.getSystemService(Context.ACCESSIBILITY_SERVICE)
            as android.view.accessibility.AccessibilityManager
        val wanted = ctx.packageName + "/" + RemoteControlService::class.java.name
        if (am.getEnabledAccessibilityServiceList(
                android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK
            ).any { it.id == wanted }
        ) return true
    } catch (e: Exception) {
        // 忽略，走下一层
    }
    // 3) 直接读系统设置（国产 ROM 上最可靠）
    return try {
        val v = android.provider.Settings.Secure.getString(
            ctx.contentResolver,
            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""
        v.contains(ctx.packageName) && v.contains("RemoteControlService")
    } catch (e: Exception) {
        false
    }
}

@Composable
fun PermRow(label: String, ok: Boolean, open: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(if (ok) "✅" else "⚪️", fontSize = 14.sp)
        Spacer(Modifier.width(6.dp))
        Text(label, Modifier.weight(1f), fontSize = 14.sp)
        if (!ok) OutlinedButton(open) { Text("去开启", fontSize = 13.sp) }
    }
}

// ---------------- 控制端（纯手势全屏观看，无任何按钮/输入框） ----------------

@Composable
fun ControllerScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val connState by AppState.connState.collectAsState()
    val rw by AppState.remoteW.collectAsState()
    val rh by AppState.remoteH.collectAsState()
    val fps by AppState.decodedFps.collectAsState()
    val peer by AppState.peerName.collectAsState()
    val controlsVisible by AppState.controlsVisible.collectAsState()
    val fill by AppState.fillScreen.collectAsState()
    val landscape by AppState.landscape.collectAsState()

    val link = AppState.link

    var relayMode by androidx.compose.runtime.remember { mutableStateOf(false) }
    var ip by remember { mutableStateOf(AppState.remoteIp.value) }
    var code by remember { mutableStateOf(AppState.remoteName.value) }
    var name by remember { mutableStateOf(Build.MODEL ?: "手机") }
    val remoteSurface = remember { androidx.compose.runtime.mutableStateOf<Surface?>(null) }

    val connected = connState == "connected" && rw > 0 && rh > 0

    LaunchedEffect(connected) { AppState.immersive.value = connected }
    // 方向永远交给用户/系统传感器：不锁竖屏、不强制横屏（用户明确要求）。
    // 连上后只默认铺满。
    LaunchedEffect(Unit) { (ctx as? MainActivity)?.resetOrientation() }
    LaunchedEffect(connected) {
        if (connected) AppState.fillScreen.value = true
    }
    // 顶部信息条自动淡出：3 秒后隐藏
    LaunchedEffect(connected, controlsVisible) {
        if (connected && controlsVisible) {
            delay(3000L)
            AppState.controlsVisible.value = false
        }
    }

    if (!connected) {
        // ---------- 未连接：连接表单 ----------
        // SurfaceView 必须存在（解码器要有输出目标），但不能参与布局：
        // 放在最外层的 Box 里，固定 1dp 挂到角落，视觉上不可见。
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.size(1.dp).align(Alignment.TopStart)) {
                SurfaceViewHost(remoteSurface, link, Modifier.fillMaxSize())
            }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = Spec.pagePad)
        ) {
            // 顶栏
            Row(
                Modifier.fillMaxWidth().height(56.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "‹",
                    fontSize = 22.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clickable { onBack() }.padding(end = 14.dp)
                )
                PageTitle("连接对方")
            }

            // 分段切换
            Row(
                Modifier.fillMaxWidth().height(46.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                listOf(false to "局域网", true to "异地中继").forEach { (mode, label) ->
                    val on = relayMode == mode
                    Box(
                        Modifier.weight(1f).fillMaxHeight()
                            .background(
                                if (on) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceVariant,
                                Spec.buttonShape
                            )
                            .clickable { relayMode = mode },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            label,
                            fontSize = 14.sp,
                            fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                            color = if (on) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(Modifier.height(Spec.gapL))

            // 配对码（大字输入）
            CaptionText("配对码")
            Spacer(Modifier.height(8.dp))
            Card(
                Modifier.fillMaxWidth(),
                shape = Spec.fieldShape,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                androidx.compose.foundation.text.BasicTextField(
                    value = code,
                    onValueChange = { v ->
                        code = v.filter { it.isDigit() }.take(6)
                    },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontSize = Spec.inputSize,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        letterSpacing = 6.sp
                    ),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(
                        MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier.fillMaxWidth().padding(
                        horizontal = Spec.cardPadH, vertical = 18.dp
                    ),
                    decorationBox = { inner ->
                        Box {
                            if (code.isEmpty()) {
                                Text(
                                    "输入 6 位数字",
                                    fontSize = Spec.inputSize,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                            }
                            inner()
                        }
                    }
                )
            }

            Spacer(Modifier.height(Spec.gapM))

            // 异地中继模式不再显示中继地址：配置在后端（AppState.relayUrl），用户只见配对码
            if (!relayMode) {
                CaptionText("对方 IP 地址")
                Spacer(Modifier.height(8.dp))
                Card(
                    Modifier.fillMaxWidth(),
                    shape = Spec.fieldShape,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    androidx.compose.foundation.text.BasicTextField(
                        value = ip,
                        onValueChange = { ip = it },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontSize = 15.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(
                            MaterialTheme.colorScheme.primary
                        ),
                        modifier = Modifier.fillMaxWidth().padding(
                            horizontal = Spec.cardPadH, vertical = 16.dp
                        ),
                        decorationBox = { inner ->
                            Box {
                                if (ip.isEmpty()) {
                                    Text(
                                        "对方屏幕上显示的 IP",
                                        fontSize = 15.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                    )
                                }
                                inner()
                            }
                        }
                    )
                }
                Spacer(Modifier.height(Spec.gapM))
            }

            CaptionText("显示给对方的名字")
            Spacer(Modifier.height(8.dp))
            Card(
                Modifier.fillMaxWidth(),
                shape = Spec.fieldShape,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                androidx.compose.foundation.text.BasicTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(
                        MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier.fillMaxWidth().padding(
                        horizontal = Spec.cardPadH, vertical = 16.dp
                    )
                )
            }

            if (connState != "idle" && connState.isNotEmpty()) {
                Spacer(Modifier.height(Spec.gapM))
                CaptionText(statusHint(connState))
            }

            // 滚动容器内不能用 weight 撑底，改固定间距
            Spacer(Modifier.height(Spec.gapL))

            Button(
                onClick = {
                    val s = remoteSurface.value
                    if (s != null && s.isValid && code.isNotBlank()) {
                        if (relayMode) {
                            // 中继地址来自后端配置，UI 不再暴露
                            link.connectRelay(AppState.relayUrl.value, code.trim(), code.trim(), name.trim(), s, ctx)
                        } else if (ip.isNotBlank()) {
                            link.connect(ip.trim(), code.trim(), name.trim(), s, ctx)
                        }
                    }
                },
                enabled = code.length >= 6 && (relayMode || ip.isNotBlank()),
                modifier = Modifier.fillMaxWidth().height(Spec.buttonHeight),
                shape = Spec.buttonShape
            ) {
                Text("连接", fontSize = Spec.buttonTextSize, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(24.dp))
        }
        }
    } else {
        // ---------- 已连接：纯画面全屏，所有操作靠手势，全部透传给被控端 ----------
        BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
            val bw = maxWidth
            val bh = maxHeight
            val ar = if (rw > 0 && rh > 0) rw.toFloat() / rh else 9f / 20f
            val vw: androidx.compose.ui.unit.Dp
            val vh: androidx.compose.ui.unit.Dp
            if (fill) {
                // 铺满：取较大者，超出裁剪，画面顶满整屏
                if (bw / bh > ar) { vw = bw; vh = bw / ar } else { vh = bh; vw = bh * ar }
            } else {
                // 完整：等比缩放进屏幕，留黑边
                if (bw / bh > ar) { vh = bh; vw = bh * ar } else { vw = bw; vh = bw / ar }
            }

            // 渲染方式：RTC 优先（自适应码率），关闭时用旧的自定义解码器。
            //
            // 绿屏坑：之前用 Modifier.size(vw, vh) 给 SurfaceView，
            // Dp→px 有取整误差，Surface 的 BufferQueue 尺寸和视频帧尺寸
            // 差 1~2px，硬件解码器直接输出绿屏（YUV 全部按 0 渲染）。
            //
            // 修复：Surface 用**精确像素**尺寸，并且必须和 rebuildDecoder
            // 传入的 w/h 完全一致。用 requiredSize 按 px 精确指定。
            val density = androidx.compose.ui.platform.LocalDensity.current
            val vwPx = with(density) { vw.roundToPx() }.coerceAtLeast(1)
            val vhPx = with(density) { vh.roundToPx() }.coerceAtLeast(1)
            // 注意：**不要**把 SurfaceView 尺寸喂给解码器。
            // 解码器必须用视频流本身的尺寸（720x1600），
            // SurfaceView 只是渲染目标，尺寸不同是正常的 —— SurfaceFlinger
            // 会自动做缩放。之前把 1272x2827 传给解码器，直接导致绿屏。
            if (AppState.useRtc.collectAsState().value) {
                RtcSurfaceHost(link, Modifier.size(vw, vh).align(androidx.compose.ui.Alignment.Center))
            } else {
                SurfaceViewHost(remoteSurface, link, Modifier.size(vw, vh).align(androidx.compose.ui.Alignment.Center))
            }

            // 全屏手势层：纯透传。每一次按下/移动/抬起都原样送到被控端，
            // 不做任何本地拦截（没有边缘返回手势、没有吞点击、没有双击转屏），
            // 手感 = 直接摸对方那块屏幕。
            @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
            Box(
                Modifier.fillMaxSize()
                    .pointerInput(connected, vw, vh) {
                        var downAt = 0L
                        var lastSent = 0L
                        var startX = 0f
                        var startY = 0f
                        var lastX = 0f
                        var lastY = 0f
                        var maxMoves = 0

                        // 坐标换算：必须基于**实际显示区域**，而不是视频比例。
                        //
                        // 踩过的坑：原来用 vw/vh（视频宽高比算出的尺寸）做归一化。
                        // "完整"模式下这样是对的，但"铺满"模式下 vw/vh 会比屏幕大，
                        // left/top 变成负数，坐标整体偏移 ——
                        // 表现为抖音里上下滑被识别成横向滑动，翻不动。
                        //
                        // 现在把 vw/vh 裁到屏幕范围：铺满时就是整块屏幕，
                        // 完整时就是居中的那条画面区域，两种情况都正确。
                        val vwPx = vw.toPx()
                        val vhPx = vh.toPx()
                        val effW: Float = if (vwPx < size.width) vwPx else size.width.toFloat()
                        val effH: Float = if (vhPx < size.height) vhPx else size.height.toFloat()
                        fun toNx(x: Float): Float {
                            val left = (size.width - effW) / 2f
                            return ((x - left) / effW).coerceIn(0f, 1f)
                        }
                        fun toNy(y: Float): Float {
                            val top = (size.height - effH) / 2f
                            return ((y - top) / effH).coerceIn(0f, 1f)
                        }

                        awaitPointerEventScope {
                            while (true) {
                                val ev = awaitPointerEvent(PointerEventPass.Initial)
                                val ch = ev.changes.firstOrNull() ?: break
                                when (ev.type) {
                                    PointerEventType.Press -> {
                                        downAt = System.currentTimeMillis()
                                        lastSent = 0L
                                        startX = ch.position.x
                                        startY = ch.position.y
                                        lastX = startX
                                        lastY = startY
                                        maxMoves = 0
                                        link.down(toNx(startX), toNy(startY))
                                    }
                                    PointerEventType.Move -> {
                                        // 补发本帧内的历史样本，让轨迹更贴近真实手指路径。
                                        // Compose 会把两帧之间的若干个采样点存在 historical 里，
                                        // 只用 ch.position 会丢掉这些中间点。
                                        for (h in ch.historical) {
                                            link.move(toNx(h.position.x), toNy(h.position.y))
                                        }
                                        lastX = ch.position.x
                                        lastY = ch.position.y
                                        maxMoves++
                                        // 采样间隔：4ms。原来 8ms 会漏掉部分中间点，
                                        // 快速滑动时的轨迹变得稀疏，被控端识别成点击的概率上升。
                                        // 4ms 能保证 60fps 下每帧至少上报一次，轨迹更连续。
                                        val now = System.currentTimeMillis()
                                        if (now - lastSent >= 4L) {
                                            lastSent = now
                                            link.move(toNx(lastX), toNy(lastY))
                                        }
                                    }
                                    PointerEventType.Release -> {
                                        lastX = ch.position.x
                                        lastY = ch.position.y
                                        // ================================================
                                        // 完全透传：抬起就发 up，不做任何本地判定。
                                        //
                                        // 需求原话："我要的是跟我屏幕一模一样"。
                                        //
                                        // 之前在这里做过 tap/lp(长按)/up 的三分支判定，
                                        // 结果凡是位移没超过阈值、或时间超长的滑动，
                                        // 都被转成了点击或长按 —— 表现就是"上滑变成点击"。
                                        //
                                        // 正确做法：控制端只负责**忠实上报触摸事件**，
                                        // 是点击、长按还是滑动，交给被控端的系统自己识别。
                                        // 这才是"跟我屏幕一模一样"。
                                        // ================================================
                                        link.move(toNx(lastX), toNy(lastY))
                                        link.up(toNx(lastX), toNy(lastY))
                                    }
                                    else -> {}
                                }
                                if (ch.pressed) ch.consume()
                            }
                        }
                    }
            )

            // 顶部信息条：只显示状态，3 秒后自动淡出
            AnimatedVisibility(
                visible = controlsVisible,
                enter = fadeIn(), exit = fadeOut(),
                modifier = Modifier.align(androidx.compose.ui.Alignment.TopCenter)
            ) {
                Row(
                    Modifier.fillMaxWidth()
                        .background(Color(0x99000000))
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Text(
                        "${fps}fps · 正在控制「$peer」" +
                            (if (landscape) " · 横屏" else " · 竖屏") +
                            (if (fill) " · 铺满" else " · 完整"),
                        color = Color(0xCCFFFFFF), fontSize = 12.sp
                    )
                }
            }
        }
    }
}

/**
 * WebRTC 视频渲染组件。
 *
 * 跟 SurfaceViewHost 并存：
 *   · useRtc = true  → 用这个（WebRTC 自适应码率，不卡不糊）
 *   · useRtc = false → 用旧的 SurfaceView + 自定义解码器
 *
 * SurfaceViewRenderer 是 WebRTC 提供的专用渲染器，内部维护 EGL 上下文，
 * 必须 init 后才能 addSink，直接在布局里放是不行的。
 */
@Composable
fun RtcSurfaceHost(
    link: RemoteLink,
    modifier: Modifier
) {
    AndroidView(
        modifier = modifier,
        factory = { c ->
            org.webrtc.SurfaceViewRenderer(c).apply {
                // 布局参数：让 WebRTC 自己按视频比例渲染，不拉伸
                try {
                    link.rtcViewer?.attachRenderer(this)
                } catch (e: Throwable) {
                    android.util.Log.w("MainActivity", "attachRenderer failed", e)
                }
            }
        },
        update = { r ->
            // viewer 可能在 factory 之后才创建（连接过程中），这里补挂
            try {
                link.rtcViewer?.attachRenderer(r)
            } catch (e: Throwable) { }
        },
        onRelease = { r ->
            try {
                link.rtcViewer?.detachRenderer()
                r.release()
            } catch (e: Throwable) { }
        }
    )
}

/** SurfaceView + SurfaceHolder 回调 */
@Composable
fun SurfaceViewHost(
    remoteSurface: androidx.compose.runtime.MutableState<Surface?>,
    link: RemoteLink,
    modifier: Modifier
) {
    Box(modifier) {
        AndroidView(
            factory = { c ->
                android.view.SurfaceView(c).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(h: SurfaceHolder) {
                            remoteSurface.value = h.surface
                            AppState.currentSurface = h.surface
                            link.onForeground(h.surface)
                        }
                        override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {
                            remoteSurface.value = h.surface
                            AppState.currentSurface = h.surface
                            if (!link.isActive) return
                            link.attachSurface(h.surface)
                        }
                        override fun surfaceDestroyed(h: SurfaceHolder) {
                            if (remoteSurface.value === h.surface) {
                                remoteSurface.value = null
                                AppState.currentSurface = null
                            }
                            link.detachSurface(h.surface)
                        }
                    })
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}

fun statusHint(connState: String): String = when {
    connState == "connecting" -> "连接中…"
    connState == "waiting" -> "已发出请求，等待对方点“允许”…"
    connState.startsWith("denied:") -> connState.removePrefix("denied:")
    connState.startsWith("error:") -> connState.removePrefix("error:")
    connState.startsWith("closed:") -> connState.removePrefix("closed:")
    else -> "被控端首页可查 IP 和配对码"
}
