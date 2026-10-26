package com.voiceflow.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * Pixel-style floating overlay containing:
 * 1) The circular microphone bubble with a pulsing ring (when listening) and a circular loader (when processing).
 * 2) A compact tonal pill chip right below the bubble for the 3-second "Undo" action,
 *    "Offline • basic mode" indicator, or readable API error messages.
 */
class BubbleOverlayView(
    context: Context,
    private val prefs: PrefsManager,
    private val onBubbleTapped: () -> Unit,
    private val onUndoTapped: () -> Unit,
    private val onDragMoved: (dx: Int, dy: Int) -> Unit,
    private val onDragFinished: () -> Unit
) : LinearLayout(context) {

    enum class State {
        IDLE,
        LISTENING,
        PROCESSING
    }

    var currentState: State = State.IDLE
        private set

    private var micAmplitude: Float = 0f
    private val bubbleView: BubbleCircleView
    private val chipView: TextView
    private var isUndoChipActive = false

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        clipChildren = false
        clipToPadding = false
        val pad = dpToPx(10f)
        setPadding(pad, pad, pad, pad)

        bubbleView = BubbleCircleView(context)
        val sizePx = dpToPx(prefs.getBubbleSizeDp().toFloat())
        val bubbleParams = LayoutParams(sizePx, sizePx)
        addView(bubbleView, bubbleParams)

        chipView = TextView(context).apply {
            visibility = View.GONE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Color.WHITE)
            val hPad = dpToPx(12f)
            val vPad = dpToPx(6f)
            setPadding(hPad, vPad, hPad, vPad)
            maxWidth = dpToPx(240f)
            elevation = dpToPx(4f).toFloat()
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(18f).toFloat()
                setColor(Color.parseColor("#EE1E1F25"))
                setStroke(dpToPx(1f), Color.parseColor("#44FFFFFF"))
            }
            setOnClickListener {
                if (isUndoChipActive) {
                    performBubbleHaptic()
                    onUndoTapped()
                } else {
                    hideChip()
                }
            }
        }

        val chipParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dpToPx(6f)
        }
        addView(chipView, chipParams)
    }

    fun refreshBubbleSize() {
        val sizePx = dpToPx(prefs.getBubbleSizeDp().toFloat())
        val lp = bubbleView.layoutParams
        lp.width = sizePx
        lp.height = sizePx
        bubbleView.layoutParams = lp
        bubbleView.invalidate()
    }

    fun setState(newState: State) {
        if (currentState == newState) return
        currentState = newState
        bubbleView.onStateChanged(newState)
    }

    fun updateAmplitude(amplitude: Float) {
        micAmplitude = amplitude.coerceIn(0f, 1f)
        if (currentState == State.LISTENING) {
            bubbleView.invalidate()
        }
    }

    fun showUndoChip() {
        isUndoChipActive = true
        chipView.text = "↶ Undo"
        (chipView.background as? GradientDrawable)?.setColor( resolvePrimaryColor(context) )
        chipView.visibility = View.VISIBLE
    }

    fun showInfoChip(message: String, isError: Boolean = false) {
        isUndoChipActive = false
        chipView.text = message
        val bgColor = if (isError) Color.parseColor("#EE8C1D18") else Color.parseColor("#EE282A36")
        (chipView.background as? GradientDrawable)?.setColor(bgColor)
        chipView.visibility = View.VISIBLE
    }

    fun hideChip() {
        isUndoChipActive = false
        chipView.visibility = View.GONE
    }

    fun performBubbleHaptic() {
        if (prefs.hapticsEnabled) {
            bubbleView.performHapticFeedback(
                HapticFeedbackConstants.VIRTUAL_KEY,
                HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
            )
        }
    }

    private fun dpToPx(dp: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            resources.displayMetrics
        ).toInt()
    }

    private fun resolvePrimaryColor(ctx: Context): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ctx.getColor(android.R.color.system_accent1_600)
        } else {
            Color.parseColor("#4A6CF7")
        }
    }

    private inner class BubbleCircleView(context: Context) : View(context) {

        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
        }
        private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private val iconFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        private val loaderRect = RectF()
        private val micCapsuleRect = RectF()
        private val micArcRect = RectF()

        private var pulsePhase = 0f
        private var spinnerAngle = 0f

        private val pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1100L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener {
                pulsePhase = it.animatedValue as Float
                invalidate()
            }
        }

        private val spinnerAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 950L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                spinnerAngle = it.animatedValue as Float
                invalidate()
            }
        }

        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        private var downRawX = 0f
        private var downRawY = 0f
        private var lastRawX = 0f
        private var lastRawY = 0f
        private var isDragging = false

        init {
            isClickable = true
        }

        fun onStateChanged(state: State) {
            pulseAnimator.cancel()
            spinnerAnimator.cancel()
            when (state) {
                State.IDLE -> {
                    pulsePhase = 0f
                    spinnerAngle = 0f
                }
                State.LISTENING -> {
                    pulseAnimator.start()
                }
                State.PROCESSING -> {
                    spinnerAnimator.start()
                }
            }
            invalidate()
        }

        override fun onDetachedFromWindow() {
            pulseAnimator.cancel()
            spinnerAnimator.cancel()
            super.onDetachedFromWindow()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val maxRadius = minOf(cx, cy)
            val baseRadius = maxRadius * 0.78f
            val primaryColor = resolvePrimaryColor(context)

            // 1. Pulsing outer ring while listening
            if (currentState == State.LISTENING) {
                val boost = (pulsePhase * 0.55f) + (micAmplitude * 0.45f)
                val ringRadius = baseRadius + (maxRadius - baseRadius) * boost.coerceIn(0.15f, 1f)
                ringPaint.color = Color.argb(
                    (130 * (1f - pulsePhase * 0.5f)).toInt().coerceIn(40, 160),
                    Color.red(primaryColor),
                    Color.green(primaryColor),
                    Color.blue(primaryColor)
                )
                ringPaint.strokeWidth = dpToPx(3.5f).toFloat()
                canvas.drawCircle(cx, cy, ringRadius, ringPaint)
            }

            // 2. Main circular button surface
            bgPaint.color = when (currentState) {
                State.IDLE -> primaryColor
                State.LISTENING -> Color.parseColor("#D9383A") // Expressive recording coral/red
                State.PROCESSING -> primaryColor
            }
            canvas.drawCircle(cx, cy, baseRadius, bgPaint)

            // 3. Processing circular loader OR Microphone icon
            if (currentState == State.PROCESSING) {
                val loaderRadius = baseRadius * 0.48f
                loaderRect.set(
                    cx - loaderRadius,
                    cy - loaderRadius,
                    cx + loaderRadius,
                    cy + loaderRadius
                )
                iconPaint.strokeWidth = dpToPx(3f).toFloat()
                iconPaint.color = Color.argb(75, 255, 255, 255)
                canvas.drawOval(loaderRect, iconPaint)

                iconPaint.color = Color.WHITE
                canvas.drawArc(loaderRect, spinnerAngle, 250f, false, iconPaint)
            } else {
                drawMicrophoneIcon(canvas, cx, cy, baseRadius)
            }
        }

        private fun drawMicrophoneIcon(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
            val scale = radius / 28f
            iconPaint.color = Color.WHITE
            iconPaint.strokeWidth = 2.6f * scale

            // Capsule body
            val capW = 4.2f * scale
            val capTop = cy - 9.5f * scale
            val capBottom = cy + 1.5f * scale
            micCapsuleRect.set(cx - capW, capTop, cx + capW, capBottom)
            canvas.drawRoundRect(micCapsuleRect, capW, capW, iconFillPaint)

            // U-cradle
            val cradleR = 8.2f * scale
            val cradleCy = cy - 2.0f * scale
            micArcRect.set(cx - cradleR, cradleCy - cradleR, cx + cradleR, cradleCy + cradleR)
            canvas.drawArc(micArcRect, 0f, 180f, false, iconPaint)

            // Stem and base
            val stemTop = cradleCy + cradleR
            val stemBottom = cy + 10.2f * scale
            canvas.drawLine(cx, stemTop, cx, stemBottom, iconPaint)
            canvas.drawLine(cx - 4.5f * scale, stemBottom, cx + 4.5f * scale, stemBottom, iconPaint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                    isDragging = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val totalDx = event.rawX - downRawX
                    val totalDy = event.rawY - downRawY
                    if (!isDragging && (abs(totalDx) > touchSlop || abs(totalDy) > touchSlop)) {
                        isDragging = true
                    }
                    if (isDragging) {
                        val dx = (event.rawX - lastRawX).toInt()
                        val dy = (event.rawY - lastRawY).toInt()
                        lastRawX = event.rawX
                        lastRawY = event.rawY
                        onDragMoved(dx, dy)
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (isDragging) {
                        onDragFinished()
                    } else {
                        performBubbleHaptic()
                        onBubbleTapped()
                    }
                    isDragging = false
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (isDragging) {
                        onDragFinished()
                    }
                    isDragging = false
                    return true
                }
            }
            return super.onTouchEvent(event)
        }
    }
}
