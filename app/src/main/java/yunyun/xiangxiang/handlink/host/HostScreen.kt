package yunyun.xiangxiang.handlink.host

import android.Manifest
import android.app.Activity
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.content.pm.PackageManager
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import yunyun.xiangxiang.handlink.ui.common.Pill
import yunyun.xiangxiang.handlink.ui.common.SectionCard
import yunyun.xiangxiang.handlink.ui.common.StatusDot
import yunyun.xiangxiang.handlink.ui.common.SwitchRow
import yunyun.xiangxiang.handlink.ui.theme.AppColors
import androidx.compose.ui.draw.clip

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HostScreen(
    vm: HostViewModel,
    currentServerUrl: String? = null,
    onBack: () -> Unit
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        vm.startProjectionStandby()
    }
    val touchReady by ProjectionAccessibilityService.connected.collectAsStateWithLifecycle()
    val standby by ProjectionStandbyService.state.collectAsStateWithLifecycle()
    var unattendedTicket by rememberSaveable { mutableStateOf<String?>(null) }
    val unattendedConsent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val ticket = unattendedTicket
        unattendedTicket = null
        if (ticket != null) {
            val data = result.data
            if (result.resultCode == Activity.RESULT_OK && data != null) {
                ProjectionHostService.start(context, ticket, data)
            } else {
                ProjectionStandbyService.reject(ticket)
            }
        }
    }

    // 没有内置 Root 组件时，自动回到系统录屏，避免用户卡在不可用的模式
    LaunchedEffect(state.hasAssets, state.prefs.runMode) {
        if (!state.hasAssets && state.prefs.runMode == RunMode.ROOT) {
            vm.updatePrefs { it.copy(runMode = RunMode.PROJECTION) }
        }
    }

    LaunchedEffect(state.toastMessage) {
        state.toastMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            vm.clearToast()
        }
    }

    if (state.errorMessage != null) {
        AlertDialog(
            onDismissRequest = { vm.clearError() },
            title = { Text("提示") },
            text = { Text(state.errorMessage.orEmpty()) },
            confirmButton = {
                TextButton(onClick = { vm.clearError() }) {
                    Text("确定")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("让对方看我的屏幕") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { vm.refreshState() }) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "刷新")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { innerPadding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp)
        ) {
            // 状态卡片
            HostStatusBanner(state)

            // 小白指引
            SectionCard(title = "三步就好") {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    GuideStep("1", "选好下面的方式（默认「系统录屏」就行）")
                    GuideStep("2", "点下面的大按钮")
                    GuideStep("3", "系统弹窗问「允许录屏」时，点「立即开始」")
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "弄好之后，对方手机上就会看到你的屏幕。想停下来就再点一次按钮。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // 核心控制按钮
            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                val isRunning = state.status == HostStatus.RUNNING || state.serviceRunning
                Button(
                    onClick = {
                        if (isRunning) {
                            vm.stopHost()
                        } else if (state.prefs.runMode != RunMode.PROJECTION) {
                            vm.startHost()
                        } else if (Build.VERSION.SDK_INT >= 33 &&
                            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                        ) {
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            vm.startProjectionStandby()
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = MaterialTheme.shapes.large,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isRunning) AppColors.Danger else AppColors.Brand
                    ),
                    enabled = !state.isBusy
                ) {
                    if (state.isBusy) {
                        CircularProgressIndicator(
                            color = Color.White,
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(if (isRunning) "正在停止..." else "正在启动...")
                    } else {
                        Icon(
                            if (isRunning) Icons.Outlined.PowerSettingsNew else Icons.Outlined.PowerSettingsNew,
                            null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (isRunning) "断开连接" else "让对方连进来",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                    }
                }
            }

            // 连接与信令配置
            SectionCard(title = "连接与信令配置") {
                Column(Modifier.padding(16.dp)) {
                    OutlinedTextField(
                        value = state.prefs.signalingUrl,
                        onValueChange = { vm.updatePrefs { p -> p.copy(signalingUrl = it) } },
                        label = { Text("信令服务器地址 (ws:// 或 wss://)") },
                        leadingIcon = { Icon(Icons.Outlined.Dns, null) },
                        enabled = !state.serviceRunning && !state.isBusy,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("例如 wss://192.168.1.100:8443") }
                    )

                    if (!currentServerUrl.isNullOrBlank() && currentServerUrl != state.prefs.signalingUrl) {
                        Spacer(Modifier.height(8.dp))
                        TextButton(
                            onClick = { vm.fillSignalingUrl(currentServerUrl) },
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                        ) {
                            Icon(Icons.Outlined.Check, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("填入当前登录的信令服务器: $currentServerUrl", style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = state.prefs.deviceId,
                        onValueChange = { vm.updatePrefs { p -> p.copy(deviceId = it) } },
                        label = { Text("自定义设备 ID (空则使用系统默认)") },
                        leadingIcon = { Icon(Icons.Outlined.Info, null) },
                        enabled = !state.serviceRunning && !state.isBusy,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = state.prefs.iceServers,
                        onValueChange = { vm.updatePrefs { p -> p.copy(iceServers = it) } },
                        label = { Text("ICE / STUN 穿透服务器 (可选)") },
                        leadingIcon = { Icon(Icons.Outlined.Hub, null) },
                        enabled = !state.serviceRunning && !state.isBusy,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("例如 stun:stun.l.google.com:19302") }
                    )
                }
            }

            // 执行引擎与保活选项
            SectionCard(title = "运行引擎与守护") {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text("选择执行引擎", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Column(Modifier.fillMaxWidth()) {
                        EngineOption(
                            title = "系统录屏（推荐）",
                            desc = "最简单，不用折腾权限，打开就能让对方看",
                            selected = state.prefs.runMode == RunMode.PROJECTION,
                            enabled = !state.serviceRunning && !state.isBusy,
                        ) { vm.updatePrefs { it.copy(runMode = RunMode.PROJECTION) } }
                        EngineOption(
                            title = "Shizuku（对方能操作你的手机）",
                            desc = "需要装 Shizuku 并授权，可以完整控制",
                            selected = state.prefs.runMode == RunMode.SHIZUKU,
                            enabled = !state.serviceRunning && !state.isBusy,
                        ) { vm.updatePrefs { it.copy(runMode = RunMode.SHIZUKU) } }
                        if (state.hasAssets) {
                            EngineOption(
                                title = "Root（进阶，一般不用）",
                                desc = "手机已刷 Magisk / KernelSU 才可用",
                                selected = state.prefs.runMode == RunMode.ROOT,
                                enabled = !state.serviceRunning && !state.isBusy,
                            ) { vm.updatePrefs { it.copy(runMode = RunMode.ROOT) } }
                        }
                    }

                    Spacer(Modifier.height(4.dp))
                    Text(
                        when (state.prefs.runMode) {
                            RunMode.PROJECTION -> "点下面的按钮，系统会问你「是否允许录屏」，点立即开始就好。"
                            RunMode.SHIZUKU -> "先点「请求 Shizuku 授权」，允许之后才能开始。"
                            RunMode.ROOT -> "需要手机已 Root，并在弹窗里授予权限。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (state.prefs.runMode == RunMode.SHIZUKU) {
                    TextButton(onClick = vm::requestShizukuPermission) {
                        Text("请求 Shizuku 授权")
                    }
                    Text(
                        "Shizuku 调试模式在重启后需要重新激活，不支持开机自启动。",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (state.prefs.runMode == RunMode.ROOT) SwitchRow(
                    title = "开机自启动（Root）",
                    enabled = !state.serviceRunning && !state.isBusy,
                    subtitle = "系统开机完成或本应用升级后自动拉起受控端服务",
                    checked = state.prefs.autoStart,
                    onChange = { vm.updatePrefs { p -> p.copy(autoStart = it) } }
                )
                if (state.prefs.runMode == RunMode.PROJECTION) {
                    TextButton(
                        enabled = standby.unattended || (standby.online && unattendedTicket == null),
                        onClick = {
                            if (standby.unattended) {
                                ProjectionHostService.stop(context)
                            } else {
                                val ticket = ProjectionStandbyService.signaling?.reserveUnattended()
                                if (ticket == null) {
                                    Toast.makeText(context, "请使用 WSS 上线，并先结束当前协助或待确认请求", Toast.LENGTH_SHORT).show()
                                } else {
                                    unattendedTicket = ticket
                                    runCatching {
                                        val manager = context.getSystemService(MediaProjectionManager::class.java)
                                        val intent = if (Build.VERSION.SDK_INT >= 34) {
                                            manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
                                        } else {
                                            manager.createScreenCaptureIntent()
                                        }
                                        unattendedConsent.launch(intent)
                                    }.onFailure {
                                        unattendedTicket = null
                                        ProjectionStandbyService.reject(ticket)
                                    }
                                }
                            }
                        }
                    ) {
                        Text(if (standby.unattended) "结束亮屏无人值守" else "开启亮屏无人值守")
                    }
                    Text(
                        "无人值守开启后，远端连接无需手机确认。请保持手机亮屏并解锁。",
                        modifier = Modifier.padding(horizontal = 16.dp),
                        style = MaterialTheme.typography.bodySmall
                    )
                    FlowRow {
                        TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) {
                            Text(if (touchReady) "远程触控已开启" else "开启远程触控")
                        }
                        TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_DISPLAY_SETTINGS)) }) {
                            Text("调整自动灭屏时间")
                        }
                    }
                } else SwitchRow(
                    title = "前台常驻与防休眠",
                    enabled = !state.serviceRunning && !state.isBusy,
                    subtitle = "显示常驻状态通知并持有 WakeLock，防止手机灭屏时被系统休眠杀死",
                    checked = state.prefs.keepAlive,
                    onChange = { vm.updatePrefs { p -> p.copy(keepAlive = it) } }
                )
            }

            // 终端实时日志
            SectionCard(title = "终端实时日志") {
                Column(Modifier.padding(14.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Terminal, null, modifier = Modifier.size(18.dp), tint = AppColors.Brand)
                            Spacer(Modifier.width(6.dp))
                            Text("运行输出 (tail -n 40)", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        }
                        Row {
                            IconButton(onClick = {
                                clipboard.setText(AnnotatedString(state.logs))
                                Toast.makeText(context, "日志已复制到剪贴板", Toast.LENGTH_SHORT).show()
                            }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Outlined.ContentCopy, contentDescription = "复制日志", modifier = Modifier.size(16.dp))
                            }
                            IconButton(onClick = { vm.refreshState() }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Outlined.Refresh, contentDescription = "刷新日志", modifier = Modifier.size(16.dp))
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp, max = 240.dp),
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF0F141C)
                    ) {
                        SelectionContainer {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .padding(10.dp)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                Text(
                                    text = state.logs.ifBlank { "暂无日志" },
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = Color(0xFF81C784),
                                    lineHeight = 16.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HostStatusBanner(state: HostUiState) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.large
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val dotColor = when (state.status) {
                        HostStatus.RUNNING -> AppColors.Online
                        HostStatus.STARTING -> AppColors.Warning
                        HostStatus.STOPPED -> AppColors.Danger
                    }
                    StatusDot(dotColor, size = 12)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        when (state.status) {
                            HostStatus.RUNNING -> if (state.prefs.runMode == RunMode.PROJECTION) "已上线" else "正在受控运行中"
                            HostStatus.STARTING -> "正在启动中..."
                            HostStatus.STOPPED -> "未运行 (已停止)"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (state.agentPid.isNotEmpty()) {
                    Pill("PID: ${state.agentPid}", AppColors.Brand, filled = false)
                }
            }

            if (state.prefs.runMode == RunMode.PROJECTION && state.logs.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(state.logs, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(14.dp))

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                InfoChip("📱 ${state.deviceModel}")
                InfoChip("🌐 ${state.localIp}")
                InfoChip("🤖 ${state.androidVersion}")
                val engineDesc = when (state.prefs.runMode) {
                    RunMode.PROJECTION -> "系统录屏"
                    RunMode.ROOT -> if (state.rootStatus == AgentController.RootStatus.GRANTED) "Root: 已授权" else "Root: 未授权"
                    RunMode.SHIZUKU -> when (state.shizukuStatus) {
                        AgentController.ShizukuStatus.RUNNING_AND_GRANTED -> "Shizuku: 已激活"
                        AgentController.ShizukuStatus.RUNNING_WAITING_PERMISSION -> "Shizuku: 待授权"
                        AgentController.ShizukuStatus.NOT_RUNNING -> "Shizuku: 未运行"
                    }
                }
                InfoChip("⚡ $engineDesc")
            }
        }
    }
}

@Composable
private fun InfoChip(text: String) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun EngineOption(
    title: String,
    desc: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f) else Color.Transparent
            )
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(20.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(
                    if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(
                    Icons.Outlined.Check, null,
                    tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(14.dp),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title, style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
            )
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun GuideStep(num: String, text: String) {
    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(22.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(num, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
