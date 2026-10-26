package com.voiceflow.app

import android.content.Context
import android.content.SharedPreferences

class PrefsManager(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("voiceflow_prefs", Context.MODE_PRIVATE)

    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, "")?.trim() ?: ""
        set(value) = prefs.edit().putString(KEY_API_KEY, value.trim()).apply()

    var selectedModel: String
        get() = prefs.getString(KEY_SELECTED_MODEL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SELECTED_MODEL, value).apply()

    var availableModels: List<String>
        get() {
            val raw = prefs.getString(KEY_AVAILABLE_MODELS, "") ?: ""
            return if (raw.isBlank()) emptyList() else raw.split(",").filter { it.isNotBlank() }
        }
        set(value) {
            prefs.edit().putString(KEY_AVAILABLE_MODELS, value.joinToString(",")).apply()
        }

    // 0 = Small (48dp), 1 = Medium (56dp), 2 = Large (66dp)
    var bubbleSizeIndex: Int
        get() = prefs.getInt(KEY_BUBBLE_SIZE, 1)
        set(value) = prefs.edit().putInt(KEY_BUBBLE_SIZE, value.coerceIn(0, 2)).apply()

    var hapticsEnabled: Boolean
        get() = prefs.getBoolean(KEY_HAPTICS, true)
        set(value) = prefs.edit().putBoolean(KEY_HAPTICS, value).apply()

    var bubbleX: Int
        get() = prefs.getInt(KEY_BUBBLE_X, -1)
        set(value) = prefs.edit().putInt(KEY_BUBBLE_X, value).apply()

    var bubbleY: Int
        get() = prefs.getInt(KEY_BUBBLE_Y, -1)
        set(value) = prefs.edit().putInt(KEY_BUBBLE_Y, value).apply()

    fun resetBubblePosition() {
        prefs.edit().remove(KEY_BUBBLE_X).remove(KEY_BUBBLE_Y).apply()
    }

    fun getBubbleSizeDp(): Int {
        return when (bubbleSizeIndex) {
            0 -> 48
            2 -> 66
            else -> 56
        }
    }

    companion object {
        private const val KEY_API_KEY = "gemini_api_key"
        private const val KEY_SELECTED_MODEL = "selected_model"
        private const val KEY_AVAILABLE_MODELS = "available_models"
        private const val KEY_BUBBLE_SIZE = "bubble_size_index"
        private const val KEY_HAPTICS = "haptics_enabled"
        private const val KEY_BUBBLE_X = "bubble_x"
        private const val KEY_BUBBLE_Y = "bubble_y"
    }
}
