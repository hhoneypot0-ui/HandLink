@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package yunyun.xiangxiang.handlink.ui.stream

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import yunyun.xiangxiang.handlink.data.model.Device
import yunyun.xiangxiang.handlink.rtc.CameraParams
import yunyun.xiangxiang.handlink.rtc.channel.Keys
import yunyun.xiangxiang.handlink.ui.home.CameraResolutions
import yunyun.xiangxiang.handlink.ui.home.lensOptions
import kotlin.math.roundToInt
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.foundation.gestures.detectTapGestures

/**
 * 虚拟导航键：返回 / 主页 / 多任务。
 * 以浮动条的形态悬浮在画面上，**长按后可以拖着换位置**（类似系统悬浮按钮），
 * 位置记在设置里，下次进来还在原处。
 */
@Composable
fun NavKeys(vm: StreamViewModel) {
    val app by vm.settings.app.collectAsStateWithLifecycle()
    val haptic = LocalHapticFeedback.current
    var collapsed by rememberSaveable { mutableStateOf(false) }
    val items = listOf(
        Triple(Icons.AutoMirrored.Filled.ArrowBack, "返回", Keys.BACK),
        Triple(Icons.Filled.Circle, "主页", Keys.HOME),
        Triple(Icons.Filled.Menu, "多任务", Keys.APP_SWITCH),
    )

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val barW = with(density) { (if (collapsed) 44 else 172).dp.toPx() }
        val barH = with(density) { 44.dp.toPx() }
        val maxX = (constraints.maxWidth - barW).coerceAtLeast(0f)
        val maxY = (constraints.maxHeight - barH).coerceAtLeast(0f)

        var x by remember(app.navBarX) {
            mutableStateOf(
                if (app.navBarX < 0f) maxX * 0.5f
                else (app.navBarX * constraints.maxWidth).coerceIn(0f, maxX)
            )
        }
        var y by remember(app.navBarY) {
            mutableStateOf(
                if (app.navBarY < 0f) maxY - with(density) { 12.dp.toPx() }
                else (app.navBarY * constraints.maxHeight).coerceIn(0f, maxY)
            )
        }
        var dragging by remember { mutableStateOf(false) }
        var moved by remember { mutableStateOf(0f) }

        fun persist() {
            vm.settings.updateApp {
                it.copy(
                    navBarX = (x / constraints.maxWidth).coerceIn(0f, 1f),
                    navBarY = (y / constraints.maxHeight).coerceIn(0f, 1f),
                )
            }
        }

        Box(
            Modifier
                .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                .width(if (collapsed) 44.dp else 172.dp)
                .height(44.dp)
                .shadow(if (dragging) 12.dp else 6.dp, RoundedCornerShape(if (collapsed) 22.dp else 26.dp))
                .background(
                    if (collapsed) Color(0x70FFFFFF) else if (dragging) Color(0xE62A3242) else Color(0xB3202733),
                    RoundedCornerShape(if (collapsed) 22.dp else 26.dp)
                )
                .then(
                    if (collapsed) Modifier.border(1.2.dp, Color(0xAAFFFFFF), RoundedCornerShape(22.dp))
                    else if (dragging) Modifier.border(1.dp, Color(0x66FFFFFF), RoundedCornerShape(26.dp))
                    else Modifier
                )
                // 收起态：和悬浮球一样「按住就能拖」，不用先长按
                .pointerInput(collapsed) {
                    if (collapsed) {
                        detectDragGestures(
                            onDragStart = {
                                dragging = true
                                moved = 0f
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            },
                            onDragCancel = {
                                dragging = false
                                if (moved < 12f) collapsed = false
                            },
                            onDragEnd = {
                                dragging = false
                                if (moved < 12f) {
                                    // 基本没动 = 想展开
                                    collapsed = false
                                } else {
                                    x = if (x + barW / 2f < constraints.maxWidth / 2f) 0f else maxX
                                    persist()
                                }
                            },
                        ) { change, amount ->
                            change.consume()
                            moved += kotlin.math.abs(amount.x) + kotlin.math.abs(amount.y)
                            x = (x + amount.x).coerceIn(0f, maxX)
                            y = (y + amount.y).coerceIn(0f, maxY)
                        }
                    } else {
                        // 展开态有按键，长按才拖，避免误触
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                dragging = true
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            },
                            onDragCancel = {
                                dragging = false
                                x = if (x + barW / 2f < constraints.maxWidth / 2f) 0f else maxX
                                persist()
                            },
                            onDragEnd = {
                                dragging = false
                                x = if (x + barW / 2f < constraints.maxWidth / 2f) 0f else maxX
                                persist()
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                x = (x + amount.x).coerceIn(0f, maxX)
                                y = (y + amount.y).coerceIn(0f, maxY)
                            }
                        )
                    }
                }
                // 收起状态下点一下展开（单独一个 pointerInput，不影响拖动）
                .pointerInput(collapsed) {
                    if (collapsed) {
                        detectTapGestures(onTap = { collapsed = false })
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            if (collapsed) {
                DogBall(Modifier.size(30.dp))
            } else {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    items.forEach { (icon, label, key) ->
                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .combinedClickable(
                                    onClick = { vm.press(key) },
                                    onLongClick = { vm.longPress(if (key == Keys.BACK) Keys.MENU else key) },
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                icon, label,
                                tint = Color.White.copy(alpha = 0.9f),
                                modifier = Modifier.size(if (key == Keys.HOME) 18.dp else 22.dp)
                            )
                        }
                    }
                    Box(
                        Modifier.width(30.dp).fillMaxHeight().clickable { collapsed = true },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.KeyboardArrowDown, "收起",
                            tint = Color.White.copy(alpha = 0.55f),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
}

/** 导航条收起后的小狗：半透明球 + 手绘小狗，纯 Canvas，无图片资源 */
@Composable
private fun DogBall(modifier: Modifier = Modifier) {
    val ear = MaterialTheme.colorScheme.primary
    val fur = Color(0xFFFFF8F0)
    val eye = Color(0xFF3E302C)
    val blush = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)

    androidx.compose.foundation.Canvas(modifier) {
        val w = size.width
        val h = size.height
        fun px(x: Float) = x * w
        fun py(y: Float) = y * h

        // 垂耳
        drawOval(ear, topLeft = androidx.compose.ui.geometry.Offset(px(0.00f), py(0.26f)),
            size = androidx.compose.ui.geometry.Size(px(0.30f), py(0.60f)))
        drawOval(ear, topLeft = androidx.compose.ui.geometry.Offset(px(0.70f), py(0.26f)),
            size = androidx.compose.ui.geometry.Size(px(0.30f), py(0.60f)))
        // 头
        drawOval(fur, topLeft = androidx.compose.ui.geometry.Offset(px(0.12f), py(0.16f)),
            size = androidx.compose.ui.geometry.Size(px(0.76f), py(0.76f)))
        // 眼睛
        listOf(0.35f, 0.65f).forEach { cx ->
            drawCircle(eye, radius = w * 0.058f, center = androidx.compose.ui.geometry.Offset(px(cx), py(0.50f)))
            drawCircle(Color.White.copy(alpha = 0.9f), radius = w * 0.018f,
                center = androidx.compose.ui.geometry.Offset(px(cx) - w * 0.020f, py(0.48f)))
        }
        // 鼻子
        drawCircle(Color(0xFF46342E), radius = w * 0.062f, center = androidx.compose.ui.geometry.Offset(px(0.50f), py(0.63f)))
        // 嘴
        val sw = w * 0.022f
        drawArc(Color(0xFF78605A), startAngle = 0f, sweepAngle = 110f, useCenter = false,
            topLeft = androidx.compose.ui.geometry.Offset(px(0.42f), py(0.66f)),
            size = androidx.compose.ui.geometry.Size(px(0.08f), py(0.12f)),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = sw))
        drawArc(Color(0xFF78605A), startAngle = 70f, sweepAngle = 110f, useCenter = false,
            topLeft = androidx.compose.ui.geometry.Offset(px(0.50f), py(0.66f)),
            size = androidx.compose.ui.geometry.Size(px(0.08f), py(0.12f)),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = sw))
        // 腮红
        drawCircle(blush, radius = w * 0.07f, center = androidx.compose.ui.geometry.Offset(px(0.24f), py(0.66f)))
        drawCircle(blush, radius = w * 0.07f, center = androidx.compose.ui.geometry.Offset(px(0.76f), py(0.66f)))
    }
}

/**
 * 左侧边缘把手：常驻显示，点击打开“切换设备”侧边栏。
 * 只做点击不做滑动，避免与云手机自身的边缘返回手势冲突。
 */
@Composable
fun DrawerHandle(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(width = 22.dp, height = 64.dp)
            .background(Color(0xCC1F2633), androidx.compose.foundation.shape.RoundedCornerShape(topEnd = 12.dp, bottomEnd = 12.dp))
            .combinedClickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.ChevronRight, "切换设备", tint = Color.White, modifier = Modifier.size(18.dp))
    }
}

/** 可拖动悬浮球：点击展开工具面板，长按打开设备侧边栏，拖动后吸附屏幕左右边缘 */
@Composable
fun FloatingBall(onClick: () -> Unit, onLongClick: (() -> Unit)? = null) {
    val haptic = LocalHapticFeedback.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val ballPx = with(density) { 48.dp.toPx() }
        val maxX = constraints.maxWidth - ballPx
        val maxY = constraints.maxHeight - ballPx
        var x by remember { mutableStateOf(maxX) }
        var y by remember { mutableStateOf(maxY * 0.35f) }
        Box(
            Modifier
                .offset { IntOffset(x.coerceIn(0f, maxX).roundToInt(), y.coerceIn(0f, maxY).roundToInt()) }
                .size(48.dp)
                .shadow(6.dp, CircleShape)
                .background(Color(0x70FFFFFF), CircleShape)
                .border(1.2.dp, Color(0xAAFFFFFF), CircleShape)
                .pointerInputDrag(
                    onDrag = { dx, dy -> x = (x + dx).coerceIn(0f, maxX); y = (y + dy).coerceIn(0f, maxY) },
                    onEnd = { x = if (x + ballPx / 2 < constraints.maxWidth / 2f) 0f else maxX },
                )
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick?.let { cb -> { haptic.performHapticFeedback(HapticFeedbackType.LongPress); cb() } },
                ),
            contentAlignment = Alignment.Center
        ) {
            CatBall(Modifier.size(32.dp))
        }
    }
}

/**
 * 悬浮球里的小猫：纯 Canvas 手绘，半透明球体，颜色跟随主题。
 * 点击开工具面板，长按开切换设备，拖动可换位置。
 */
@Composable
private fun CatBall(modifier: Modifier = Modifier) {
    val fur = Color(0xFFFFF4EB)
    val earInner = MaterialTheme.colorScheme.primary
    val eye = Color(0xFF46343C)
    val blush = MaterialTheme.colorScheme.primary.copy(alpha = 0.42f)
    val nose = MaterialTheme.colorScheme.primary
    val mouth = Color(0xFF6E5862)

    androidx.compose.foundation.Canvas(modifier) {
        val w = size.width
        val h = size.height
        fun px(x: Float) = x * w
        fun py(y: Float) = y * h

        // 耳朵
        val earL = androidx.compose.ui.graphics.Path().apply {
            moveTo(px(0.10f), py(0.36f)); lineTo(px(0.20f), py(0.02f)); lineTo(px(0.46f), py(0.24f)); close()
        }
        val earR = androidx.compose.ui.graphics.Path().apply {
            moveTo(px(0.90f), py(0.36f)); lineTo(px(0.80f), py(0.02f)); lineTo(px(0.54f), py(0.24f)); close()
        }
        drawPath(earL, fur); drawPath(earR, fur)
        val inL = androidx.compose.ui.graphics.Path().apply {
            moveTo(px(0.17f), py(0.31f)); lineTo(px(0.24f), py(0.11f)); lineTo(px(0.38f), py(0.26f)); close()
        }
        val inR = androidx.compose.ui.graphics.Path().apply {
            moveTo(px(0.83f), py(0.31f)); lineTo(px(0.76f), py(0.11f)); lineTo(px(0.62f), py(0.26f)); close()
        }
        drawPath(inL, earInner); drawPath(inR, earInner)

        // 头
        drawOval(fur, topLeft = androidx.compose.ui.geometry.Offset(px(0.05f), py(0.18f)),
            size = androidx.compose.ui.geometry.Size(px(0.90f), py(0.77f)))

        // 腮红
        drawCircle(blush, radius = w * 0.085f, center = androidx.compose.ui.geometry.Offset(px(0.22f), py(0.68f)))
        drawCircle(blush, radius = w * 0.085f, center = androidx.compose.ui.geometry.Offset(px(0.78f), py(0.68f)))

        // 眼睛
        listOf(0.34f, 0.66f).forEach { cx ->
            drawCircle(eye, radius = w * 0.062f, center = androidx.compose.ui.geometry.Offset(px(cx), py(0.53f)))
            drawCircle(Color.White.copy(alpha = 0.9f), radius = w * 0.020f,
                center = androidx.compose.ui.geometry.Offset(px(cx) - w * 0.018f, py(0.51f)))
        }

        // 鼻子
        val nosePath = androidx.compose.ui.graphics.Path().apply {
            moveTo(px(0.46f), py(0.66f)); lineTo(px(0.54f), py(0.66f)); lineTo(px(0.50f), py(0.71f)); close()
        }
        drawPath(nosePath, nose)

        // 嘴（两段小弧）
        val strokeW = w * 0.022f
        drawArc(
            color = mouth, startAngle = 0f, sweepAngle = 110f, useCenter = false,
            topLeft = androidx.compose.ui.geometry.Offset(px(0.42f), py(0.67f)),
            size = androidx.compose.ui.geometry.Size(px(0.08f), py(0.11f)),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeW),
        )
        drawArc(
            color = mouth, startAngle = 70f, sweepAngle = 110f, useCenter = false,
            topLeft = androidx.compose.ui.geometry.Offset(px(0.50f), py(0.67f)),
            size = androidx.compose.ui.geometry.Size(px(0.08f), py(0.11f)),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeW),
        )
    }
}

private fun Modifier.pointerInputDrag(onDrag: (Float, Float) -> Unit, onEnd: () -> Unit): Modifier =
    this.pointerInput(Unit) {
        detectDragGestures(onDragEnd = onEnd, onDragCancel = onEnd) { change, amount ->
            change.consume()
            onDrag(amount.x, amount.y)
        }
    }

/** 摄像头监控控制条：镜头、分辨率、静音、镜像、抓拍、退出 */
@Composable
fun CameraBar(
    vm: StreamViewModel,
    device: Device?,
    onSnapshot: () -> Unit,
    onExit: () -> Unit,
) {
    val p = vm.cameraParams ?: return
    val lenses = remember(device) { device?.let { lensOptions(it) } ?: lensOptions(fakeDevice()) }
    val chip = FilterChipDefaults.filterChipColors(
        labelColor = Color.White, selectedContainerColor = Color.White, selectedLabelColor = Color.Black,
    )
    Column(Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.85f)).padding(vertical = 6.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            lenses.forEach { o ->
                val sel = if (o.lens.facing != p.facing) false
                    else if (o.lens.facing == "back") kotlin.math.abs(o.lens.zoom - p.zoom) < 0.15
                    else o.lens.id == p.cameraId || p.cameraId.isBlank()
                FilterChip(
                    selected = sel,
                    onClick = {
                        if (!sel) {
                            vm.toast("正在切换至 ${o.label}…")
                            vm.switchCamera(p.copy(facing = o.lens.facing, cameraId = o.lens.id, zoom = o.lens.zoom))
                        }
                    },
                    label = { Text(o.label) },
                    colors = chip
                )
            }
            CameraResolutions.forEach { (size, label) ->
                val selRes = p.size == size
                FilterChip(
                    selected = selRes,
                    onClick = {
                        if (!selRes) {
                            vm.toast("正在切换分辨率至 $label…")
                            vm.switchCamera(p.copy(size = size))
                        }
                    },
                    label = { Text(label) },
                    colors = chip
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            BarIcon(if (vm.audioOn) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff, "环境音") { vm.toggleAudio() }
            BarIcon(Icons.Filled.Flip, "镜像") { vm.mirrored = !vm.mirrored }
            BarIcon(Icons.Filled.CameraAlt, "抓拍", big = true, onClick = onSnapshot)
            BarIcon(Icons.Filled.FlipCameraAndroid, "翻转") {
                val front = p.facing == "front"
                vm.toast(if (front) "正在切换至后置主摄…" else "正在切换至前置自拍…")
                vm.switchCamera(p.copy(facing = if (front) "back" else "front", cameraId = if (front) "0" else "1", zoom = 1.0))
            }
            BarIcon(Icons.Filled.Close, "退出", onClick = onExit)
        }
        if (vm.cameraZoom > 1.01f) Text(
            "数字变焦 %.1fx（双指缩放）".format(vm.cameraZoom), color = Color.White.copy(alpha = 0.7f),
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
    }
}

@Composable
private fun BarIcon(icon: ImageVector, label: String, big: Boolean = false, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(if (big) 60.dp else 48.dp)) {
        Box(
            Modifier.size(if (big) 54.dp else 40.dp).background(if (big) Color.White else Color.White.copy(alpha = 0.12f), CircleShape),
            contentAlignment = Alignment.Center
        ) { Icon(icon, label, tint = if (big) Color.Black else Color.White) }
    }
}

private fun fakeDevice() = Device("", yunyun.xiangxiang.handlink.data.model.DeviceInfo(), true, "", null, 0, emptyList(), null, null)
