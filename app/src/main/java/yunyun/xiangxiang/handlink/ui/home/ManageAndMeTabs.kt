package yunyun.xiangxiang.handlink.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.automirrored.outlined.ShortText
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.DriveFolderUpload
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.HighQuality
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Label
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import yunyun.xiangxiang.handlink.core.util.Formatters
import yunyun.xiangxiang.handlink.data.settings.ThemeMode
import yunyun.xiangxiang.handlink.ui.common.ConfirmDialog
import yunyun.xiangxiang.handlink.ui.common.Pill
import yunyun.xiangxiang.handlink.ui.common.SectionCard
import yunyun.xiangxiang.handlink.ui.common.SettingRow
import yunyun.xiangxiang.handlink.ui.theme.AppColors
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.ui.text.style.TextOverflow

object Routes {
    const val HOME = "home"
    const val SHARES = "shares"
    const val USERS = "users"
    const val TAGS = "tags"
    const val QUICK_TEXTS = "quick_texts"
    const val BATCH_FILES = "batch_files"
    const val STREAM_SETTINGS = "stream_settings"
    const val APP_SETTINGS = "app_settings"
    const val CERTS = "certs"
    const val ABOUT = "about"
    const val HOST = "host"
    fun shares(deviceId: String? = null, create: Boolean = false) =
        "$SHARES?deviceId=${deviceId.orEmpty()}&create=$create"
}

@Composable
fun ManageTab(vm: HomeViewModel, navigate: (String) -> Unit) {
    val devices by vm.devices.collectAsStateWithLifecycle()
    val tags by vm.tags.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCard("设备总数", devices.size.toString(), Modifier.weight(1f))
            StatCard("在线", devices.count { it.online }.toString(), Modifier.weight(1f), AppColors.Online)
            StatCard("正在看", devices.count { it.online && it.inUse }.toString(), Modifier.weight(1f), AppColors.Busy)
        }
        SectionCard(title = "访问控制") {
        }
        SectionCard(title = "设备组织") {
        }
        SectionCard(title = "批量操作") {
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier, color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(14.dp)) {
            Text(value, style = MaterialTheme.typography.headlineSmall, color = color, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun MeTab(vm: HomeViewModel, navigate: (String) -> Unit) {
    val me by vm.me.collectAsStateWithLifecycle()
    val app by vm.appSettings.collectAsStateWithLifecycle()
    var confirmLogout by remember { mutableStateOf(false) }
    var showPassword by remember { mutableStateOf(false) }
    val s = vm.session
    // 从加密存储里取回这台服务器 + 账号对应的密码
    val savedPassword = remember(s.username, s.serverUrl) { vm.savedPasswordFor(s.username) }
    val serverText = remember(s.serverUrl) {
        s.serverUrl.removePrefix("https://").removePrefix("http://").removeSuffix("/")
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        Card(
            Modifier.fillMaxWidth().padding(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            shape = MaterialTheme.shapes.large,
        ) {
            Column(Modifier.padding(20.dp)) {
                // 头像 + 一句话（单行）
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    androidx.compose.foundation.layout.Box(
                        Modifier
                            .size(48.dp)
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = androidx.compose.ui.Alignment.Center,
                    ) {
                        Text(
                            s.username.take(1).uppercase(),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimary,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "你独一无二，所以我万般珍惜",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }

                Spacer(Modifier.height(14.dp))
                androidx.compose.material3.HorizontalDivider(
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.12f),
                )
                Spacer(Modifier.height(14.dp))

                MeInfoLine("账号", s.username)
                Spacer(Modifier.height(10.dp))
                MeInfoLine("密码", savedPassword, masked = !showPassword) {
                    showPassword = !showPassword
                }
                Spacer(Modifier.height(10.dp))
                MeInfoLine("服务器", serverText)
            }

        }

        SectionCard(title = "投屏") {
            SettingRow(
                "画质与编码",
                "分辨率、帧率、码率、音频",
                Icons.Outlined.HighQuality,
                onClick = { navigate(Routes.STREAM_SETTINGS) },
            )
        }

        SectionCard(title = "受控") {
            SettingRow(
                "让对方看我的屏幕",
                "打开后，对方就能看到你的屏幕",
                Icons.Outlined.PhoneAndroid,
                onClick = { navigate(Routes.HOST) },
            )
        }

        SectionCard(title = "通用") {
            SettingRow(
                "外观",
                when (app.themeMode) {
                    ThemeMode.LIGHT -> "粉色"
                    ThemeMode.DARK -> "蓝色"
                    ThemeMode.CUSTOM -> "自定义"
                    ThemeMode.SYSTEM -> "跟随系统"
                },
                Icons.Outlined.DarkMode, onClick = { navigate(Routes.APP_SETTINGS) }
            )
            SettingRow("偏好设置", "振动反馈、剪贴板同步、自动重连…", Icons.Outlined.Tune, onClick = { navigate(Routes.APP_SETTINGS) })
            SettingRow("关于", null, Icons.Outlined.Info, onClick = { navigate(Routes.ABOUT) })
        }

        SectionCard {
            SettingRow(
                "退出登录", null,
                Icons.AutoMirrored.Outlined.Logout,
                tint = AppColors.Danger,
                onClick = { confirmLogout = true },
            )
        }
    }

    if (confirmLogout) ConfirmDialog(
        "退出登录", "将断开当前投屏并清除本机保存的登录凭据。", "退出", destructive = true,
        onConfirm = { vm.logout() }, onDismiss = { confirmLogout = false }
    )
}

/** 我的页信息行：标签 + 值（密码可切换显示） */
@Composable
private fun MeInfoLine(
    label: String,
    value: String,
    masked: Boolean = false,
    onToggle: (() -> Unit)? = null,
) {
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f),
            modifier = Modifier.width(52.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            if (masked) "•".repeat(value.length.coerceIn(6, 16)) else value.ifBlank { "—" },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.weight(1f),
        )
        if (onToggle != null) {
            IconButton(onClick = onToggle, modifier = Modifier.size(28.dp)) {
                Icon(
                    if (masked) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                    "显示密码",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f),
                )
            }
        }
    }
}
