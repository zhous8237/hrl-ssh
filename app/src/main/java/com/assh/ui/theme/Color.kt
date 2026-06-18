package com.assh.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 全 app 配色（视觉设计方案 §3.1）。
 *
 * 主题切换（白天/夜晚/跟随系统）：原先散布各处的颜色名（Navy900 等）保持不变，
 * 改为从 [LocalAsshColors] 动态解析的 `@Composable get()` 属性，调用点无需改动。
 * 深色保持原 Cyber-Minimalism 配色；浅色为对应的亮色映射。
 */
@Immutable
data class AsshColors(
    val background: Color,   // 页面底色（原 Navy900）
    val surface: Color,      // 卡片/对话框（原 Navy800）
    val surfaceVariant: Color, // 输入框边框/按键底（原 Navy700）
    val accent: Color,       // 主强调色（原 BlueAccent）
    val success: Color,      // 成功/已连接（原 GreenSuccess）
    val warning: Color,      // 警告（原 AmberWarning）
    val error: Color,        // 错误/删除（原 RedError）
    val textPrimary: Color,  // 主要文字（原 Slate200）
    val textSecondary: Color // 次要文字（原 Slate400）
)

/** 深色（默认，原配色不变） */
val DarkAsshColors = AsshColors(
    background = Color(0xFF0F172A),
    surface = Color(0xFF1E293B),
    surfaceVariant = Color(0xFF334155),
    accent = Color(0xFF3B82F6),
    success = Color(0xFF10B981),
    warning = Color(0xFFF59E0B),
    error = Color(0xFFEF4444),
    textPrimary = Color(0xFFE2E8F0),
    textSecondary = Color(0xFF94A3B8),
)

/** 浅色（白天） */
val LightAsshColors = AsshColors(
    background = Color(0xFFF1F5F9),  // 浅灰蓝底
    surface = Color(0xFFFFFFFF),     // 白卡片
    surfaceVariant = Color(0xFFE2E8F0), // 浅边框
    accent = Color(0xFF2563EB),      // 稍深的蓝，浅底上对比更足
    success = Color(0xFF059669),
    warning = Color(0xFFD97706),
    error = Color(0xFFDC2626),
    textPrimary = Color(0xFF0F172A), // 深色主文字
    textSecondary = Color(0xFF64748B),
)

/** 当前生效配色；由 AsshTheme 注入。默认深色。 */
val LocalAsshColors = staticCompositionLocalOf { DarkAsshColors }

// ===== 兼容旧调用点的颜色名（@Composable get，运行时按当前主题解析）=====
// 名称沿用旧语义，浅色主题下自动映射到对应亮色。

val Navy900: Color @Composable get() = LocalAsshColors.current.background
val Navy800: Color @Composable get() = LocalAsshColors.current.surface
val Navy700: Color @Composable get() = LocalAsshColors.current.surfaceVariant
val BlueAccent: Color @Composable get() = LocalAsshColors.current.accent
val GreenSuccess: Color @Composable get() = LocalAsshColors.current.success
val AmberWarning: Color @Composable get() = LocalAsshColors.current.warning
val RedError: Color @Composable get() = LocalAsshColors.current.error
val Slate200: Color @Composable get() = LocalAsshColors.current.textPrimary
val Slate400: Color @Composable get() = LocalAsshColors.current.textSecondary

// 头像低饱和度渐变色池（按主机名 hash 取色）——两套主题通用，保持静态
val AvatarGradients = listOf(
    Pair(Color(0xFF3B5BDB), Color(0xFF22B8CF)),
    Pair(Color(0xFF7048E8), Color(0xFFD6336C)),
    Pair(Color(0xFF0CA678), Color(0xFF74B816)),
    Pair(Color(0xFFE8590C), Color(0xFFF59F00)),
    Pair(Color(0xFF1098AD), Color(0xFF4263EB)),
    Pair(Color(0xFFAE3EC9), Color(0xFF7048E8)),
)
