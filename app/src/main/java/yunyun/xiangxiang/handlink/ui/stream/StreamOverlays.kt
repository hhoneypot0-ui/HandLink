package yunyun.xiangxiang.handlink.ui.stream

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import yunyun.xiangxiang.handlink.rtc.ConnectionPhase
import org.webrtc.SurfaceViewRenderer
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.graphicsLayer

/**
 * 视频区域：按帧宽高比等比居中；摄像头模式下 zoom>1 时放大并由父布局裁剪（数字变焦）。
 * SurfaceView 不支持 scale 变换，只能通过改变自身尺寸实现缩放。
 */
@Composable
fun VideoArea(videoView: android.view.View, ratio: Float, zoom: Float, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().clipToBounds(), contentAlignment = Alignment.Center) {
        Layout(content = {
            AndroidView(factory = { videoView }, modifier = Modifier)
        }) { measurables, c ->
            val maxW = c.maxWidth
            val maxH = c.maxHeight
            var w = maxW
            var h = (maxW / ratio).toInt()
            if (h > maxH) {
                h = maxH
                w = (maxH * ratio).toInt()
            }
            w = (w * zoom).toInt()
            h = (h * zoom).toInt()
            val p = measurables.first().measure(Constraints.fixed(w, h))
            layout(maxW, maxH) { p.place((maxW - w) / 2, (maxH - h) / 2) }
        }
    }
}

@Composable
fun ConnectingOverlay(phase: ConnectionPhase, retry: Int, deviceName: String, onExit: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            ConnectionLogo()
            Spacer(Modifier.height(10.dp))
            androidx.compose.material3.CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                color = Color.White.copy(alpha = 0.85f),
                strokeWidth = 2.dp,
            )
            Spacer(Modifier.height(10.dp))
            Text(deviceName, color = Color.White, style = MaterialTheme.typography.titleMedium)
            Text(
                (if (retry > 0) "第 $retry 次重连 · " else "") + when (phase) {
                    ConnectionPhase.Signaling -> "正在联系对方…"
                    ConnectionPhase.WaitingOffer -> "等对方接起来…"
                    ConnectionPhase.WaitingProjection -> "对方正在同意，稍等一下…"
                    ConnectionPhase.Negotiating -> "别急别急，快进来了哟"
                    ConnectionPhase.Connected -> "快好了，马上就有画面"
                    else -> "正在连…"
                },
                color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(24.dp))
            OutlinedButton(onClick = onExit) { Text("取消", color = Color.White) }
        }
    }
}

@Composable
private fun ConnectionLogo() {
    val animation = rememberInfiniteTransition(label = "连接动画")
    val alpha by animation.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "呼吸"
    )
    val breathe by animation.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.02f,
        animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "缩放"
    )
    Image(
        painter = painterResource(yunyun.xiangxiang.handlink.R.drawable.ic_app_logo),
        contentDescription = null,
        modifier = Modifier
            .size(120.dp)
            .graphicsLayer {
                this.alpha = alpha
                scaleX = breathe
                scaleY = breathe
            }
    )
}

@Composable
fun FailedOverlay(phase: ConnectionPhase.Failed, onRetry: () -> Unit, onExit: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.8f)), contentAlignment = Alignment.Center) {
        Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("连接断开了", color = Color.White, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(phase.message, color = Color.White.copy(alpha = 0.8f), textAlign = TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onExit) { Text("退出", color = Color.White) }
                if (!phase.authError) Button(onClick = onRetry) { Text("重新连接") }
            }
        }
    }
}

@Composable
fun Banner(text: String, color: Color, modifier: Modifier = Modifier) {
    Surface(color = color.copy(alpha = 0.9f), contentColor = Color.White, shape = MaterialTheme.shapes.small, modifier = modifier) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (color != Color.Gray) {
                CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp, color = Color.White)
                Spacer(Modifier.width(8.dp))
            }
            Text(text, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Suppress("unused")
private fun Modifier.ratio(r: Float) = this.aspectRatio(r)
