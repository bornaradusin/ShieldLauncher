package io.github.bornaradusin.launcher.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage

/** What every card shares: grows a little and gets a white ring when focused. */
@Composable
fun FocusCard(
    width: Dp,
    height: Dp,
    radius: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    onFocus: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
    onKey: ((KeyEvent) -> Boolean)? = null,
    ring: Color = Tv.ink,
    forceRing: Boolean = false,
    grow: Float = 1.08f,
    content: @Composable (focused: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val lit = focused || forceRing
    val scale by animateFloatAsState(if (lit) grow else 1f, tween(160), label = "grow")
    val shape = RoundedCornerShape(radius)
    Box(
        modifier
            .zIndex(if (lit) 1f else 0f)
            .size(width, height)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .shadow(if (lit) 18.dp else 6.dp, shape, clip = false)
            .then(if (lit) Modifier.border(2.dp, ring, shape) else Modifier)
            .clip(shape)
            .tvItem(
                onClick = onClick,
                onLongClick = onLongClick,
                onFocus = onFocus,
                focusRequester = focusRequester,
                onFocusChange = { focused = it },
                onKey = onKey,
            ),
    ) {
        content(focused)
    }
}

/** A tile in the apps row: the app's TV banner, or its icon and name. */
@Composable
fun AppTile(
    label: String,
    banner: ImageBitmap?,
    icon: ImageBitmap?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onFocus: () -> Unit,
    moving: Boolean,
    onKey: ((KeyEvent) -> Boolean)?,
    focusRequester: FocusRequester?,
    modifier: Modifier = Modifier,
) {
    FocusCard(
        width = 108.dp, height = 61.dp, radius = 10.dp,
        onClick = onClick, onLongClick = onLongClick, onFocus = onFocus, onKey = onKey,
        focusRequester = focusRequester, ring = if (moving) Tv.accent else Tv.ink, forceRing = moving, grow = 1.1f,
        modifier = modifier,
    ) {
        if (banner != null) {
            Image(banner, label, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Column(
                Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF1B2433), Color(0xFF0D1119)))).padding(9.dp, 8.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                if (icon != null) Image(icon, null, Modifier.size(22.dp))
                Text(label, style = Tv.text(10.5f, FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** The dashed "Add an app" tile at the end of the apps row. */
@Composable
fun AddTile(onClick: () -> Unit, onFocus: () -> Unit, focusRequester: FocusRequester?) {
    FocusCard(108.dp, 61.dp, 10.dp, onClick = onClick, onFocus = onFocus, focusRequester = focusRequester, grow = 1.1f) { focused ->
        Column(
            Modifier.fillMaxSize()
                .background(if (focused) Color(0x1FFFFFFF) else Color.Transparent)
                .drawWithContent {
                    drawContent()
                    drawRoundRect(
                        color = Color(0xFF3A4556),
                        cornerRadius = CornerRadius(10.dp.toPx()),
                        style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))),
                    )
                },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(Ic.plus, null, Modifier.size(15.dp), tint = Tv.muted)
            Spacer(Modifier.height(4.dp))
            Text("Add an app", style = Tv.text(10f, color = Tv.muted))
        }
    }
}

/** A wide card for Continue watching: the picture, the name, and how far through it is. */
@Composable
fun WideCard(
    title: String,
    sub: String,
    image: String?,
    progress: Float,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onFocus: () -> Unit,
    focusRequester: FocusRequester?,
) {
    FocusCard(200.dp, 112.dp, 11.dp, onClick = onClick, onLongClick = onLongClick, onFocus = onFocus, focusRequester = focusRequester) {
        Box(Modifier.fillMaxSize().background(Tv.panel)) {
            AsyncImage(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.4f to Color.Transparent, 1f to Color(0xD9000000))))
            Column(Modifier.align(Alignment.BottomStart).padding(start = 10.dp, end = 10.dp, bottom = 11.dp)) {
                Text(title, style = Tv.cardTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(sub, style = Tv.cardSub, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (progress > 0f) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color(0x2EFFFFFF))) {
                    Box(Modifier.fillMaxWidth(progress).height(3.dp).background(Tv.accent))
                }
            }
        }
    }
}

/** A poster (films, shows, games), with its name at the bottom and an optional tag at the top. */
@Composable
fun PosterCard(
    title: String,
    image: String?,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    onFocus: () -> Unit,
    focusRequester: FocusRequester?,
    tag: String? = null,
    watched: Boolean = false,
    imageModel: Any? = image,
) {
    FocusCard(98.dp, 147.dp, 9.dp, onClick = onClick, onLongClick = onLongClick, onFocus = onFocus, focusRequester = focusRequester, grow = 1.1f) {
        Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF1B2433), Color(0xFF0D1119))))) {
            // The name shows under the picture until it loads (and stays if there's none)
            Text(
                title, style = Tv.text(10f, FontWeight.SemiBold, Tv.ink2, lineHeight = 12f),
                modifier = Modifier.align(Alignment.Center).padding(8.dp), maxLines = 4, overflow = TextOverflow.Ellipsis,
            )
            AsyncImage(imageModel, title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            if (tag != null) {
                Text(
                    tag, style = Tv.text(6.5f, FontWeight.Bold, Tv.accent, spacing = 0.75f),
                    modifier = Modifier.align(Alignment.TopStart).padding(7.dp).clip(RoundedCornerShape(3.dp))
                        .background(Color(0x8C000000)).padding(horizontal = 4.dp, vertical = 1.5.dp),
                )
            }
            if (watched) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(6.dp).size(16.dp).clip(RoundedCornerShape(8.dp)).background(Tv.accent),
                    contentAlignment = Alignment.Center,
                ) { Icon(Ic.check, null, Modifier.size(11.dp), tint = Tv.bg) }
            }
        }
    }
}

/** A YouTube video: the thumbnail with its length, then the title and channel underneath. */
@Composable
fun VideoCard(
    title: String,
    channel: String,
    duration: String,
    thumbnail: String,
    onClick: () -> Unit,
    onFocus: () -> Unit,
    focusRequester: FocusRequester?,
) {
    Column(Modifier.width(200.dp)) {
        FocusCard(200.dp, 112.dp, 11.dp, onClick = onClick, onFocus = onFocus, focusRequester = focusRequester) {
            Box(Modifier.fillMaxSize().background(Tv.panel)) {
                AsyncImage(thumbnail, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                if (duration.isNotBlank()) {
                    Text(
                        duration, style = Tv.text(8f, FontWeight.SemiBold),
                        modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).clip(RoundedCornerShape(3.dp))
                            .background(Color(0xBF000000)).padding(horizontal = 4.dp, vertical = 1.5.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(7.dp))
        Text(title, style = Tv.text(10.5f, FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(channel, style = Tv.text(8.5f, color = Tv.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A round icon button (search, settings). */
@Composable
fun RoundButton(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier.size(30.dp).clip(RoundedCornerShape(15.dp))
            .background(if (focused) Tv.ink else Color(0x14FFFFFF))
            .tvItem(onClick = onClick, onFocusChange = { focused = it }),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, Modifier.size(13.dp), tint = if (focused) Tv.bg else Tv.ink2)
    }
}

/** A pill button: white when focused, the main one filled. */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    primary: Boolean = false,
    focusRequester: FocusRequester? = null,
    height: Dp = 36.dp,
    textSize: Float = 12f,
) {
    var focused by remember { mutableStateOf(false) }
    val bg = when {
        focused -> Tv.ink
        primary -> Color(0x337CC4FF)
        else -> Color(0x1AFFFFFF)
    }
    val fg = if (focused) Tv.bg else if (primary) Tv.accent else Tv.ink
    androidx.compose.foundation.layout.Row(
        modifier.height(height).clip(RoundedCornerShape(height / 2)).background(bg)
            .tvItem(onClick = onClick, focusRequester = focusRequester, onFocusChange = { focused = it })
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        if (icon != null) Icon(icon, null, Modifier.size(13.dp), tint = fg)
        Text(text, style = Tv.text(textSize, FontWeight.SemiBold, fg))
    }
}
