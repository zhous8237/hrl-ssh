package com.assh.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/** 主题模式（持久化）。SYSTEM = 跟随系统。 */
enum class ThemeMode { LIGHT, DARK, SYSTEM }

private val White = Color(0xFFFFFFFF)

private fun materialSchemeFrom(c: AsshColors, dark: Boolean) =
    if (dark) {
        darkColorScheme(
            primary = c.accent,
            onPrimary = White,
            secondary = c.success,
            onSecondary = c.background,
            tertiary = c.warning,
            background = c.background,
            onBackground = c.textPrimary,
            surface = c.surface,
            onSurface = c.textPrimary,
            surfaceVariant = c.surfaceVariant,
            onSurfaceVariant = c.textSecondary,
            error = c.error,
            outline = c.surfaceVariant,
        )
    } else {
        lightColorScheme(
            primary = c.accent,
            onPrimary = White,
            secondary = c.success,
            onSecondary = White,
            tertiary = c.warning,
            background = c.background,
            onBackground = c.textPrimary,
            surface = c.surface,
            onSurface = c.textPrimary,
            surfaceVariant = c.surfaceVariant,
            onSurfaceVariant = c.textSecondary,
            error = c.error,
            outline = c.surfaceVariant,
        )
    }

/**
 * 全局主题。根据 [themeMode] 选择深/浅两套配色，
 * 同时通过 [LocalAsshColors] 提供给所有以颜色名（Navy900 等）取色的旧代码，
 * 并适配系统状态栏图标的明暗（适配手机顶部状态栏）。
 */
@Composable
fun AsshTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit
) {
    val dark = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val asshColors = if (dark) DarkAsshColors else LightAsshColors
    val materialScheme = materialSchemeFrom(asshColors, dark)

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            // LocalView.current.context 常被 ContextThemeWrapper 包裹，不一定直接是 Activity，
            // 直接强转会抛 ClassCastException（进主页即闪退）。逐层解包安全取 Activity。
            val activity = view.context.findActivity()
            if (activity != null) {
                val insets = WindowCompat.getInsetsController(activity.window, view)
                // 状态栏/导航栏图标明暗：浅色主题用深色图标，深色主题用浅色图标
                insets.isAppearanceLightStatusBars = !dark
                insets.isAppearanceLightNavigationBars = !dark
            }
        }
    }

    CompositionLocalProvider(LocalAsshColors provides asshColors) {
        MaterialTheme(
            colorScheme = materialScheme,
            content = content
        )
    }
}

/** 从 Context 逐层解包出宿主 Activity；找不到返回 null（不崩溃）。 */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
