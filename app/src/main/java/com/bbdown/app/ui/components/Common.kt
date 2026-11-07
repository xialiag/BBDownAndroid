package com.bbdown.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bbdown.app.ui.theme.AppTheme
import com.bbdown.app.ui.theme.Dimens
import kotlin.math.abs

/** 可点击并带轻微缩放反馈的容器：模拟 CSS 的 :active{transform:scale(.93)} */
@Composable
fun PressableBox(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    pressedScale: Float = 0.96f,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) pressedScale else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 900f),
        label = "pressScale",
    )
    Box(
        modifier = modifier
            .scale(scale)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
    ) { content() }
}

/** 主按钮：对应 .btn / .btn-primary / .btn-sec / .btn-danger */
enum class ButtonKind { Primary, Secondary, Danger, Ghost }

@Composable
fun AppButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.Secondary,
    enabled: Boolean = true,
    fontSize: Int = 13,
) {
    val c = AppTheme.colors
    val (bg, fg, borderColor) = when (kind) {
        ButtonKind.Primary -> Triple(c.accentBtn, Color.White, c.accentBtn)
        ButtonKind.Secondary -> Triple(Color.Transparent, c.fg, c.borderLight)
        ButtonKind.Danger -> Triple(Color.Transparent, c.error, c.error)
        ButtonKind.Ghost -> Triple(Color.Transparent, c.fgDim, Color.Transparent)
    }
    PressableBox(
        onClick = { if (enabled) onClick() },
        enabled = enabled,
        modifier = modifier
            .alpha(if (enabled) 1f else 0.4f)
            .clip(RoundedCornerShape(Dimens.radiusMd))
            .background(bg)
            .border(1.dp, borderColor, RoundedCornerShape(Dimens.radiusMd))
            .padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        Text(text, color = fg, fontSize = fontSize.sp, fontWeight = FontWeight.Medium)
    }
}

/** 胶囊选择项：对应 .pill */
@Composable
fun Pill(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = AppTheme.colors
    val bg by animateColorAsStateSafe(if (selected) c.bgActive else c.pillBg)
    val fg by animateColorAsStateSafe(if (selected) c.accent else c.fgDim)
    PressableBox(
        onClick = onClick,
        modifier = modifier
            .clip(RoundedCornerShape(percent = Dimens.radiusPill))
            .background(bg)
            .border(1.dp, if (selected) c.accent else c.pillBorder, RoundedCornerShape(percent = Dimens.radiusPill))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) { Text(text, color = fg, fontSize = 12.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal) }
}

/** 状态徽标：运行中带呼吸光圈，完成/失败静态 */
@Composable
fun StatusBadge(text: String, color: Color, pulsing: Boolean = false) {
    val infinite = rememberInfiniteTransition(label = "badge")
    val pulse by infinite.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
        label = "pulse",
    )
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(percent = Dimens.radiusPill))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 7.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(5.dp)
                .alpha(if (pulsing) pulse else 1f)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.width(4.dp))
        Text(text, color = color, fontSize = 10.5.sp, fontWeight = FontWeight.Medium)
    }
}

/** 分区标题 */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    val c = AppTheme.colors
    Text(
        text,
        modifier = modifier.padding(top = 14.dp, bottom = 6.dp),
        color = c.fgTitle,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
    )
}

/** 空状态 */
@Composable
fun EmptyState(text: String, modifier: Modifier = Modifier) {
    val c = AppTheme.colors
    Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, color = c.fgDim, fontSize = 13.sp)
    }
}

/** 加载中 */
@Composable
fun LoadingRow(text: String = "加载中…", modifier: Modifier = Modifier) {
    val c = AppTheme.colors
    Row(
        modifier.padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            color = c.accent,
            strokeWidth = 2.dp,
        )
        Spacer(Modifier.width(8.dp))
        Text(text, color = c.fgDim, fontSize = 12.sp)
    }
}

/** 错误条 + 重试 */
@Composable
fun ErrorRow(message: String, onRetry: (() -> Unit)?, modifier: Modifier = Modifier) {
    val c = AppTheme.colors
    Row(
        modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(message, color = c.error, fontSize = 12.sp, modifier = Modifier.weight(1f), maxLines = 3, overflow = TextOverflow.Ellipsis)
        if (onRetry != null) {
            Spacer(Modifier.width(8.dp))
            AppButton("重试", onRetry, fontSize = 11)
        }
    }
}

/** 进度条：宽度/颜色都做平滑过渡，避免数值跳变导致的视觉抖动 */
@Composable
fun SmoothProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    height: Dp = 4.dp,
    color: Color? = null,
    trackColor: Color? = null,
) {
    val c = AppTheme.colors
    // 关键：进度值用动画插值推进，轮询拿到新值也不会突跳
    val animated by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 420, easing = androidx.compose.animation.core.FastOutSlowInEasing),
        label = "progress",
    )
    val accent = color ?: c.accent
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(percent = 50))
            .background(trackColor ?: c.border)
    ) {
        Box(
            Modifier
                .fillMaxWidth(animated)
                .height(height)
                .clip(RoundedCornerShape(percent = 50))
                .background(Brush.horizontalGradient(listOf(accent.copy(alpha = 0.85f), accent)))
        )
    }
}

/** 顶部标题栏的一个图标按钮（带按压反馈 + 选中项滑入指示条） */
@Composable
fun ActivityIconButton(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: @Composable (Color) -> Unit,
) {
    val c = AppTheme.colors
    val color by animateColorAsStateSafe(if (selected) c.accent else c.fgDim)
    Box(modifier.size(Dimens.activityBtn), contentAlignment = Alignment.Center) {
        PressableBox(onClick = onClick, modifier = Modifier.size(Dimens.activityBtn)) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { icon(color) }
        }
        // 选中指示条：高度/透明度一起动画，切换时有种"滑过去"的感觉
        val barHeight by animateFloatAsState(if (selected) 1f else 0f, spring(stiffness = 500f), label = "barH")
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .width(3.dp)
                .height(28.dp * barHeight)
                .alpha(barHeight)
                .clip(RoundedCornerShape(topEnd = 3.dp, bottomEnd = 3.dp))
                .background(c.accent)
        )
    }
}

/** Toast：从底部滑入 + 淡入，2.2s 后自动消失 */
@Composable
fun AppToast(message: String?, isError: Boolean, onDismiss: () -> Unit) {
    val c = AppTheme.colors
    androidx.compose.runtime.LaunchedEffect(message) {
        if (message != null) {
            kotlinx.coroutines.delay(2200)
            onDismiss()
        }
    }
    AnimatedVisibility(
        visible = message != null,
        enter = slideInVertically(initialOffsetY = { it / 2 }) + fadeIn(tween(180)),
        exit = slideOutVertically(targetOffsetY = { it / 2 }) + fadeOut(tween(160)),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                message ?: "",
                color = if (isError) c.dangerFg else c.fgBright,
                fontSize = 12.5.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(Dimens.radiusXl))
                    .background(if (isError) c.dangerBg else c.bgTitlebar)
                    .border(1.dp, if (isError) c.error.copy(alpha = 0.5f) else c.borderLight, RoundedCornerShape(Dimens.radiusXl))
                    .padding(horizontal = 14.dp, vertical = 9.dp),
            )
        }
    }
}

/** 列表项入场：逐项延迟淡入 + 上移，避免长列表一次性"砸"出来 */
@Composable
fun StaggeredItem(index: Int, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    var visible by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        kotlinx.coroutines.delay((index.coerceAtMost(12) * 28).toLong())
        visible = true
    }
    val anim by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(260, easing = androidx.compose.animation.core.FastOutSlowInEasing),
        label = "itemIn",
    )
    Box(
        modifier
            .alpha(anim)
            .padding(top = ((1f - anim) * 10).dp)
    ) { content() }
}

/** 数字滚动：任务数/进度百分比变化时做插值，不硬跳 */
@Composable
fun AnimatedCount(value: Int, modifier: Modifier = Modifier, content: @Composable (Int) -> Unit) {
    val animated by animateIntAsState(value, tween(400), label = "count")
    Box(modifier) { content(animated) }
}

/** 颜色动画包装：Compose 的 animateColorAsState 需要 Label，这里统一给默认值 */
@Composable
fun animateColorAsStateSafe(target: Color): androidx.compose.runtime.State<Color> =
    androidx.compose.animation.animateColorAsState(
        targetValue = target,
        animationSpec = tween(220),
        label = "colorAnim",
    )

/** 弹性悬浮：让卡片在出现时有一点点"落定"的手感 */
@Composable
fun cardScaleIn(visible: Boolean): Float {
    val v by animateFloatAsState(
        targetValue = if (visible) 1f else 0.94f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "cardScale",
    )
    return v
}
