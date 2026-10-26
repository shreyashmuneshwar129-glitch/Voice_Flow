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
import kotlin.math.abs
import kotlin.math.sqrt

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
        val replaceEntireField: Boolean = false,
        val errorMessage: String = ""
    )

    private const val SYSTEM_PROMPT = """
You are VoiceFlow, an intelligent AI voice-dictation, smart-formatting, and text-editing assistant.
Your job is to turn the user's spoken audio into the exact final text they want in their text box.

STRICT RULES:
1. SILENCE & NOISE GUARD (CRITICAL):
   - If the audio contains ONLY silence, breathing, mic tap clicks, wind, static, or indistinct background noise with NO clear intentional human speech, output ONLY the exact token: [SILENCE]
   - NEVER guess, invent, or hallucinate random words (like "thank you", "okay", "bye", etc.) when the audio is silent or unclear.

2. CONTEXT-AWARE TEXT EDITING & QUESTION ANSWERING (WHEN SELECTED TEXT OR EXISTING TEXT IS PROVIDED):
   - Case A: SELECTED_TEXT is provided (the user highlighted text before speaking):
     * If the user speaks a command like "answer it", "solve this", "what is the answer", "emojify it", "improve it", "shorten it", "make it professional", "fix grammar", "translate it", or "bullet points", apply that command directly to SELECTED_TEXT and output ONLY the result (e.g., if SELECTED_TEXT is "Who was the first Prime Minister of India?" and audio is "answer it", output "Jawaharlal Nehru").
     * NEVER output the literal command words (like "Answer it") when transforming SELECTED_TEXT!
     * If the user is NOT giving a command and is simply dictating new replacement words, output the cleaned dictated text.
   - Case B: No text is highlighted, but EXISTING_FIELD_TEXT is provided:
     * If the user's spoken audio is purely a command referring to the existing text in the box (e.g., "answer it", "solve this", "emojify this", "improve it", "shorten it"), prefix your response with the exact tag [EDIT_ALL] followed immediately by the transformed/answered version of EXISTING_FIELD_TEXT.
     * If the user is dictating new text to add, DO NOT use [EDIT_ALL]—just output the new cleaned text.

3. INLINE KNOWLEDGE & VOICE COMMANDS:
   - If the user asks to insert/add/write a real-world fact, name, or answer a standalone question (either standalone or mid-sentence), DO NOT transcribe the command literally—resolve it and output the actual answer!
   - Examples:
     * Spoken: "Who was the first Prime Minister of India?" -> Output: Jawaharlal Nehru
     * Spoken: "add the current Prime Minister of India" -> Output: Narendra Modi
     * Spoken: "add the capital of Maharashtra" -> Output: Mumbai
     * Spoken: "write a short birthday wish for Rahul" -> Output: Happy Birthday, Rahul! Wishing you a fantastic year ahead.

4. SMART AUTO-FORMATTING (LISTS & TO-DOS):
   - GROCERY / SHOPPING / ITEM LISTS: When the user says they are making a grocery list, shopping list, or dictates a list of items (e.g., "I am writing a grocery list milk eggs oats chapati"), format it cleanly with bullet points:
     Grocery List:
     • Milk
     • Eggs
     • Oats
     • Chapati
   - TO-DO / TASK LISTS: When the user says "I have to do things today...", "my to-do list...", or lists tasks they need to complete (e.g., "I have to do things today go to the gym study business law call mom"), format it as a clean checklist:
     To-Do List:
     ☐ Go to the gym
     ☐ Study Business Law
     ☐ Call mom
   - NUMBERED STEPS: If the user dictates "first..., second..., third..." or asks for a numbered list, format as a numbered list (1., 2., 3.).

5. DICTATION CLEANUP & SELF-CORRECTIONS:
   - Remove filler words ("um", "uh", "like", "you know", "matlab", "accha") and stutters.
   - Fix grammar, punctuation, and capitalization naturally.
   - When the speaker corrects themselves mid-sentence ("send it at 5, no 6", "meet Tuesday sorry Wednesday"), output ONLY the final intended version ("Send it at 6.", "Meet Wednesday.").

6. LANGUAGES & SCRIPTS:
   - The user speaks English, Hinglish, and Marathi, and may switch mid-sentence.
   - NEVER translate unless the user explicitly says "translate to [language]".
   - Hinglish MUST stay in Roman script (English letters).
   - Marathi MUST ALWAYS be written in Roman script (English letters, e.g., "Tu kasa aahes?"). NEVER use Devanagari script.

7. SPOKEN EMOJIS:
   - Replace spoken emoji names with the emoji character ("how are you smiling emoji" -> "How are you 😊", "thumbs up emoji" -> "👍", "laughing emoji" -> "😂", "red heart emoji" -> "❤️", "fire emoji" -> "🔥", "party emoji" -> "🎉", "folded hands emoji" -> "🙏").

8. OUTPUT FORMAT:
   - Output ONLY the final text to be typed (or [SILENCE] if no speech). Never add conversational wrapper text like "Here is the answer:" or wrap the output in quotes.
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
        wavBytes: ByteArray,
        selectedText: String = "",
        existingFieldText: String = ""
    ): DictationResult = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        if (cleanKey.isEmpty()) {
            return@withContext DictationResult(
                success = false,
                errorMessage = "API key missing. Open VoiceFlow app and add your Gemini API key."
            )
        }

        if (!hasHumanSpeech(wavBytes)) {
            return@withContext DictationResult(
                success = true,
                text = "",
                usedModel = modelName
            )
        }

        val base64Audio = Base64.encodeToString(wavBytes, Base64.NO_WRAP)

        val modelsToTry = LinkedHashSet<String>()
        val initial = modelName.trim().removePrefix("models/")
        if (initial.isNotBlank() && !initial.startsWith("gemini-2.5") && !initial.startsWith("gemini-2.0")) {
            modelsToTry.add(initial)
        }
        availableModels.sortedBy { rankModel(it) }.forEach {
            val clean = it.trim().removePrefix("models/")
            if (clean.isNotBlank()) modelsToTry.add(clean)
        }
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
                selectedText = selectedText,
                existingFieldText = existingFieldText,
                useLowThinking = true
            )

            if (attempt.httpStatus in 200..299) {
                val parsed = parseModelOutput(attempt.text)
                return@withContext DictationResult(
                    success = true,
                    text = parsed.first,
                    usedModel = candidate,
                    replaceEntireField = parsed.second
                )
            }

            if (attempt.httpStatus == 400 && attempt.rawError.contains("thinking", ignoreCase = true)) {
                val retryNoThinking = callGenerateContentSingle(
                    apiKey = cleanKey,
                    model = candidate,
                    base64Audio = base64Audio,
                    selectedText = selectedText,
                    existingFieldText = existingFieldText,
                    useLowThinking = false
                )
                if (retryNoThinking.httpStatus in 200..299) {
                    val parsed = parseModelOutput(retryNoThinking.text)
                    return@withContext DictationResult(
                        success = true,
                        text = parsed.first,
                        usedModel = candidate,
                        replaceEntireField = parsed.second
                    )
                }
            }

            lastError = formatApiError(attempt.httpStatus, attempt.rawError)

            if (attempt.httpStatus == 404 || attempt.httpStatus == 429 || attempt.httpStatus == 503) {
                val suggested = extractSuggestedModel(attempt.rawError, candidate)
                if (suggested != null && suggested !in tried) {
                    queue.addFirst(suggested)
                }
                continue
            } else {
                break
            }
        }

        DictationResult(
            success = false,
            errorMessage = lastError
        )
    }

    private fun hasHumanSpeech(wavBytes: ByteArray): Boolean {
        if (wavBytes.size < 11244) return false

        val startByte = 44 + 3840
        val endByte = wavBytes.size - 3840
        if (endByte - startByte < 3200) return false

        val frameBytes = 640
        var activeSpeechFrames = 0
        var loudPeakCount = 0

        var offset = startByte
        while (offset + frameBytes <= endByte) {
            var sumSquares = 0.0
            var framePeak = 0
            var i = offset
            while (i + 1 < offset + frameBytes) {
                val low = wavBytes[i].toInt() and 0xFF
                val high = wavBytes[i + 1].toInt()
                val sample = (high shl 8) or low
                val absSample = abs(sample)
                if (absSample > framePeak) framePeak = absSample
                sumSquares += (sample * sample).toDouble()
                i += 2
            }
            val rms = sqrt(sumSquares / 320.0)
            if (rms > 380.0 && framePeak > 1100) {
                activeSpeechFrames++
            }
            if (framePeak > 1800) {
                loudPeakCount++
            }
            offset += frameBytes
        }

        return activeSpeechFrames >= 4 && loudPeakCount >= 2
    }

    private fun parseModelOutput(raw: String): Pair<String, Boolean> {
        var trimmed = raw.trim()
        if (trimmed.isEmpty()) return Pair("", false)
        if (trimmed.contains("[SILENCE]", ignoreCase = true) ||
            trimmed.equals("SILENCE", ignoreCase = true) ||
            trimmed.equals("[EMPTY]", ignoreCase = true)
        ) {
            return Pair("", false)
        }

        var replaceAll = false
        if (trimmed.startsWith("[EDIT_ALL]", ignoreCase = true)) {
            replaceAll = true
            trimmed = trimmed.substring("[EDIT_ALL]".length).trim()
        }
        return Pair(trimmed, replaceAll)
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
        selectedText: String,
        existingFieldText: String,
        useLowThinking: Boolean
    ): SingleCallOutput {
        val genConfig = JSONObject()
            .put("temperature", 0.15)
            .put("maxOutputTokens", 4096)

        if (useLowThinking) {
            val lower = model.lowercase()
            if (lower.startsWith("gemini-3") || lower.startsWith("gemini-4")) {
                genConfig.put("thinkingConfig", JSONObject().put("thinkingLevel", "low"))
            } else if (lower.startsWith("gemini-2.5") && !lower.contains("lite")) {
                genConfig.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
            }
        }

        val userPromptText = when {
            selectedText.isNotBlank() -> {
                """
                SELECTED_TEXT (currently highlighted by the user in their text box):
                \"\"\"$selectedText\"\"\"
                
                INSTRUCTION:
                Listen to the audio.
                - If the user is speaking a command for SELECTED_TEXT (such as "answer it", "solve this", "emojify it", "improve it", "shorten it", "make it professional", "fix grammar", "translate it", etc.), apply that instruction/answer to SELECTED_TEXT and output ONLY the result (do NOT output the command words!).
                - If the user is dictating completely new replacement text (not a command), output the cleaned replacement text.
                - If the audio is silent/noise, output [SILENCE].
                """.trimIndent()
            }
            existingFieldText.isNotBlank() -> {
                """
                EXISTING_FIELD_TEXT (currently inside the user's text box, though not highlighted):
                \"\"\"$existingFieldText\"\"\"
                
                INSTRUCTION:
                Listen to the audio.
                - If the user is speaking purely a command meant for the existing text in the box (such as "answer it", "solve this", "emojify it", "improve it", "shorten it"), prefix your output with [EDIT_ALL] followed immediately by the answered/transformed version of EXISTING_FIELD_TEXT.
                - Otherwise, treat the audio as new dictation/commands and output ONLY the new text without [EDIT_ALL].
                - If the audio is silent/noise, output [SILENCE].
                """.trimIndent()
            }
            else -> {
                "Process this voice input following all system rules: if silent/noise output [SILENCE]; if the speaker asks to answer a question or add a fact (e.g., 'Who was the first Prime Minister of India?'), output the actual answer; if dictating a grocery list or to-do list, format it into a clean bulleted or checkbox list; otherwise output the cleaned dictation."
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
                                JSONObject().put("text", userPromptText)
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
