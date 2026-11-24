package com.yuyu.handlink

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 设计规格 —— 全 App 统一的尺寸与文字层级
 *
 * 之前的问题是每个界面各写各的：圆角 12/14 混用，字号 12/13/14 混用，
 * 没有层级。这里集中定义，改一处全 App 生效。
 */
object Spec {

    // ---------- 圆角 ----------
    /** 卡片 */
    val cardRadius = 20.dp
    /** 输入框 */
    val fieldRadius = 18.dp
    /** 小标签 */
    val chipRadius = 14.dp
    /** 主按钮：全圆角（高度的一半左右） */
    val buttonRadius = 31.dp

    val cardShape = RoundedCornerShape(cardRadius)
    val fieldShape = RoundedCornerShape(fieldRadius)
    val chipShape = RoundedCornerShape(chipRadius)
    val buttonShape = RoundedCornerShape(buttonRadius)

    // ---------- 间隔 ----------
    val gapXs = 6.dp
    val gapS = 10.dp
    val gapM = 14.dp
    val gapL = 20.dp
    val gapXl = 28.dp

    /** 页面左右边距 */
    val pagePad = 24.dp

    // ---------- 尺寸 ----------
    val buttonHeight = 62.dp
    val cardPadH = 20.dp
    val cardPadV = 18.dp

    // ---------- 字号层级 ----------
    /** 页面标题 */
    val titleSize = 19.sp
    /** 首页大标题 */
    val brandSize = 31.sp
    /** 配对码大字 */
    val codeSize = 48.sp
    /** 输入框内容 */
    val inputSize = 26.sp
    /** 卡片主文字 */
    val bodySize = 14.sp
    /** 说明文字 */
    val captionSize = 11.sp
    /** 按钮文字 */
    val buttonTextSize = 17.sp
}

// ---------- 常用文字组件 ----------

@Composable
fun PageTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier,
        fontSize = Spec.titleSize,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onBackground
    )
}

@Composable
fun BodyText(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier,
        fontSize = Spec.bodySize,
        color = MaterialTheme.colorScheme.onSurface
    )
}

@Composable
fun CaptionText(
    text: String,
    modifier: Modifier = Modifier,
    color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant,
    size: TextUnit = Spec.captionSize
) {
    Text(text, modifier = modifier, fontSize = size, color = color)
}