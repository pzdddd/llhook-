package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import bxxd.hook.NearbyFilterState
import bxxd.hook.TriState

// ============================================================================
//  附近列表「筛选面板」(Compose)
//
//  由 NearbySortHook 的「筛选」胶囊点击 → showHostComposePanel 弹出。
//  布局: 固定顶栏 + 可滚动分区内容 + 固定底栏 (重置/确定 始终可见)。
//
//  分区:
//   1. 角色 (role 精确值): 1 / 偏1 / 0.5 / 偏0 / 0 / 其它 / side
//   2. 在线状态: 在线(1) / 离线(0)
//   3. 资质筛选 (三态 不限/仅看/不看): VIP / 年费VIP / 真人认证 / 相册 / 新人 / 影子用户 / 主播
// ============================================================================

/** role 精确值 → 标签 (按用户给定含义)。 */
private val ROLE_EXACT = listOf(
    1.0 to "1", 0.75 to "偏1", 0.5 to "0.5", 0.25 to "偏0",
    0.0 to "0", -1.0 to "其它", -2.0 to "side"
)
private val ONLINE_OPTS = listOf(1 to "在线", 0 to "离线")
private val QUAL_LABELS = listOf("VIP", "年费VIP", "真人认证", "相册", "新人", "影子用户", "主播")

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun NearbyFilterPanel(
    initial: NearbyFilterState,
    onApply: (NearbyFilterState) -> Unit,
    onReset: () -> Unit,
    onClose: () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val accent = if (isDark) Color(0xFF60A5FA) else Color(0xFF3B82F6)
    val accent2 = if (isDark) Color(0xFFA78BFA) else Color(0xFF6366F1)
    val accentBrush = Brush.horizontalGradient(listOf(accent, accent2))
    val text = if (isDark) Color(0xFFF1F5F9) else Color(0xFF0F172A)
    val subText = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
    val cardBg = if (isDark) Color(0xFF1E293B).copy(alpha = 0.9f) else Color.White.copy(alpha = 0.85f)
    val cardStroke = if (isDark) Color(0x1AFFFFFF) else Color(0x14878F99)
    val chipBg = if (isDark) Color(0xFF334155) else Color(0xFFF1F5F9)
    val divider = if (isDark) Color(0x10FFFFFF) else Color(0x0A000000)

    // ---- 本地可编辑状态 (从 initial 快照初始化) ----
    var roles by remember { mutableStateOf(initial.roleExact) }
    var online by remember { mutableStateOf(initial.onlineStates) }
    var vip by remember { mutableStateOf(initial.vipMode) }
    var annual by remember { mutableStateOf(initial.annualVipMode) }
    var real by remember { mutableStateOf(initial.realMode) }
    var album by remember { mutableStateOf(initial.albumMode) }
    var newU by remember { mutableStateOf(initial.newMode) }
    var shadow by remember { mutableStateOf(initial.shadowMode) }
    var anchor by remember { mutableStateOf(initial.anchorMode) }

    fun resetLocal() {
        roles = emptySet(); online = emptySet()
        vip = TriState.NONE; annual = TriState.NONE; real = TriState.NONE
        album = TriState.NONE; newU = TriState.NONE; shadow = TriState.NONE; anchor = TriState.NONE
    }
    fun buildState() = NearbyFilterState(
        roleExact = roles, onlineStates = online,
        vipMode = vip, annualVipMode = annual, realMode = real,
        albumMode = album, newMode = newU, shadowMode = shadow, anchorMode = anchor
    )

    val bgBrush = if (isDark) Brush.verticalGradient(listOf(Color(0xFF0F172A), Color(0xFF1E293B)))
        else Brush.verticalGradient(listOf(Color(0xFFEFF6FF), Color(0xFFE0E7FF)))

    Box(Modifier.fillMaxSize().background(bgBrush)) {
        Column(Modifier.fillMaxSize()) {
            // ============ 顶栏 (固定) ============
            Row(
                Modifier.fillMaxWidth().padding(start = 18.dp, end = 12.dp, top = 14.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(accentBrush),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Filled.FilterAlt, null, tint = Color.White, modifier = Modifier.size(22.dp)) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("附近筛选", color = text, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    Text("勾选条件后点确定, 仅显示符合者", color = subText, fontSize = 11.sp)
                }
                ActionIcon(onClick = onClose, bg = cardBg, stroke = cardStroke) {
                    Icon(Icons.Filled.Close, null, tint = text, modifier = Modifier.size(20.dp))
                }
            }

            // ============ 中间内容 (仅此区滚动) ============
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)
            ) {
                SectionCard("角色", accent, cardBg, cardStroke, text, subText) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        ROLE_EXACT.forEach { (v, label) ->
                            val sel = roles.any { kotlin.math.abs(it - v) < 1e-9 }
                            MiniChip(
                                text = label, selected = sel, bg = chipBg, selBg = accentBrush,
                                tColor = text, tSelColor = Color.White
                            ) {
                                roles = if (sel) roles.filterNot { kotlin.math.abs(it - v) < 1e-9 }.toSet() else roles + v
                            }
                        }
                    }
                }

                SectionCard("在线状态", accent, cardBg, cardStroke, text, subText) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        ONLINE_OPTS.forEach { (id, label) ->
                            val sel = id in online
                            MiniChip(label, sel, chipBg, accentBrush, text, Color.White) {
                                online = if (sel) online - id else online + id
                            }
                        }
                    }
                }

                SectionCard("资质筛选", accent, cardBg, cardStroke, text, subText, hint = "切换 不限 → 仅看 → 不看") {
                    val labels = QUAL_LABELS
                    val modes = listOf(vip, annual, real, album, newU, shadow, anchor)
                    labels.forEachIndexed { i, label ->
                        TriStateRow(
                            label = label, state = modes[i],
                            text = text, subText = subText, bg = chipBg, accent = accent,
                            divider = divider, isLast = i == labels.lastIndex
                        ) { ns ->
                            when (i) {
                                0 -> vip = ns; 1 -> annual = ns; 2 -> real = ns
                                3 -> album = ns; 4 -> newU = ns; 5 -> shadow = ns; 6 -> anchor = ns
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }

            // ============ 底栏 (固定) ============
            Row(
                Modifier.fillMaxWidth().background(cardBg.copy(alpha = 0.55f))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                PillButton(
                    text = "重置", onClick = { resetLocal(); onReset() },
                    modifier = Modifier.weight(1f), gradient = false,
                    bg = chipBg, selBg = accentBrush, stroke = cardStroke, tColor = text
                )
                PillButton(
                    text = "确定", onClick = { onApply(buildState()); onClose() },
                    modifier = Modifier.weight(1.5f), gradient = true,
                    bg = accent, selBg = accentBrush, stroke = cardStroke, tColor = Color.White
                )
            }
        }
    }
}

// ===========================================================================
//  子组件
// ===========================================================================

/** 区块卡片: 左侧 accent 竖条 + 标题 + 内容。 */
@Composable
private fun SectionCard(
    title: String, accent: Color, bg: Color, stroke: Color, text: Color, subText: Color,
    hint: String? = null, content: @Composable () -> Unit
) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(16.dp))
            .background(bg).border(1.dp, stroke, RoundedCornerShape(16.dp)).padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(4.dp).height(16.dp).clip(RoundedCornerShape(2.dp)).background(accent))
            Spacer(Modifier.width(8.dp))
            Text(title, color = text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            if (hint != null) {
                Spacer(Modifier.width(8.dp))
                Text(hint, color = subText, fontSize = 10.sp)
            }
        }
        Spacer(Modifier.height(12.dp))
        content()
    }
}

/** 小型圆角胶囊 chip (FlowRow 通用)。 */
@Composable
private fun MiniChip(
    text: String, selected: Boolean,
    bg: Color, selBg: Brush, tColor: Color, tSelColor: Color,
    onClick: () -> Unit
) {
    Box(
        Modifier.clip(RoundedCornerShape(50)).then(
            if (selected) Modifier.background(selBg) else Modifier.background(bg)
        ).noRippleClickable(onClick).padding(horizontal = 14.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = if (selected) tSelColor else tColor, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

/** 资质三态行: 左标签 + 右分段 (不限/仅看/不看) + 细分隔线。 */
@Composable
private fun TriStateRow(
    label: String, state: TriState,
    text: Color, subText: Color, bg: Color, accent: Color,
    divider: Color, isLast: Boolean,
    onChange: (TriState) -> Unit
) {
    Column {
        Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = text, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            SingleChoiceSegmentedButtonRow(Modifier.width(168.dp)) {
                TriState.values().forEachIndexed { i, ts ->
                    val lab = when (ts) { TriState.NONE -> "不限"; TriState.ONLY -> "仅看"; TriState.EXCLUDE -> "不看" }
                    SegmentedButton(
                        selected = state == ts,
                        onClick = { onChange(if (state == ts) TriState.NONE else ts) },
                        shape = SegmentedButtonDefaults.itemShape(i, 3),
                        colors = SegmentedButtonDefaults.colors(
                            activeContainerColor = accent, activeContentColor = Color.White,
                            inactiveContainerColor = bg, inactiveContentColor = subText
                        ),
                        label = { Text(lab, fontSize = 12.sp, maxLines = 1, textAlign = TextAlign.Center) }
                    )
                }
            }
        }
        if (!isLast) Box(Modifier.fillMaxWidth().height(0.5.dp).background(divider))
    }
}

/** 顶栏小圆形图标按钮。 */
@Composable
private fun ActionIcon(onClick: () -> Unit, bg: Color, stroke: Color, content: @Composable () -> Unit) {
    Box(
        Modifier.size(36.dp).clip(RoundedCornerShape(50)).background(bg)
            .border(1.dp, stroke, RoundedCornerShape(50)).noRippleClickable(onClick),
        contentAlignment = Alignment.Center
    ) { content() }
}

/** 底栏胶囊按钮 (渐变 / 纯色两态), 文字居中。 */
@Composable
private fun RowScope.PillButton(
    text: String, onClick: () -> Unit,
    modifier: Modifier, gradient: Boolean,
    bg: Color, selBg: Brush, stroke: Color, tColor: Color
) {
    Box(
        modifier.height(48.dp).clip(RoundedCornerShape(14.dp)).then(
            if (gradient) Modifier.background(selBg) else Modifier.background(bg)
        )
            .border(if (gradient) 0.dp else 1.dp, stroke, RoundedCornerShape(14.dp))
            .noRippleClickable(onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = tColor, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

// ---- 小工具 ----

/** 无水波纹点击 (自定义 chip/按钮统一风格)。 */
@Composable
private fun Modifier.noRippleClickable(onClick: () -> Unit): Modifier =
    this.clickable(
        indication = null,
        interactionSource = remember { MutableInteractionSource() },
        onClick = onClick
    )
