package com.voiceflow.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/**
 * Provides offline / basic fallback speech recognition using Android's built-in SpeechRecognizer
 * when the phone has no internet connection.
 */
class OfflineSpeechHelper(
    private val context: Context,
    private val onResult: (String) -> Unit,
    private val onError: (String) -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var isListening = false

    fun startListening() {
        mainHandler.post {
            try {
                stopAndDestroyInternal()
                if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                    onError("Offline speech recognition is not installed on this device.")
                    return@post
                }

                val sr = SpeechRecognizer.createSpeechRecognizer(context)
                recognizer = sr
                sr.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {
                        isListening = false
                    }

                    override fun onError(error: Int) {
                        isListening = false
                        val msg = when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH,
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech heard (offline mode)."
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission needed."
                            else -> "Offline speech unavailable (code $error). Connect to internet for Gemini AI."
                        }
                        onError(msg)
                    }

                    override fun onResults(results: Bundle?) {
                        isListening = false
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val raw = matches?.firstOrNull()?.trim() ?: ""
                        if (raw.isBlank()) {
                            onResult("")
                        } else {
                            onResult(formatBasicOfflineText(raw))
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                    )
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                }

                isListening = true
                sr.startListening(intent)
            } catch (e: Exception) {
                isListening = false
                onError("Offline recognizer error: ${e.localizedMessage ?: "Unknown"}")
            }
        }
    }

    fun stopListening() {
        mainHandler.post {
            try {
                if (isListening) {
                    recognizer?.stopListening()
                    isListening = false
                }
            } catch (_: Exception) {
            }
        }
    }

    fun destroy() {
        mainHandler.post {
            stopAndDestroyInternal()
        }
    }

    private fun stopAndDestroyInternal() {
        try {
            recognizer?.cancel()
            recognizer?.destroy()
        } catch (_: Exception) {
        }
        recognizer = null
        isListening = false
    }

    companion object {
        fun formatBasicOfflineText(rawInput: String): String {
            var text = rawInput.trim()
            if (text.isEmpty()) return ""

            // Simple spoken emoji & punctuation replacements for offline mode
            val replacements = listOf(
                Regex("\\bsmiling emoji\\b", RegexOption.IGNORE_CASE) to "😊",
                Regex("\\bsmile emoji\\b", RegexOption.IGNORE_CASE) to "😊",
                Regex("\\blaughing emoji\\b", RegexOption.IGNORE_CASE) to "😂",
                Regex("\\bthumbs up emoji\\b", RegexOption.IGNORE_CASE) to "👍",
                Regex("\\bheart emoji\\b", RegexOption.IGNORE_CASE) to "❤️",
                Regex("\\bred heart emoji\\b", RegexOption.IGNORE_CASE) to "❤️",
                Regex("\\bfire emoji\\b", RegexOption.IGNORE_CASE) to "🔥",
                Regex("\\bparty emoji\\b", RegexOption.IGNORE_CASE) to "🎉",
                Regex("\\bfolded hands emoji\\b", RegexOption.IGNORE_CASE) to "🙏",
                Regex("\\bcrying emoji\\b", RegexOption.IGNORE_CASE) to "😢",
                Regex("\\bquestion mark\\b", RegexOption.IGNORE_CASE) to "?",
                Regex("\\bexclamation mark\\b", RegexOption.IGNORE_CASE) to "!",
                Regex("\\bfull stop\\b", RegexOption.IGNORE_CASE) to ".",
                Regex("\\bperiod\\b", RegexOption.IGNORE_CASE) to ".",
                Regex("\\bcomma\\b", RegexOption.IGNORE_CASE) to ","
            )

            for ((pattern, replacement) in replacements) {
                text = pattern.replace(text, replacement)
            }

            text = text.replace(Regex("\\s+([.,?!])"), "$1")
            text = text.replaceFirstChar {
                if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
            }

            val lastChar = text.last()
            if (lastChar.isLetterOrDigit()) {
                text += "."
            }
            return text
        }
    }
}
