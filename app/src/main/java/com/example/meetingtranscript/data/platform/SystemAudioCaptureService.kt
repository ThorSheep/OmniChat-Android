package com.example.meetingtranscript.data.platform

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import com.example.meetingtranscript.domain.AudioCaptureService

class SystemAudioCaptureService(context: Context) : AudioCaptureService {
    private val audioManager = context.getSystemService(AudioManager::class.java)

    override fun currentInputName(): String {
        return runCatching {
            val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            val preferred = inputs.firstOrNull { it.isBluetoothInput() }
                ?: inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
                ?: inputs.firstOrNull()
            preferred?.productName?.toString()?.takeIf { it.isNotBlank() } ?: "系統預設麥克風"
        }.getOrDefault("系統預設麥克風")
    }

    private fun AudioDeviceInfo.isBluetoothInput(): Boolean = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET -> true
        else -> false
    }
}
