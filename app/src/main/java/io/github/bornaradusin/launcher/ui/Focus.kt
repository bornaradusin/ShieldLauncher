package io.github.bornaradusin.launcher.ui

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

/** False under a menu or dialog: what's behind it can't take focus. */
val LocalTvEnabled = compositionLocalOf { true }

/** The remote's OK, the controller's A, and Enter on a keyboard. */
val OK_KEYS = setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.ButtonA)

fun KeyEvent.isOk() = key in OK_KEYS
fun KeyEvent.isDown() = type == KeyEventType.KeyDown

/**
 * Something on the TV that can be picked with the remote: OK does [onClick], holding OK does
 * [onLongClick] (once, as soon as it counts as held). [onKey] sees other keys first, for moving
 * things around. A press that started before this got focus doesn't count.
 */
fun Modifier.tvItem(
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    onFocus: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
    onFocusChange: ((Boolean) -> Unit)? = null,
    onKey: ((KeyEvent) -> Boolean)? = null,
): Modifier = composed {
    val enabled = LocalTvEnabled.current
    var pressed by remember { mutableStateOf(false) }
    var held by remember { mutableStateOf(false) }
    this
        .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
        .onFocusChanged {
            onFocusChange?.invoke(it.isFocused)
            if (it.isFocused) onFocus?.invoke() else {
                pressed = false
                held = false
            }
        }
        .onPreviewKeyEvent { e ->
            if (onKey?.invoke(e) == true) return@onPreviewKeyEvent true
            if (!e.isOk()) return@onPreviewKeyEvent false
            val native = e.nativeKeyEvent
            when (e.type) {
                KeyEventType.KeyDown -> {
                    if (native.repeatCount == 0) {
                        pressed = true
                        held = false
                    } else if (pressed && !held && onLongClick != null &&
                        (native.flags and AndroidKeyEvent.FLAG_LONG_PRESS != 0 || native.repeatCount >= 1)
                    ) {
                        held = true
                        onLongClick()
                    }
                    true
                }
                KeyEventType.KeyUp -> {
                    val click = pressed && !held
                    pressed = false
                    held = false
                    if (click) onClick()
                    true
                }
                else -> false
            }
        }
        .focusable(enabled)
}

/**
 * Where a row scrolls to as focus moves along it: the focused card stays at the row's left edge
 * (like the stock home screen), except at the very start and end.
 */
@OptIn(ExperimentalFoundationApi::class)
class PinStart(private val startPx: Float) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = offset - startPx
}

/**
 * Where Home scrolls to as focus moves between rows: the focused row's cards sit [pivotPx] down
 * from the top of the rows, with the row above showing over them, and never off the bottom.
 */
@OptIn(ExperimentalFoundationApi::class)
class PinRow(private val pivotPx: Float, private val bottomPx: Float) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
        offset - minOf(pivotPx, containerSize - size - bottomPx).coerceAtLeast(0f)
}
