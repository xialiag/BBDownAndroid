package com.bbdown.app.ui.style

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bbdown.app.ui.theme.AppTheme

/**
 * ============================================================================
 *  样式层 —— 每个函数对应原版 app.css 里的一个类，数值全部来自 CSS 原文
 * ============================================================================
 *
 * 与 WebView 版的对应：
 *   .view                    → Modifier.page()
 *   .view h1                 → Style.H1()
 *   .view h2                 → Style.H2()
 *   .view .lead              → Style.Lead()
 *   .search-type-tabs        → Style.SegmentedTabs()        （整体容器 + 内部按钮）
 *   .pill-group              → Style.PillGroup()
 *   input[type=text]         → Style.Input()
 *   .btn / .btn-primary / .btn-sec / .btn-danger → Style.Button()
 *   .video-card / .vc-item   → Style.VideoCard()
 *   .upper-card              → Style.UpperCard()
 *   .user-card               → Style.UserCard()
 */
object Style {

    // ==================== 页面骨架（.view） ====================

    /** `.view h1{ font-size:20px; font-weight:300; color:var(--fg-bright); margin:0 0 4px }` */
    @Composable
    fun H1(text: String, modifier: Modifier = Modifier) {
        val c = AppTheme.colors
        Text(text, color = c.fgBright, fontSize = 20.sp, fontWeight = FontWeight.Light,
            modifier = modifier.padding(bottom = 4.dp))
    }

    /** `.view h2{ font-size:14px; font-weight:600; margin:22px 0 10px; text-transform:uppercase;
     *            letter-spacing:.5px; padding-bottom:6px; border-bottom:1px solid var(--border) }` */
    @Composable
    fun H2(text: String, modifier: Modifier = Modifier) {
        val c = AppTheme.colors
        Column(modifier.padding(top = 22.dp, bottom = 10.dp)) {
            Text(text.uppercase(), color = c.fgTitle, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.5.sp, modifier = Modifier.padding(bottom = 6.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.border))
        }
    }

    /** `.view .lead{ color:var(--fg-dim); font-size:13px; margin:0 0 8px }` */
    @Composable
    fun Lead(text: String, modifier: Modifier = Modifier) {
        val c = AppTheme.colors
        Text(text, color = c.fgDim, fontSize = 13.sp, modifier = modifier.padding(bottom = 8.dp))
    }

    /** `.btn-row{ display:flex; gap:8px; margin-top:18px; flex-wrap:wrap }` */
    @Composable
    fun ButtonRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
        Row(modifier.padding(top = 18.dp).fillMaxWidth(),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
            content = content)
    }

    // ==================== 分段切换（.search-type-tabs / .pill-group） ====================

    /**
     * 分段控件（UP主/视频、深色/浅色/跟随系统 等）。
     *
     * CSS:
     *   .search-type-tabs{ display:flex; gap:4px; background:var(--pill-bg);
     *                      border:1px solid var(--pill-border); border-radius:10px; padding:3px }
     *   .search-type-tabs button{ flex:1; padding:7px 12px; font-size:12px; font-weight:500;
     *                             color:var(--fg-dim); border-radius:8px }
     *   .search-type-tabs button.active{ background:var(--accent); color:#fff; font-weight:600 }
     *
     * 注意：这是「一个整体容器 + 内部按钮」，不是一个按钮一个胶囊。
     */
    @Composable
    fun SegmentedTabs(
        options: List<String>,
        selected: Int,
        onSelect: (Int) -> Unit,
        modifier: Modifier = Modifier,
        itemPadV: Dp = 7.dp,
        fontSize: TextUnit = 12.sp,
    ) {
        val c = AppTheme.colors
        Row(
            modifier
                .fillMaxWidth()
                .padding(bottom = 14.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(c.pillBg)
                .border(1.dp, c.pillBorder, RoundedCornerShape(10.dp))
                .padding(3.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp),
        ) {
            options.forEachIndexed { i, label ->
                val active = i == selected
                val interaction = remember { MutableInteractionSource() }
                val pressed by interaction.collectIsPressedAsState()
                val scale by animateFloatAsState(if (pressed && !active) 0.96f else 1f,
                    spring(dampingRatio = 0.5f, stiffness = 900f), label = "segScale")
                val bg by animateColorAsState(if (active) c.accent else Color.Transparent, label = "segBg")   // CSS: background:var(--accent)
                val fg by animateColorAsState(if (active) Color.White else c.fgDim, label = "segFg")
                Box(
                    Modifier
                        .weight(1f)
                        .scale(scale)
                        .clip(RoundedCornerShape(8.dp))
                        .background(bg)
                        .clickable(interactionSource = interaction, indication = null) { onSelect(i) }
                        .padding(horizontal = 12.dp, vertical = itemPadV),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, color = fg, fontSize = fontSize,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1)
                }
            }
        }
    }

    // ==================== 输入框 ====================

    /**
     * CSS `input[type=text]{ width:100%; background:var(--bg-input);
     *   border:1px solid var(--border-light); border-radius:8px;
     *   color:var(--fg-bright); padding:9px 10px; font-size:13px }`
     */
    @Composable
    fun Input(
        value: String,
        onValueChange: (String) -> Unit,
        modifier: Modifier = Modifier,
        placeholder: String = "",
        height: Dp? = null,
        fontSize: TextUnit = 13.sp,
        singleLine: Boolean = true,
    ) {
        val c = AppTheme.colors
        val shape = RoundedCornerShape(8.dp)
        var m = modifier.fillMaxWidth()
        if (height != null) m = m.height(height)
        Box(
            m.clip(shape).background(c.bgInput).border(1.dp, c.borderLight, shape)
                .padding(horizontal = 10.dp, vertical = 9.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (value.isEmpty() && placeholder.isNotEmpty()) {
                Text(placeholder, color = c.fgDim.copy(alpha = 0.6f), fontSize = fontSize,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = singleLine,
                textStyle = TextStyle(color = c.fgBright, fontSize = fontSize),
                cursorBrush = SolidColor(c.accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    // ==================== 按钮 ====================

    enum class Kind { Primary, Secondary, Danger, Ghost }

    /** `.btn{ padding:9px 18px; font-size:13px; border-radius:8px; font-weight:500 }` */
    @Composable
    fun Button(
        text: String,
        onClick: () -> Unit,
        modifier: Modifier = Modifier,
        kind: Kind = Kind.Secondary,
        enabled: Boolean = true,
        height: Dp? = null,
        fontSize: TextUnit = 13.sp,
        fill: Boolean = false,
    ) {
        val c = AppTheme.colors
        val (bg, fg, bd) = when (kind) {
            Kind.Primary -> Triple(c.accentBtn, Color.White, c.accentBtn)
            Kind.Secondary -> Triple(c.bgInput, c.fg, c.borderLight)
            Kind.Danger -> Triple(c.dangerBg, c.dangerFg, Color.Transparent)
            Kind.Ghost -> Triple(Color.Transparent, c.fgDim, Color.Transparent)
        }
        val interaction = remember { MutableInteractionSource() }
        val pressed by interaction.collectIsPressedAsState()
        val scale by animateFloatAsState(if (pressed && enabled) 0.95f else 1f,
            spring(dampingRatio = 0.5f, stiffness = 900f), label = "btnScale")
        var m = modifier.scale(scale)
        if (fill) m = m.fillMaxWidth()
        if (height != null) m = m.height(height)
        Box(
            m.clip(RoundedCornerShape(8.dp)).background(bg).border(1.dp, bd, RoundedCornerShape(8.dp))
                .then(if (enabled) Modifier.clickable(interactionSource = interaction, indication = null, onClick = onClick) else Modifier)
                .padding(horizontal = 18.dp, vertical = 9.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(text, color = if (enabled) fg else fg.copy(alpha = 0.35f), fontSize = fontSize,
                fontWeight = FontWeight.Medium, maxLines = 1)
        }
    }

    /**
     * 独立胶囊项（非分组的场景，如文件类型标签）。
     * CSS `.pill-group button{ padding:6px 14px; font-size:12px; border-radius:7px }`
     */
    @Composable
    fun Pill(
        text: String,
        selected: Boolean,
        onClick: () -> Unit,
        modifier: Modifier = Modifier,
    ) {
        val c = AppTheme.colors
        val bg by animateColorAsState(if (selected) c.accentBtn else c.pillBg, label = "pillBg2")
        val fg by animateColorAsState(if (selected) Color.White else c.fgDim, label = "pillFg2")
        Box(
            modifier
                .clip(RoundedCornerShape(7.dp))
                .background(bg)
                .border(1.dp, if (selected) Color.Transparent else c.pillBorder, RoundedCornerShape(7.dp))
                .clickable { onClick() }
                .padding(horizontal = 14.dp, vertical = 6.dp),
        ) {
            Text(text, color = fg, fontSize = 12.sp, maxLines = 1,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
        }
    }

    // ==================== 卡片 ====================

    /** `.vc-item{ gap:12px; padding:10px; border-radius:10px; background:card-bg; border:1px solid card-border }` */
    @Composable
    fun Modifier.card10(): Modifier {
        val c = AppTheme.colors
        return this.clip(RoundedCornerShape(10.dp)).background(c.cardBg)
            .border(1.dp, c.cardBorder, RoundedCornerShape(10.dp)).padding(10.dp)
    }

    /** `.upper-card / .user-card{ padding:14~16px; border-radius:12px }` */
    @Composable
    fun Modifier.card12(pad: Dp = 14.dp): Modifier {
        val c = AppTheme.colors
        return this.clip(RoundedCornerShape(12.dp)).background(c.cardBg)
            .border(1.dp, c.cardBorder, RoundedCornerShape(12.dp)).padding(pad)
    }

    // ==================== 下拉选择器（原版 <select>） ====================

    /**
     * 下拉选择框。对应原版 `<select>`：与外层 Input 同样外观，右侧带 ▾，
     * 点击弹出选项列表。
     */
    @Composable
    fun SelectBox(
        options: List<String>,
        selectedIndex: Int,
        onSelect: (Int) -> Unit,
        modifier: Modifier = Modifier,
    ) {
        val c = AppTheme.colors
        var expanded by remember { mutableStateOf(false) }
        Column(modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(c.bgInput)
                    .border(1.dp, c.borderLight, RoundedCornerShape(8.dp))
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 10.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    options.getOrElse(selectedIndex) { options.firstOrNull() ?: "" },
                    color = c.fgBright, fontSize = 13.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                // 用 Canvas 画实心三角（▾ 在部分字体里渲染成细线）
                androidx.compose.foundation.Canvas(Modifier.size(10.dp)) {
                    val w = size.width; val h = size.height
                    val path = androidx.compose.ui.graphics.Path().apply {
                        if (expanded) {
                            moveTo(0f, h * 0.7f); lineTo(w, h * 0.7f); lineTo(w / 2, h * 0.15f)
                        } else {
                            moveTo(0f, h * 0.3f); lineTo(w, h * 0.3f); lineTo(w / 2, h * 0.85f)
                        }
                        close()
                    }
                    drawPath(path, c.fgDim)
                }
            }
            if (expanded) {
                Spacer(Modifier.height(4.dp))
                Column(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(c.dropdownBg)
                        .border(1.dp, c.borderLight, RoundedCornerShape(8.dp)),
                ) {
                    options.forEachIndexed { i, label ->
                        Row(
                            Modifier.fillMaxWidth()
                                .background(if (i == selectedIndex) c.bgActive else Color.Transparent)
                                .clickable { onSelect(i); expanded = false }
                                .padding(horizontal = 10.dp, vertical = 10.dp),
                        ) {
                            Text(label, color = if (i == selectedIndex) c.accent else c.fg, fontSize = 12.5.sp)
                        }
                    }
                }
            }
        }
    }

    // ==================== 开关（.switch） ====================

    /**
     * 开关滑块。CSS：
     *   .slider{ position:absolute; inset:0; background:var(--border-light); border-radius:20px }
     *   .slider::before{ width:14px; height:14px; left:3px; top:3px; background:#fff; border-radius:50% }
     *   .switch input:checked+.slider{ background:var(--accent) }
     *   .switch input:checked+.slider::before{ transform:translateX(18px) }
     */
    @Composable
    fun Switch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
        val c = AppTheme.colors
        val bg by animateColorAsState(if (checked) c.accent else c.borderLight, label = "swBg")
        val knobX by animateFloatAsState(if (checked) 18f else 0f,
            spring(dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
                   stiffness = androidx.compose.animation.core.Spring.StiffnessMedium), label = "knobX")
        Box(
            modifier
                .width(38.dp).height(20.dp)          // 3+14+3 = 20 高；滑块行程 18
                .clip(RoundedCornerShape(20.dp))
                .background(bg)
                .clickable { onCheckedChange(!checked) }
                .padding(3.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                Modifier
                    .padding(start = knobX.dp)
                    .size(14.dp)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(Color.White)
            )
        }
    }

    /**
     * 开关行。CSS `.switch-row{ display:flex; align-items:center; justify-content:space-between;
     *   padding:11px 0; border-bottom:1px solid var(--border-light) }`
     */
    @Composable
    fun SwitchRow(
        label: String,
        checked: Boolean,
        onCheckedChange: (Boolean) -> Unit,
        showDivider: Boolean = true,
    ) {
        val c = AppTheme.colors
        Row(
            Modifier.fillMaxWidth()
                .then(
                    if (showDivider) Modifier.drawBehind {
                        val t = 1.dp.toPx()
                        drawLine(c.borderLight, Offset(0f, size.height - t / 2), Offset(size.width, size.height - t / 2), t)
                    } else Modifier
                )
                .padding(vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, color = c.fg, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Switch(checked, onCheckedChange)
        }
    }

    @Composable
    fun Modifier.divider(color: Color? = null): Modifier {
        val c = AppTheme.colors
        return this.fillMaxWidth().height(1.dp).background(color ?: c.border)
    }
}

/** 页面根容器（顶层扩展，便于 `import com.bbdown.app.ui.style.page`）。CSS `.view{ padding:16px }` */
fun Modifier.page(): Modifier = this.fillMaxWidth().padding(16.dp)
