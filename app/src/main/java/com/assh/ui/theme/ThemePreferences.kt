package com.assh.ui.theme

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.themeDataStore by preferencesDataStore(name = "settings")

/** 主题模式持久化（DataStore）。存枚举名，默认跟随系统。 */
class ThemePreferences(private val context: Context) {

    private val keyMode = stringPreferencesKey("theme_mode")

    /** 当前主题模式流；默认跟随系统 */
    val mode: Flow<ThemeMode> = context.themeDataStore.data.map { prefs ->
        when (prefs[keyMode]) {
            ThemeMode.LIGHT.name -> ThemeMode.LIGHT
            ThemeMode.DARK.name -> ThemeMode.DARK
            else -> ThemeMode.SYSTEM
        }
    }

    suspend fun setMode(mode: ThemeMode) {
        context.themeDataStore.edit { it[keyMode] = mode.name }
    }
}
