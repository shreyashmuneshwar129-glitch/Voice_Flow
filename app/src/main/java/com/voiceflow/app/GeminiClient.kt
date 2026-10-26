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
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

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
        val usedModel: String = "",
        val errorMessage: String = ""
    )

    private const val SYSTEM_PROMPT = """
You are VoiceFlow, a fast AI voice-dictation engine inspired by Wispr Flow and Google Gboard Rambler.
Transcribe the user's spoken audio AND clean it up in a single pass.

STRICT RULES:
1. OUTPUT ONLY THE FINAL CLEANED DICTATED TEXT. Never add explanations, greetings, quotes, or markdown. If the audio has no speech, return an empty string.
2. CLEANUP: Remove filler words ("um", "uh", "like", "you know", "matlab", "accha") and stutters/repeats. Fix grammar, punctuation, and capitalization naturally.
3. SELF-CORRECTIONS: When the speaker corrects themselves mid-sentence ("send it at 5, no 6", "meet Tuesday sorry Wednesday"), output ONLY the final intended version ("Send it at 6.", "Meet Wednesday.").
4. LANGUAGES & SCRIPTS:
   - The user speaks English, Hinglish, and Marathi, and may switch mid-sentence.
   - NEVER translate unless the user explicitly says "translate".
   - Hinglish MUST stay in Roman script (English letters).
   - Marathi MUST ALWAYS be written in Roman script (English letters, e.g., "Tu kasa aahes?"). NEVER use Devanagari script.
5. SPOKEN EMOJIS: Replace spoken emoji names with the emoji character ("how are you smiling emoji" -> "How are you 😊", "thumbs up emoji" -> "👍", "laughing emoji" -> "😂", "red heart emoji" -> "❤️", "fire emoji" -> "🔥", "party emoji" -> "🎉", "folded hands emoji" -> "🙏").
"""

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
                connectTimeout = 12000
                readTimeout = 12000
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
                        lower.contains("thinking") ||
                        lower.contains("live")

                if (isGemini && !excluded) {
                    candidateModels.add(shortName)
                }
            }

            if (candidateModels.isEmpty()) {
                return@withContext ModelDiscoveryResult(
                    success = false,
                    errorMessage = "No compatible free Flash models found for this API key."
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
                errorMessage = "No internet connection. Turn on Wi-Fi or Mobile Data."
            )
        } catch (e: SocketTimeoutException) {
            ModelDiscoveryResult(
                success = false,
                errorMessage = "Connection timed out. Check your internet signal and try again."
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

    /**
     * Ranks Gemini 3.x Flash models (like gemini-3.8-flash, gemini-3.6-flash, gemini-3-flash-preview)
     * at the very top, and pushes legacy 2.5 / 2.0 / 1.5 models to the bottom because Google
     * blocks 2.5/2.0 for new API keys.
     */
    fun rankModel(modelName: String): Int {
        val m = modelName.lowercase().removePrefix("models/")
        if (m.startsWith("gemini-1.") || m.startsWith("gemini-2.0") || m.startsWith("gemini-2.5")) {
            return 500
        }
        return when {
            m == "gemini-3.8-flash" -> 1
            m == "gemini-3.6-flash" -> 2
            m == "gemini-3.5-flash" -> 3
            m == "gemini-3-flash-preview" -> 4
            m == "gemini-3-flash" -> 5
            m == "gemini-flash-latest" -> 6
            m.startsWith("gemini-3.") && m.contains("flash") && !m.contains("lite") && !m.contains("preview") -> 7
            m.startsWith("gemini-3") && m.contains("flash") && !m.contains("lite") -> 8
            m == "gemini-3.5-flash-lite" -> 9
            m == "gemini-3.1-flash-lite" -> 10
            m.startsWith("gemini-3") && m.contains("flash") -> 11
            m.contains("flash") && !m.contains("lite") -> 20
            m.contains("flash") -> 30
            else -> 100
        }
    }

    suspend fun transcribeAndClean(
        apiKey: String,
        modelName: String,
        availableModels: List<String>,
        wavBytes: ByteArray
    ): DictationResult = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        if (cleanKey.isEmpty()) {
            return@withContext DictationResult(
                success = false,
                errorMessage = "API key missing. Open VoiceFlow app and add your Gemini API key."
            )
        }

        val base64Audio = Base64.encodeToString(wavBytes, Base64.NO_WRAP)

        // Build ordered queue of models to try so if one returns 404/429, we auto-heal immediately
        val modelsToTry = LinkedHashSet<String>()
        val initial = modelName.trim().removePrefix("models/")
        if (initial.isNotBlank() && !initial.startsWith("gemini-2.5") && !initial.startsWith("gemini-2.0")) {
            modelsToTry.add(initial)
        }
        availableModels.sortedBy { rankModel(it) }.forEach {
            val clean = it.trim().removePrefix("models/")
            if (clean.isNotBlank()) modelsToTry.add(clean)
        }
        // Standard current fallback Flash models for new users
        modelsToTry.add("gemini-3.8-flash")
        modelsToTry.add("gemini-3.6-flash")
        modelsToTry.add("gemini-3-flash-preview")
        modelsToTry.add("gemini-3.1-flash-lite")
        if (initial.isNotBlank()) modelsToTry.add(initial)

        val queue = ArrayDeque(modelsToTry)
        val tried = mutableSetOf<String>()
        var lastError = "Failed to reach Gemini API."

        while (queue.isNotEmpty() && tried.size < 4) {
            val candidate = queue.removeFirst()
            if (!tried.add(candidate)) continue

            val attempt = callGenerateContentSingle(
                apiKey = cleanKey,
                model = candidate,
                base64Audio = base64Audio,
                useLowThinking = true
            )

            if (attempt.httpStatus in 200..299) {
                return@withContext DictationResult(
                    success = true,
                    text = attempt.text,
                    usedModel = candidate
                )
            }

            // If a model rejected thinkingConfig with HTTP 400, retry once without thinkingConfig
            if (attempt.httpStatus == 400 && attempt.rawError.contains("thinking", ignoreCase = true)) {
                val retryNoThinking = callGenerateContentSingle(
                    apiKey = cleanKey,
                    model = candidate,
                    base64Audio = base64Audio,
                    useLowThinking = false
                )
                if (retryNoThinking.httpStatus in 200..299) {
                    return@withContext DictationResult(
                        success = true,
                        text = retryNoThinking.text,
                        usedModel = candidate
                    )
                }
            }

            lastError = formatApiError(attempt.httpStatus, attempt.rawError)

            // If 404 says "Please update your code to use models/gemini-3.8-flash", extract that exact model and try it next!
            if (attempt.httpStatus == 404 || attempt.httpStatus == 429 || attempt.httpStatus == 503) {
                val suggested = extractSuggestedModel(attempt.rawError, candidate)
                if (suggested != null && suggested !in tried) {
                    queue.addFirst(suggested)
                }
                continue
            } else {
                // For auth (401/403) or network errors, stop immediately
                break
            }
        }

        DictationResult(
            success = false,
            errorMessage = lastError
        )
    }

    private data class SingleCallOutput(
        val httpStatus: Int,
        val text: String = "",
        val rawError: String = ""
    )

    private fun callGenerateContentSingle(
        apiKey: String,
        model: String,
        base64Audio: String,
        useLowThinking: Boolean
    ): SingleCallOutput {
        val genConfig = JSONObject()
            .put("temperature", 0.1)
            .put("maxOutputTokens", 4096)

        if (useLowThinking) {
            val lower = model.lowercase()
            if (lower.startsWith("gemini-3") || lower.startsWith("gemini-4")) {
                // Critical speed fix for Gemini 3+: set thinkingLevel to "low" so it responds in ~1s
                genConfig.put("thinkingConfig", JSONObject().put("thinkingLevel", "low"))
            } else if (lower.startsWith("gemini-2.5") && !lower.contains("lite")) {
                genConfig.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
            }
        }

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
                                    "Transcribe and clean this audio. Output ONLY the final cleaned text."
                                )
                            )
                    )
                )
            )
            put("generationConfig", genConfig)
        }

        val urlString =
            "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
        var connection: HttpURLConnection? = null

        return try {
            val url = URL(urlString)
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                doOutput = true
                connectTimeout = 15000
                readTimeout = 25000
            }

            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(requestJson.toString())
                writer.flush()
            }

            val status = connection.responseCode
            val responseBody = readStream(connection, status)

            if (status !in 200..299) {
                return SingleCallOutput(httpStatus = status, rawError = responseBody)
            }

            val root = JSONObject(responseBody)
            val candidates = root.optJSONArray("candidates")
            if (candidates == null || candidates.length() == 0) {
                return SingleCallOutput(httpStatus = 200, text = "")
            }

            val firstCandidate = candidates.optJSONObject(0)
            val content = firstCandidate?.optJSONObject("content")
            val parts = content?.optJSONArray("parts")
            val sb = StringBuilder()
            if (parts != null) {
                for (i in 0 until parts.length()) {
                    val partObj = parts.optJSONObject(i) ?: continue
                    // Skip internal thinking parts so only real dictated text is inserted
                    if (partObj.optBoolean("thought", false)) continue
                    sb.append(partObj.optString("text", ""))
                }
            }

            SingleCallOutput(
                httpStatus = 200,
                text = sb.toString().trim()
            )
        } catch (e: UnknownHostException) {
            SingleCallOutput(
                httpStatus = -1,
                rawError = "No internet connection. Tip: Check Wi-Fi or Mobile Data."
            )
        } catch (e: SocketTimeoutException) {
            SingleCallOutput(
                httpStatus = -2,
                rawError = "Request timed out. Tip: Check signal or try a shorter voice clip."
            )
        } catch (e: Exception) {
            SingleCallOutput(
                httpStatus = -3,
                rawError = e.localizedMessage ?: "Network error"
            )
        } finally {
            connection?.disconnect()
        }
    }

    private fun extractSuggestedModel(rawError: String, failedModel: String): String? {
        val regex = Regex("models/(gemini-[a-zA-Z0-9._-]+)")
        for (match in regex.findAll(rawError)) {
            val found = match.groupValues[1].trim().trimEnd('.', ',', ')')
            if (found.isNotBlank() && !found.equals(failedModel, ignoreCase = true)) {
                return found
            }
        }
        return null
    }

    private fun readStream(connection: HttpURLConnection, status: Int): String {
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        if (stream == null) return ""
        return BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
    }

    private fun formatApiError(status: Int, rawBody: String): String {
        if (status < 0) return rawBody

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
            400 -> "Tip: Check your API key or switch models in VoiceFlow Settings."
            401, 403 -> "Tip: Your API key was rejected. Create a fresh free key in Google AI Studio."
            404 -> "Tip: Open VoiceFlow Settings and pick gemini-3.8-flash in Change Model."
            429 -> "Tip: Free rate limit reached. Wait 15 seconds and try again."
            500, 503 -> "Tip: Google's server is busy right now. Try again in a moment."
            else -> "Tip: Check your internet connection."
        }

        return "HTTP $status: $googleMessage\n$fixTip"
    }
}
