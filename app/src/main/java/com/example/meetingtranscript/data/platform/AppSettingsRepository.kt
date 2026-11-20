package com.example.meetingtranscript.data.platform

import android.content.Context

enum class ThemeMode { SYSTEM, LIGHT, DARK }

class AppSettingsRepository(context: Context) {
    fun lastRoomId(): String? = preferences.getString("last_room_id", null)
    fun setLastRoomId(id: String) { preferences.edit().putString("last_room_id", id).apply() }
    private val preferences = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
    fun themeMode(): ThemeMode = runCatching { ThemeMode.valueOf(preferences.getString("theme_mode", ThemeMode.SYSTEM.name)!!) }.getOrDefault(ThemeMode.SYSTEM)
    fun setThemeMode(mode: ThemeMode) { preferences.edit().putString("theme_mode", mode.name).apply() }
}
