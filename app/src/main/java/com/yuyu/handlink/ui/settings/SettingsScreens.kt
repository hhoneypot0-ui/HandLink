package com.yuyu.handlink.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import com.yuyu.handlink.R
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Feedback
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.ScreenLockPortrait
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Vibration
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yuyu.handlink.BuildConfig
import com.yuyu.handlink.core.net.CertificatePinStore
import com.yuyu.handlink.core.util.Formatters
import com.yuyu.handlink.data.model.UserPolicy
import com.yuyu.handlink.data.settings.QualityPreset
import com.yuyu.handlink.data.settings.SettingsRepository
import com.yuyu.handlink.data.settings.StreamSettings
import com.yuyu.handlink.data.settings.ThemeMode
import com.yuyu.handlink.ui.common.ConfirmDialog
import com.yuyu.handlink.ui.common.EmptyState
import com.yuyu.handlink.ui.common.SectionCard
import com.yuyu.handlink.ui.common.SettingRow
import com.yuyu.handlink.ui.common.SwitchRow
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Tune

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubScreen(
    title: String,
    onBack: () -> Unit,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
    floating: @Composable () -> Unit = {},
    snackbar: @Composable () -> Unit = {},
    content: @Composable (androidx.compose.foundation.layout.PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                actions = actions,
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        floatingActionButton = floating,
        snackbarHost = snackbar,
        containerColor = MaterialTheme.colorScheme.background,
        content = content,
    )
}

/** 画质设置（投屏页内面板复用 [StreamQualityEditor]） */
@Composable
fun StreamSettingsScreen(repo: SettingsRepository, policy: UserPolicy?, onBack: () -> Unit) {
    val s by repo.stream.collectAsStateWithLifecycle()
    SubScreen("画质与编码", onBack, actions = { TextButton(onClick = { repo.resetStream() }) { Text("恢复默认") } }) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            StreamQualityEditor(s, policy, onChange = { t -> repo.updateStream(t) })
            Text(
                "设置在下次连接时生效；投屏中修改会自动快速重连。管理员禁止修改的项目以服务端配置为准。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(20.dp)
            )
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun StreamQualityEditor(
    s: StreamSettings,
    policy: UserPolicy?,
    onChange: ((StreamSettings) -> StreamSettings) -> Unit,
    dark: Boolean = false,
) {
    val lockRes = policy?.forbidResolution == true
    val lockFps = policy?.forbidFps == true
    val lockBitrate = policy?.forbidBitrate == true
    val lockAudio = policy?.forbidAudio == true
    val presetLocked = lockRes || lockFps || lockBitrate
    var advanced by remember { mutableStateOf(false) }

    SectionCard(title = "画质（选一个就行）") {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            PresetOption(
                title = "省流量",
                desc = "网络不好的时候用，画面糊一点但很顺",
                preset = QualityPreset.SMOOTH,
                current = s.preset, enabled = !presetLocked, onChange = onChange,
            )
            PresetOption(
                title = "日常（推荐）",
                desc = "清晰又流畅，平时用这个就好",
                preset = QualityPreset.STANDARD,
                current = s.preset, enabled = !presetLocked, onChange = onChange,
            )
            PresetOption(
                title = "高清",
                desc = "网络好的时候用，画面很清楚",
                preset = QualityPreset.HIGH,
                current = s.preset, enabled = !presetLocked, onChange = onChange,
            )
            PresetOption(
                title = "原画",
                desc = "几乎不压缩，很费流量和性能",
                preset = QualityPreset.ORIGINAL,
                current = s.preset, enabled = !presetLocked, onChange = onChange,
            )
        }
    }

    SectionCard {
        SwitchRow(
            "传输声音", s.audio, { b -> onChange { it.copy(audio = b) } },
            subtitle = "对方能听到你这边的声音", enabled = !lockAudio,
        )
    }

    SectionCard {
        SettingRow(
            title = "高级设置",
            subtitle = if (advanced) "点这里收起" else "一般不用动，懂行再点开",
            icon = Icons.Outlined.Tune,
            onClick = { advanced = !advanced },
        )
        if (advanced) {
            SliderRow(
                "最大分辨率（长边）", s.maxSize.toFloat(), 0f..2560f, 8,
                label = if (s.maxSize == 0) "原始" else "${s.maxSize}px", enabled = !lockRes,
            ) { v -> onChange { it.copy(maxSize = snapSize(v), preset = QualityPreset.CUSTOM) } }
            SliderRow(
                "最大帧率", s.maxFps.toFloat(), 0f..120f, 7,
                label = if (s.maxFps == 0) "不限" else "${s.maxFps} fps", enabled = !lockFps,
            ) { v -> onChange { it.copy(maxFps = (v / 15).toInt() * 15, preset = QualityPreset.CUSTOM) } }
            SliderRow(
                "码率", s.bitrateMbps.toFloat(), 1f..40f, 38, label = "${s.bitrateMbps} Mbps", enabled = !lockBitrate,
            ) { v -> onChange { it.copy(bitrateMbps = v.toInt(), preset = QualityPreset.CUSTOM) } }
            SwitchRow(
                "动态码率 (BWE)", s.bwe, { b -> onChange { it.copy(bwe = b) } },
                subtitle = "根据网络状况自动调节 ${s.minBitrateMbps}–${s.maxBitrateMbps} Mbps，弱网更流畅",
                enabled = !lockBitrate,
            )
            if (s.bwe) {
                SliderRow("最低码率", s.minBitrateMbps.toFloat(), 1f..20f, 18, "${s.minBitrateMbps} Mbps", !lockBitrate) { v ->
                    onChange { it.copy(minBitrateMbps = v.toInt()) }
                }
                SliderRow("最高码率", s.maxBitrateMbps.toFloat(), 2f..60f, 57, "${s.maxBitrateMbps} Mbps", !lockBitrate) { v ->
                    onChange { it.copy(maxBitrateMbps = v.toInt()) }
                }
            }
            SwitchRow(
                "开启息屏连接", s.powerOff, { b -> onChange { it.copy(powerOff = b) } },
                subtitle = "唤醒录屏后自动切断真机显示背光 (防窥省电)",
            )
        }
    }
}

@Composable
private fun PresetOption(
    title: String,
    desc: String,
    preset: QualityPreset,
    current: QualityPreset,
    enabled: Boolean,
    onChange: ((StreamSettings) -> StreamSettings) -> Unit,
) {
    val selected = current == preset
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f) else Color.Transparent
            )
            .then(if (enabled) Modifier.clickable { onChange { it.withPreset(preset) } } else Modifier)
            .padding(horizontal = 12.dp, vertical = 12.dp),
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
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title, style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
            )
            Text(
                desc, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun snapSize(v: Float): Int {
    val steps = intArrayOf(0, 480, 720, 1080, 1280, 1600, 1920, 2560)
    return steps.minBy { kotlin.math.abs(it - v) }
}

@Composable
private fun SliderRow(
    title: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int, label: String,
    enabled: Boolean, onChange: (Float) -> Unit,
) {
    var local by remember(value) { mutableStateOf(value) }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Text(label + if (!enabled) " 🔒" else "", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = local, onValueChange = { local = it }, onValueChangeFinished = { onChange(local) },
            valueRange = range, steps = steps, enabled = enabled
        )
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun AppSettingsScreen(repo: SettingsRepository, onBack: () -> Unit) {
    val a by repo.app.collectAsStateWithLifecycle()
    val s by repo.stream.collectAsStateWithLifecycle()
    SubScreen("偏好设置", onBack) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            SectionCard(title = "外观") {
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(ThemeMode.SYSTEM to "跟随系统", ThemeMode.LIGHT to "浅色", ThemeMode.DARK to "深色").forEach { (m, l) ->
                        FilterChip(selected = a.themeMode == m, onClick = { repo.updateApp { it.copy(themeMode = m) } }, label = { Text(l) })
                    }
                }
            }
            SectionCard(title = "投屏") {
                SwitchRow("显示虚拟导航键", a.showNavBar, { b -> repo.updateApp { it.copy(showNavBar = b) } }, "画面下方显示 返回/主页/多任务", Icons.Outlined.ViewAgenda)
                SwitchRow("显示性能指标", a.showStats, { b -> repo.updateApp { it.copy(showStats = b) } }, "延迟、帧率、码率、丢包实时浮层", Icons.Outlined.Insights)
                SwitchRow("投屏时保持亮屏", a.keepScreenOn, { b -> repo.updateApp { it.copy(keepScreenOn = b) } }, null, Icons.Outlined.ScreenLockPortrait)
                SwitchRow("开启息屏连接", s.powerOff, { b -> repo.updateStream { it.copy(powerOff = b) } }, "唤醒录屏后自动切断真机显示背光 (防窥省电)", Icons.Outlined.DarkMode)
                SwitchRow("触控振动反馈", a.haptics, { b -> repo.updateApp { it.copy(haptics = b) } }, null, Icons.Outlined.Vibration)
                SwitchRow("剪贴板双向同步", a.clipboardSync, { b -> repo.updateApp { it.copy(clipboardSync = b) } }, "本机复制自动同步到云手机，反之亦然", Icons.Outlined.ContentPaste)
                SwitchRow("断线自动重连", a.autoReconnect, { b -> repo.updateApp { it.copy(autoReconnect = b) } }, "网络切换/抖动后自动恢复（最多 3 次）", Icons.Outlined.Sync)
            }
            SectionCard(title = "屏幕方向") {
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("auto" to "跟随画面", "portrait" to "竖屏", "landscape" to "横屏", "sensor" to "跟随重力").forEach { (v, l) ->
                        FilterChip(selected = a.orientationLock == v, onClick = { repo.updateApp { it.copy(orientationLock = v) } }, label = { Text(l) })
                    }
                }
            }
        }
    }
}

@Composable
fun CertificatesScreen(store: CertificatePinStore, onBack: () -> Unit) {
    val pins by store.pins.collectAsStateWithLifecycle()
    var removing by remember { mutableStateOf<String?>(null) }
    SubScreen("安全与证书", onBack) { pad ->
        if (pins.isEmpty()) {
            EmptyState(Icons.Outlined.Lock, "没有手动信任的证书", "使用系统受信任证书（如 Let's Encrypt）的服务器无需在此管理", Modifier.padding(pad))
        } else LazyColumn(Modifier.padding(pad)) {
            item {
                Text(
                    "以下自签证书由你手动核对指纹后信任。移除后再次连接需要重新确认。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                )
            }
            items(pins, key = { it.endpointKey }) { p ->
                SectionCard {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            Text(p.endpointKey, style = MaterialTheme.typography.titleSmall)
                            if (p.subject.isNotBlank()) Text(p.subject, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(6.dp))
                            Text(p.sha256, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                            if (p.pinnedAt > 0) Text("信任于 ${Formatters.dateTime(p.pinnedAt)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { removing = p.endpointKey }) { Icon(Icons.Outlined.Delete, "移除") }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
    removing?.let { key ->
        ConfirmDialog("移除信任", "移除 $key 的证书信任？", "移除", destructive = true,
            onConfirm = { store.remove(key) }, onDismiss = { removing = null })
    }
}

private const val GITHUB_URL = "https://github.com/hhoneypot0-ui/HandLink"
private const val DOCS_URL = "https://github.com/hhoneypot0-ui/HandLink/docs/"
private const val SITE_URL = "https://github.com/hhoneypot0-ui/HandLink"

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    SubScreen("关于", onBack) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(20.dp))
            Image(
                painter = painterResource(R.drawable.ic_app_logo),
                contentDescription = null,
                modifier = Modifier.size(72.dp)
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = androidx.compose.ui.res.stringResource(R.string.app_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "两台手机，一块屏幕",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))

            SectionCard {
                SettingRow("版本", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                SettingRow("包名", BuildConfig.APPLICATION_ID)
            }
            SectionCard(title = "给我们的话") {
                Text(
                    "这个 App 是专门做给我们的。\n" +
                        "不管隔多远，都能看到对方在做什么。",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp)
                )
            }
            SectionCard(title = "开源") {
                SettingRow(
                    "GitHub 仓库",
                    "hhoneypot0-ui/HandLink",
                    painter = painterResource(R.drawable.ic_github),
                    onClick = {
                        runCatching {
                            ctx.startActivity(
                                android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse("https://github.com/hhoneypot0-ui/HandLink")
                                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    }
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
