package com.voiceflow.app

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.UnknownHostException
import java.net.SocketTimeoutException

object GeminiClient {

    data class ModelDiscoveryResult(
        val success: Boolean,
        val chosenModel: String = "",
        val allModels: List<String> = emptyList(),
        val errorMessage: String = ""
    )

    data class DictationResult(
        val success: Boolean,
        val text: String = "",
        val errorMessage: String = ""
    )

    private const val SYSTEM_PROMPT = """
You are VoiceFlow, an AI voice-dictation engine inspired by Wispr Flow and Google Gboard Rambler.
Your job is to transcribe the user's spoken audio AND clean it up in a single pass.

FOLLOW THESE STRICT RULES:
1. OUTPUT ONLY THE FINAL CLEANED DICTATED TEXT. Never add explanations, greetings, quotes, labels, or markdown blocks around the answer. If the audio is completely silent or just background noise with no speech, return an empty string.
2. CLEANUP & FLUENCY:
   - Remove filler words ("um", "uh", "erm", "like", "you know", "matlab", "accha", "haan toh" when used purely as filler) and accidental word stutters/repeats.
   - Fix grammar, punctuation, and capitalization naturally so the message reads cleanly.
3. SELF-CORRECTIONS:
   - When the speaker corrects themselves mid-sentence (e.g., "send it at 5, no 6", "meet on Tuesday sorry Wednesday", "tell Rahul wait no Aman"), keep ONLY the final intended version ("Send it at 6.", "Meet on Wednesday.", "Tell Aman.").
4. LANGUAGES & SCRIPTS (CRITICAL):
   - The user speaks English, Hinglish (Hindi + English), and Marathi, and may switch languages mid-sentence.
   - Keep the exact language(s) spoken. NEVER translate into another language unless the speaker explicitly says "translate".
   - English stays in English.
   - Hinglish MUST stay in Roman script (English alphabet, e.g., "kal milte hain शाम को" -> "Kal milte hain shaam ko").
   - Marathi MUST ALWAYS be written in Roman script (English letters, e.g., "Tu kasa aahes?", "Mi udyach yeto", "Jevan zhala ka?"). NEVER output Devanagari script for Marathi or Hindi.
5. SPOKEN EMOJIS:
   - When the speaker names an emoji inside a sentence, replace the spoken emoji phrase with the actual emoji character (e.g., "how are you smiling emoji" -> "How are you 😊", "congrats party emoji fire emoji" -> "Congrats 🎉🔥", "thumbs up emoji" -> "👍", "laughing emoji" -> "😂", "red heart emoji" -> "❤️", "folded hands emoji" -> "🙏").
"""

    /**
     * Calls ListModels using the user's API key, filters for free Flash-capable models that
     * support generateContent and audio input, and automatically picks the best Flash model.
     */
    suspend fun fetchAndPickBestModel(apiKey: String): ModelDiscoveryResult = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        if (cleanKey.isEmpty()) {
            return@withContext ModelDiscoveryResult(
                success = false,
                errorMessage = "Please enter your Gemini API key first."
            )
        }

        val urlString = "https://generativelanguage.googleapis.com/v1beta/models?key=$cleanKey"
        var connection: HttpURLConnection? = null

        try {
            val url = URL(urlString)
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15000
                readTimeout = 15000
            }

            val status = connection.responseCode
            val responseBody = readStream(connection, status)

            if (status !in 200..299) {
                return@withContext ModelDiscoveryResult(
                    success = false,
                    errorMessage = formatApiError(status, responseBody)
                )
            }

            val root = JSONObject(responseBody)
            val modelsArray = root.optJSONArray("models") ?: JSONArray()
            val candidateModels = mutableListOf<String>()

            for (i in 0 until modelsArray.length()) {
                val item = modelsArray.optJSONObject(i) ?: continue
                val rawName = item.optString("name", "")
                val shortName = rawName.removePrefix("models/")
                if (shortName.isBlank()) continue

                val methods = item.optJSONArray("supportedGenerationMethods") ?: continue
                var supportsGenerate = false
                for (m in 0 until methods.length()) {
                    if (methods.optString(m) == "generateContent") {
                        supportsGenerate = true
                        break
                    }
                }
                if (!supportsGenerate) continue

                val lower = shortName.lowercase()
                // Only keep Gemini models capable of multimodal audio input + text output,
                // and exclude paid-only/pro/ultra/tts/image/embedding/vision-only variants.
                val isGemini = lower.startsWith("gemini")
                val excluded = lower.contains("pro") ||
                        lower.contains("ultra") ||
                        lower.contains("tts") ||
                        lower.contains("image") ||
                        lower.contains("imagen") ||
                        lower.contains("embedding") ||
                        lower.contains("vision") ||
                        lower.contains("robotics") ||
                        lower.contains("computer-use") ||
                        lower.contains("aqa") ||
                        lower.contains("thinking")

                if (isGemini && !excluded) {
                    candidateModels.add(shortName)
                }
            }

            if (candidateModels.isEmpty()) {
                return@withContext ModelDiscoveryResult(
                    success = false,
                    errorMessage = "No compatible free Flash models found for this API key. Make sure your key is from Google AI Studio (aistudio.google.com)."
                )
            }

            val sortedModels = candidateModels.sortedWith(compareBy { rankModel(it) })
            val bestModel = sortedModels.first()

            ModelDiscoveryResult(
                success = true,
                chosenModel = bestModel,
                allModels = sortedModels
            )
        } catch (e: UnknownHostException) {
            ModelDiscoveryResult(
                success = false,
                errorMessage = "No internet connection. Tip: Turn on Wi-Fi or Mobile Data and try again."
            )
        } catch (e: SocketTimeoutException) {
            ModelDiscoveryResult(
                success = false,
                errorMessage = "Connection timed out. Tip: Check your internet signal and tap Verify again."
            )
        } catch (e: Exception) {
            ModelDiscoveryResult(
                success = false,
                errorMessage = "Network error: ${e.localizedMessage ?: "Unknown error"}"
            )
        } finally {
            connection?.disconnect()
        }
    }

    private fun rankModel(modelName: String): Int {
        val m = modelName.lowercase()
        return when {
            m == "gemini-2.5-flash" -> 1
            m == "gemini-2.0-flash" -> 2
            m == "gemini-1.5-flash" -> 3
            m.startsWith("gemini-2.5-flash") && !m.contains("lite") && !m.contains("preview") -> 4
            m.startsWith("gemini-2.5-flash") && !m.contains("lite") -> 5
            m.startsWith("gemini-2.0-flash") && !m.contains("lite") && !m.contains("exp") -> 6
            m.startsWith("gemini-1.5-flash") && !m.contains("8b") -> 7
            m.contains("flash") && !m.contains("lite") -> 8
            m.contains("flash") -> 9
            else -> 20
        }
    }

    /**
     * Sends WAV audio bytes to Gemini in one call to transcribe and clean.
     */
    suspend fun transcribeAndClean(
        apiKey: String,
        modelName: String,
        wavBytes: ByteArray
    ): DictationResult = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        if (cleanKey.isEmpty()) {
            return@withContext DictationResult(
                success = false,
                errorMessage = "API key missing. Open VoiceFlow app and add your Gemini API key."
            )
        }

        val activeModel = modelName.trim().removePrefix("models/")
        if (activeModel.isEmpty()) {
            return@withContext DictationResult(
                success = false,
                errorMessage = "No model selected. Open VoiceFlow app and tap 'Verify & Save Key' to auto-pick a model."
            )
        }

        val base64Audio = Base64.encodeToString(wavBytes, Base64.NO_WRAP)

        val requestJson = JSONObject().apply {
            put(
                "system_instruction",
                JSONObject().put(
                    "parts",
                    JSONArray().put(JSONObject().put("text", SYSTEM_PROMPT.trim()))
                )
            )
            put(
                "contents",
                JSONArray().put(
                    JSONObject().put(
                        "parts",
                        JSONArray()
                            .put(
                                JSONObject().put(
                                    "inline_data",
                                    JSONObject()
                                        .put("mime_type", "audio/wav")
                                        .put("data", base64Audio)
                                )
                            )
                            .put(
                                JSONObject().put(
                                    "text",
                                    "Transcribe and clean this spoken dictation following all system rules. Output only the final text."
                                )
                            )
                    )
                )
            )
            put(
                "generationConfig",
                JSONObject()
                    .put("temperature", 0.15)
                    .put("maxOutputTokens", 1024)
            )
        }

        val urlString =
            "https://generativelanguage.googleapis.com/v1beta/models/$activeModel:generateContent?key=$cleanKey"
        var connection: HttpURLConnection? = null

        try {
            val url = URL(urlString)
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                doOutput = true
                connectTimeout = 20000
                readTimeout = 35000
            }

            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(requestJson.toString())
                writer.flush()
            }

            val status = connection.responseCode
            val responseBody = readStream(connection, status)

            if (status !in 200..299) {
                return@withContext DictationResult(
                    success = false,
                    errorMessage = formatApiError(status, responseBody)
                )
            }

            val root = JSONObject(responseBody)
            val candidates = root.optJSONArray("candidates")
            if (candidates == null || candidates.length() == 0) {
                return@withContext DictationResult(
                    success = true,
                    text = ""
                )
            }

            val firstCandidate = candidates.optJSONObject(0)
            val content = firstCandidate?.optJSONObject("content")
            val parts = content?.optJSONArray("parts")
            val sb = StringBuilder()
            if (parts != null) {
                for (i in 0 until parts.length()) {
                    val partText = parts.optJSONObject(i)?.optString("text", "") ?: ""
                    sb.append(partText)
                }
            }

            val cleanedText = sb.toString().trim()
            DictationResult(
                success = true,
                text = cleanedText
            )
        } catch (e: UnknownHostException) {
            DictationResult(
                success = false,
                errorMessage = "No internet connection. Tip: Check Wi-Fi/Data."
            )
        } catch (e: SocketTimeoutException) {
            DictationResult(
                success = false,
                errorMessage = "Request timed out. Tip: Try speaking a shorter clip or check signal."
            )
        } catch (e: Exception) {
            DictationResult(
                success = false,
                errorMessage = "Error: ${e.localizedMessage ?: "Failed to reach Gemini"}"
            )
        } finally {
            connection?.disconnect()
        }
    }

    private fun readStream(connection: HttpURLConnection, status: Int): String {
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        if (stream == null) return ""
        return BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
    }

    private fun formatApiError(status: Int, rawBody: String): String {
        var googleMessage = "Unknown API error"
        try {
            val errObj = JSONObject(rawBody).optJSONObject("error")
            if (errObj != null) {
                val msg = errObj.optString("message", "")
                val statusStr = errObj.optString("status", "")
                googleMessage = if (statusStr.isNotBlank()) "$msg ($statusStr)" else msg
            } else if (rawBody.isNotBlank()) {
                googleMessage = rawBody.take(160)
            }
        } catch (_: Exception) {
            if (rawBody.isNotBlank()) googleMessage = rawBody.take(160)
        }

        val fixTip = when (status) {
            400 -> "Tip: Check that your API key is copied completely with no spaces."
            401, 403 -> "Tip: Your API key was rejected. Create a fresh free key in Google AI Studio."
            404 -> "Tip: This model changed. Open VoiceFlow Settings and tap 'Refresh Models'."
            429 -> "Tip: Free rate limit reached. Wait 20–30 seconds or switch to another Flash model in Settings."
            500, 503 -> "Tip: Google's server is busy right now. Try again in a few seconds."
            else -> "Tip: Check your internet connection or refresh your model in VoiceFlow Settings."
        }

        return "HTTP $status: $googleMessage\n$fixTip"
    }
}
