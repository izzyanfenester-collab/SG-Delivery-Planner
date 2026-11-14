package com.izzyan.sgdeliveryplanner

import android.content.Context

/** GPS-only preference; leaves unrelated delivery and appearance settings untouched. */
internal class GpsNavigationModePreferences(context: Context) {
    private val store = context.applicationContext
        .getSharedPreferences("sg_delivery_gps_navigation", Context.MODE_PRIVATE)
    fun is3dEnabled(): Boolean = store.getBoolean("navigation_mode_3d", true)
    fun set3dEnabled(enabled: Boolean) {
        store.edit().putBoolean("navigation_mode_3d", enabled).apply()
    }
    // Explicit user preferences survive app updates; fresh installs default to voice ON.
    fun isSoundEnabled(): Boolean = store.getBoolean("navigation_sound_enabled", true)
    fun setSoundEnabled(enabled: Boolean) {
        store.edit().putBoolean("navigation_sound_enabled", enabled).apply()
    }
    fun voice(): NavigationVoice {
        if(store.getString("navigation_voice",null)!=NavigationVoice.PANDA.name)
            store.edit().putString("navigation_voice",NavigationVoice.PANDA.name).apply()
        return NavigationVoice.PANDA
    }
    fun voiceVolume(): Float = store.getFloat("navigation_voice_volume",1f).coerceIn(0f,1f)
    fun setVoiceVolume(value:Float) { store.edit().putFloat("navigation_voice_volume",value.coerceIn(0f,1f)).apply() }
}
