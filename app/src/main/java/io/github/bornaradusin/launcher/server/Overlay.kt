package io.github.bornaradusin.launcher.server

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import io.github.bornaradusin.launcher.R

/** A notification for the screen: what Home Assistant (or anything else on the network) sent. */
data class Notice(
    val title: String,
    val message: String,
    val seconds: Int,
    /** 0 bottom right, 1 bottom left, 2 top right, 3 top left, 4 centre. */
    val position: Int,
    /** 0 medium, 1 small, 2 large, 3 largest. */
    val fontSize: Int,
    val color: Int,
    /** 0 to 1: how see-through the background is. */
    val transparency: Float,
    /** Stays until OK or Back is pressed (or the time runs out). */
    val interrupt: Boolean,
    val icon: Bitmap?,
    val image: Bitmap?,
)

/**
 * Shows notifications over whatever is on the TV (needs "Display over other apps"). A new one takes
 * the place of the one showing.
 */
class Overlay(private val context: Context) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var current: View? = null
    private val hide = Runnable { dismiss() }

    fun allowed() = Settings.canDrawOverlays(context)

    fun show(n: Notice) {
        main.post {
            if (!allowed()) return@post
            removeNow()
            val view = build(n)
            val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            var flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            if (!n.interrupt) flags = flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                flags,
                PixelFormat.TRANSLUCENT,
            )
            lp.gravity = when (n.position) {
                1 -> Gravity.BOTTOM or Gravity.START
                2 -> Gravity.TOP or Gravity.END
                3 -> Gravity.TOP or Gravity.START
                4 -> Gravity.CENTER
                else -> Gravity.BOTTOM or Gravity.END
            }
            lp.x = dp(36)
            lp.y = dp(36)
            runCatching { wm.addView(view, lp) }.onFailure { return@post }
            current = view
            view.alpha = 0f
            view.translationY = if (n.position == 2 || n.position == 3) -dp(16).toFloat() else dp(16).toFloat()
            view.animate().alpha(1f).translationY(0f).setDuration(220).start()
            if (n.interrupt) view.requestFocus()
            main.removeCallbacks(hide)
            main.postDelayed(hide, n.seconds.coerceIn(1, 600) * 1000L)
        }
    }

    fun dismiss() {
        main.post {
            val v = current ?: return@post
            main.removeCallbacks(hide)
            v.animate().alpha(0f).setDuration(200).setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (current === v) removeNow()
                }
            }).start()
        }
    }

    private fun removeNow() {
        current?.let { runCatching { wm.removeView(it) } }
        current = null
    }

    private fun build(n: Notice): View {
        val scale = when (n.fontSize) {
            1 -> 0.85f
            2 -> 1.25f
            3 -> 1.6f
            else -> 1f
        }
        val regular = ResourcesCompat.getFont(context, R.font.outfit_regular) ?: Typeface.DEFAULT
        val bold = ResourcesCompat.getFont(context, R.font.outfit_semibold) ?: Typeface.DEFAULT_BOLD

        val card = object : LinearLayout(context) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_UP && event.keyCode in CLOSE_KEYS) {
                    dismiss()
                    return true
                }
                return event.keyCode in CLOSE_KEYS || super.dispatchKeyEvent(event)
            }
        }
        card.orientation = LinearLayout.VERTICAL
        card.isFocusable = n.interrupt
        card.isFocusableInTouchMode = n.interrupt
        val alpha = ((1f - n.transparency).coerceIn(0f, 1f) * 255).toInt()
        card.background = GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(Color.argb(alpha, Color.red(n.color), Color.green(n.color), Color.blue(n.color)))
            setStroke(dp(1), Color.argb(40, 255, 255, 255))
        }
        card.setPadding(dp(20), dp(16), dp(22), dp(18))
        card.elevation = dp(12).toFloat()

        val top = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        n.icon?.takeIf { it.width > 2 && it.height > 2 }?.let { b ->
            top.addView(
                ImageView(context).apply {
                    setImageBitmap(b)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                },
                LinearLayout.LayoutParams((dp(44) * scale).toInt(), (dp(44) * scale).toInt()).apply { marginEnd = dp(16) },
            )
        }
        val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        if (n.title.isNotBlank()) {
            texts.addView(TextView(context).apply {
                text = n.title
                typeface = bold
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f * scale)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            })
        }
        if (n.message.isNotBlank()) {
            texts.addView(TextView(context).apply {
                text = n.message
                typeface = regular
                setTextColor(Color.argb(235, 255, 255, 255))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f * scale)
                maxLines = 8
                ellipsize = TextUtils.TruncateAt.END
                setLineSpacing(0f, 1.1f)
            })
        }
        top.addView(texts, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        card.addView(top)

        n.image?.let { b ->
            val maxW = (dp(420) * scale).toInt().coerceAtMost(context.resources.displayMetrics.widthPixels * 2 / 3)
            val w = minOf(maxW, b.width * 2)
            val h = (w.toFloat() * b.height / b.width).toInt().coerceAtMost(context.resources.displayMetrics.heightPixels * 2 / 3)
            val frame = FrameLayout(context).apply {
                clipToOutline = true
                outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
                background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(Color.BLACK) }
            }
            frame.addView(ImageView(context).apply {
                setImageBitmap(b)
                scaleType = ImageView.ScaleType.CENTER_CROP
            }, FrameLayout.LayoutParams(w, h))
            card.addView(frame, LinearLayout.LayoutParams(w, h).apply { topMargin = dp(14) })
        }
        if (n.interrupt) {
            card.addView(TextView(context).apply {
                text = "OK or Back to close"
                typeface = regular
                setTextColor(Color.argb(170, 255, 255, 255))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        }
        card.minimumWidth = dp(300)
        return card
    }

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    companion object {
        private val CLOSE_KEYS = setOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B)
    }
}
