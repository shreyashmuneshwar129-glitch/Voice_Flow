package com.voiceflow.app

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class VoiceFlowService : AccessibilityService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var prefs: PrefsManager
    private lateinit var windowManager: WindowManager
    private var bubbleOverlay: BubbleOverlayView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var isOverlayAdded = false

    private var audioRecorder: AudioWavRecorder? = null
    private var offlineSpeechHelper: OfflineSpeechHelper? = null
    private var isUsingOfflineMode = false
    private var lastTapTimestamp = 0L

    private var lastFocusedEditableNode: AccessibilityNodeInfo? = null
    private var recordingTargetNode: AccessibilityNodeInfo? = null

    // Snapshot of the text field & selection captured when the user taps the bubble
    private var capturedFullText: String = ""
    private var capturedSelectedText: String = ""
    private var capturedSelStart: Int = -1
    private var capturedSelEnd: Int = -1

    // Snapshot for the 3-second Undo chip
    private var undoNode: AccessibilityNodeInfo? = null
    private var undoPreviousText: String? = null
    private var undoPreviousCursor: Int = -1

    private val hideChipRunnable = Runnable {
        bubbleOverlay?.hideChip()
        undoNode = null
        undoPreviousText = null
    }

    private val windowPollRunnable = object : Runnable {
        override fun run() {
            evaluateKeyboardVisibility()
            if (bubbleOverlay?.visibility == View.VISIBLE) {
                mainHandler.postDelayed(this, 600L)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        prefs = PrefsManager(this)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        if (prefs.selectedModel.startsWith("gemini-2.")) {
            val better = prefs.availableModels
                .sortedBy { GeminiClient.rankModel(it) }
                .firstOrNull { !it.startsWith("gemini-2.") }
            prefs.selectedModel = better ?: "gemini-3.8-flash"
        }

        audioRecorder = AudioWavRecorder { amplitude ->
            mainHandler.post {
                bubbleOverlay?.updateAmplitude(amplitude)
            }
        }

        offlineSpeechHelper = OfflineSpeechHelper(
            context = this,
            onResult = { offlineText ->
                bubbleOverlay?.setState(BubbleOverlayView.State.IDLE)
                if (offlineText.isNotBlank()) {
                    insertCleanedText(
                        cleanText = offlineText,
                        wasOffline = true,
                        replaceEntireField = false
                    )
                }
            },
            onError = { errorMsg ->
                bubbleOverlay?.setState(BubbleOverlayView.State.IDLE)
                showTemporaryChip(errorMsg, isError = true, durationMs = 4000L)
            }
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        evaluateKeyboardVisibility()
    }

    override fun onInterrupt() {
        stopDictationIfActive()
    }

    override fun onDestroy() {
        instance = null
        mainHandler.removeCallbacksAndMessages(null)
        offlineSpeechHelper?.destroy()
        audioRecorder?.stopAndGetWavBytes()
        removeOverlayWindow()
        serviceScope.cancel()
        super.onDestroy()
    }

    fun refreshSettingsLive() {
        mainHandler.post {
            bubbleOverlay?.refreshBubbleSize()
            if (prefs.bubbleX == -1 && prefs.bubbleY == -1) {
                val lp = layoutParams ?: return@post
                val dm = resources.displayMetrics
                lp.x = dm.widthPixels - (dm.widthPixels / 4)
                lp.y = dm.heightPixels / 2 - (dm.heightPixels / 10)
                if (isOverlayAdded) {
                    try {
                        windowManager.updateViewLayout(bubbleOverlay, lp)
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

    private fun evaluateKeyboardVisibility() {
        if (!Settings.canDrawOverlays(this)) {
            hideBubble()
            return
        }

        var imeWindowVisible = false
        var focusedNode: AccessibilityNodeInfo? = null

        try {
            val windowList = windows
            if (windowList != null) {
                for (win in windowList) {
                    if (win.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                        imeWindowVisible = true
                    }
                    if (win.type == AccessibilityWindowInfo.TYPE_APPLICATION) {
                        val root = win.root
                        val candidate = root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                        if (candidate != null && candidate.isEditable) {
                            focusedNode = candidate
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }

        if (focusedNode == null) {
            try {
                val candidate = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                if (candidate != null && candidate.isEditable) {
                    focusedNode = candidate
                }
            } catch (_: Exception) {
            }
        }

        if (focusedNode != null) {
            lastFocusedEditableNode = focusedNode
        }

        if (imeWindowVisible) {
            showBubble()
        } else {
            val state = bubbleOverlay?.currentState ?: BubbleOverlayView.State.IDLE
            if (state == BubbleOverlayView.State.IDLE && undoPreviousText == null) {
                hideBubble()
            }
        }
    }

    private fun ensureOverlayCreated() {
        if (bubbleOverlay != null && isOverlayAdded) return

        val dm = resources.displayMetrics
        val defaultX = dm.widthPixels - (dm.widthPixels / 4)
        val defaultY = dm.heightPixels / 2 - (dm.heightPixels / 10)

        val startX = if (prefs.bubbleX >= 0) prefs.bubbleX else defaultX
        val startY = if (prefs.bubbleY >= 0) prefs.bubbleY else defaultY

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = startX
            y = startY
        }

        val view = BubbleOverlayView(
            context = this,
            prefs = prefs,
            onBubbleTapped = { handleBubbleTap() },
            onUndoTapped = { performUndo() },
            onDragMoved = { dx, dy ->
                val params = layoutParams ?: return@BubbleOverlayView
                params.x = (params.x + dx).coerceIn(0, dm.widthPixels - 80)
                params.y = (params.y + dy).coerceIn(40, dm.heightPixels - 140)
                try {
                    windowManager.updateViewLayout(bubbleOverlay, params)
                } catch (_: Exception) {
                }
            },
            onDragFinished = {
                val params = layoutParams ?: return@BubbleOverlayView
                prefs.bubbleX = params.x
                prefs.bubbleY = params.y
            }
        )

        try {
            windowManager.addView(view, lp)
            bubbleOverlay = view
            layoutParams = lp
            isOverlayAdded = true
        } catch (_: Exception) {
            isOverlayAdded = false
        }
    }

    private fun showBubble() {
        ensureOverlayCreated()
        val view = bubbleOverlay ?: return
        view.refreshBubbleSize()
        if (view.visibility != View.VISIBLE) {
            view.visibility = View.VISIBLE
            mainHandler.removeCallbacks(windowPollRunnable)
            mainHandler.postDelayed(windowPollRunnable, 600L)
        }
    }

    private fun hideBubble() {
        mainHandler.removeCallbacks(windowPollRunnable)
        bubbleOverlay?.visibility = View.GONE
        bubbleOverlay?.hideChip()
    }

    private fun removeOverlayWindow() {
        if (isOverlayAdded && bubbleOverlay != null) {
            try {
                windowManager.removeView(bubbleOverlay)
            } catch (_: Exception) {
            }
        }
        isOverlayAdded = false
        bubbleOverlay = null
    }

    private fun handleBubbleTap() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastTapTimestamp < 350L) return
        lastTapTimestamp = now

        val view = bubbleOverlay ?: return
        when (view.currentState) {
            BubbleOverlayView.State.IDLE -> startDictation()
            BubbleOverlayView.State.LISTENING -> stopAndProcessDictation()
            BubbleOverlayView.State.PROCESSING -> {
                // Ignore extra taps while Gemini is processing
            }
        }
    }

    private fun startDictation() {
        mainHandler.removeCallbacks(hideChipRunnable)
        bubbleOverlay?.hideChip()

        // Lock onto the current text field and capture any highlighted selection right now!
        val node = resolveActiveEditableNode()
        recordingTargetNode = node
        captureNodeSelectionSnapshot(node)

        val hasMicPerm = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasMicPerm) {
            showTemporaryChip("Mic permission missing. Open VoiceFlow app.", isError = true, durationMs = 4000L)
            return
        }

        if (!isInternetAvailable()) {
            isUsingOfflineMode = true
            bubbleOverlay?.setState(BubbleOverlayView.State.LISTENING)
            showTemporaryChip("Offline • Basic mode", isError = false, durationMs = 3500L)
            offlineSpeechHelper?.startListening()
            return
        }

        if (prefs.apiKey.isBlank()) {
            showTemporaryChip("Add your free Gemini API key in VoiceFlow app.", isError = true, durationMs = 4500L)
            return
        }

        isUsingOfflineMode = false
        val started = audioRecorder?.start() ?: false
        if (!started) {
            showTemporaryChip("Could not access microphone.", isError = true, durationMs = 3500L)
            return
        }

        bubbleOverlay?.setState(BubbleOverlayView.State.LISTENING)
    }

    private fun captureNodeSelectionSnapshot(node: AccessibilityNodeInfo?) {
        capturedFullText = ""
        capturedSelectedText = ""
        capturedSelStart = -1
        capturedSelEnd = -1

        if (node == null) return
        try {
            node.refresh()
            val isHint = node.isShowingHintText
            val text = if (isHint) "" else (node.text?.toString() ?: "")
            capturedFullText = text

            val rawStart = node.textSelectionStart
            val rawEnd = node.textSelectionEnd
            if (!isHint && rawStart >= 0 && rawEnd >= 0 && rawStart <= text.length && rawEnd <= text.length) {
                val s = minOf(rawStart, rawEnd)
                val e = maxOf(rawStart, rawEnd)
                capturedSelStart = s
                capturedSelEnd = e
                if (e > s) {
                    capturedSelectedText = text.substring(s, e)
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun stopAndProcessDictation() {
        if (isUsingOfflineMode) {
            bubbleOverlay?.setState(BubbleOverlayView.State.PROCESSING)
            offlineSpeechHelper?.stopListening()
            return
        }

        bubbleOverlay?.setState(BubbleOverlayView.State.PROCESSING)
        val wavBytes = audioRecorder?.stopAndGetWavBytes() ?: ByteArray(0)

        if (wavBytes.size < 11244) {
            bubbleOverlay?.setState(BubbleOverlayView.State.IDLE)
            return
        }

        // Also check if the user finished highlighting text while speaking
        val activeNode = resolveActiveEditableNode() ?: recordingTargetNode
        if (capturedSelectedText.isBlank() && activeNode != null) {
            captureNodeSelectionSnapshot(activeNode)
        }

        val selectedSnapshot = capturedSelectedText
        val fullTextSnapshot = capturedFullText

        serviceScope.launch {
            val result = GeminiClient.transcribeAndClean(
                apiKey = prefs.apiKey,
                modelName = prefs.selectedModel,
                availableModels = prefs.availableModels,
                wavBytes = wavBytes,
                selectedText = selectedSnapshot,
                existingFieldText = fullTextSnapshot
            )

            bubbleOverlay?.setState(BubbleOverlayView.State.IDLE)

            if (result.success) {
                if (result.usedModel.isNotBlank() && result.usedModel != prefs.selectedModel) {
                    prefs.selectedModel = result.usedModel
                }
                if (result.text.isNotBlank()) {
                    insertCleanedText(
                        cleanText = result.text,
                        wasOffline = false,
                        replaceEntireField = result.replaceEntireField
                    )
                }
            } else {
                showTemporaryChip(result.errorMessage, isError = true, durationMs = 6500L)
            }
        }
    }

    private fun stopDictationIfActive() {
        if (bubbleOverlay?.currentState == BubbleOverlayView.State.LISTENING) {
            if (isUsingOfflineMode) {
                offlineSpeechHelper?.stopListening()
            } else {
                audioRecorder?.stopAndGetWavBytes()
            }
            bubbleOverlay?.setState(BubbleOverlayView.State.IDLE)
        }
    }

    private fun insertCleanedText(
        cleanText: String,
        wasOffline: Boolean,
        replaceEntireField: Boolean
    ) {
        val targetNode = resolveActiveEditableNode() ?: recordingTargetNode
        if (targetNode == null) {
            showTemporaryChip("Tap inside a text box first.", isError = true, durationMs = 3500L)
            return
        }

        try {
            targetNode.refresh()
        } catch (_: Exception) {
        }

        val isHint = targetNode.isShowingHintText
        val existingText = if (isHint) "" else (targetNode.text?.toString() ?: "")

        val liveStart = targetNode.textSelectionStart
        val liveEnd = targetNode.textSelectionEnd

        val hasLiveCursor = !isHint &&
                liveStart >= 0 &&
                liveEnd >= 0 &&
                liveStart <= existingText.length &&
                liveEnd <= existingText.length

        val liveMin = if (hasLiveCursor) minOf(liveStart, liveEnd) else -1
        val liveMax = if (hasLiveCursor) maxOf(liveStart, liveEnd) else -1

        // If the user highlighted text before tapping the bubble, preserve that exact range even if
        // tapping the overlay caused the app to drop the highlight visually.
        val hasCapturedSelection = capturedSelStart >= 0 &&
                capturedSelEnd > capturedSelStart &&
                capturedSelEnd <= existingText.length

        val startIdx: Int
        val endIdx: Int

        when {
            replaceEntireField -> {
                startIdx = 0
                endIdx = existingText.length
            }
            liveMax > liveMin -> {
                startIdx = liveMin
                endIdx = liveMax
            }
            hasCapturedSelection -> {
                startIdx = capturedSelStart
                endIdx = capturedSelEnd
            }
            hasLiveCursor -> {
                startIdx = liveMin
                endIdx = liveMax
            }
            else -> {
                startIdx = existingText.length
                endIdx = existingText.length
            }
        }

        val before = existingText.substring(0, startIdx)
        val after = existingText.substring(endIdx)

        val isReplacingSelection = endIdx > startIdx
        val needsLeadingSpace = !isReplacingSelection &&
                before.isNotEmpty() &&
                !before.last().isWhitespace() &&
                cleanText.firstOrNull() !in listOf('.', ',', '?', '!', ':', ';')

        val prefixSpace = if (needsLeadingSpace) " " else ""
        val newText = before + prefixSpace + cleanText + after
        val newCursorPos = (before.length + prefixSpace.length + cleanText.length).coerceAtMost(newText.length)

        val setArgs = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
        }

        var setSuccess = targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setArgs)

        if (setSuccess) {
            val cursorArgs = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, newCursorPos)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, newCursorPos)
            }
            targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, cursorArgs)
        } else {
            targetNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val textToPaste = prefixSpace + cleanText
            clipboard.setPrimaryClip(ClipData.newPlainText("VoiceFlow", textToPaste))
            setSuccess = targetNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        }

        undoNode = targetNode
        undoPreviousText = existingText
        undoPreviousCursor = startIdx

        bubbleOverlay?.performBubbleHaptic()
        if (wasOffline) {
            showTemporaryChip("Offline • Basic mode", isError = false, durationMs = 1500L)
            mainHandler.postDelayed({
                bubbleOverlay?.showUndoChip()
                mainHandler.removeCallbacks(hideChipRunnable)
                mainHandler.postDelayed(hideChipRunnable, 3000L)
            }, 1200L)
        } else {
            bubbleOverlay?.showUndoChip()
            mainHandler.removeCallbacks(hideChipRunnable)
            mainHandler.postDelayed(hideChipRunnable, 3000L)
        }
    }

    private fun performUndo() {
        val node = undoNode ?: return
        val prevText = undoPreviousText ?: return
        val prevCursor = undoPreviousCursor

        try {
            node.refresh()
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, prevText)
            }
            val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            if (ok && prevCursor in 0..prevText.length) {
                val selArgs = Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, prevCursor)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, prevCursor)
                }
                node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selArgs)
            }
        } catch (_: Exception) {
        }

        mainHandler.removeCallbacks(hideChipRunnable)
        bubbleOverlay?.hideChip()
        undoNode = null
        undoPreviousText = null
    }

    private fun resolveActiveEditableNode(): AccessibilityNodeInfo? {
        try {
            val active = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (active != null && active.isEditable) return active
        } catch (_: Exception) {
        }
        try {
            val winList = windows
            if (winList != null) {
                for (win in winList) {
                    val focus = win.root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    if (focus != null && focus.isEditable) return focus
                }
            }
        } catch (_: Exception) {
        }
        return lastFocusedEditableNode
    }

    private fun showTemporaryChip(message: String, isError: Boolean, durationMs: Long) {
        mainHandler.removeCallbacks(hideChipRunnable)
        bubbleOverlay?.showInfoChip(message, isError = isError)
        mainHandler.postDelayed(hideChipRunnable, durationMs)
    }

    private fun isInternetAvailable(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    companion object {
        var instance: VoiceFlowService? = null
            private set
    }
}
