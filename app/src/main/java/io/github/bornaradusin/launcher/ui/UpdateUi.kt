package io.github.bornaradusin.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.bornaradusin.launcher.update.UpdateState
import kotlinx.coroutines.delay

/** "Launcher 1.3.0 is ready": the note on Home when GitHub has a new version. */
@Composable
fun UpdateNote(version: String, installed: String, notes: String, onUpdate: () -> Unit, onLater: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(30)
        runCatching { focus.requestFocus() }
    }
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.align(Alignment.TopEnd).padding(top = 64.dp, end = 48.dp).width(280.dp)
                .shadow(40.dp, RoundedCornerShape(13.dp))
                .clip(RoundedCornerShape(13.dp))
                .background(Color(0xF0111621))
                .border(1.dp, Tv.line, RoundedCornerShape(13.dp))
                .padding(horizontal = 14.dp, vertical = 13.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Box(Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).background(Tv.accent), contentAlignment = Alignment.Center) {
                    Icon(Ic.download, null, Modifier.size(15.dp), tint = Tv.bg)
                }
                Column {
                    Text("Launcher $version is ready", style = Tv.text(13f, FontWeight.Bold))
                    Text("From GitHub · you have $installed", style = Tv.text(9f, color = Tv.muted))
                }
            }
            val summary = notes.lines().map { it.trim().removePrefix("•").trim() }.filter { it.isNotBlank() }.take(3).joinToString("  ·  ")
            if (summary.isNotBlank()) Text(summary, style = Tv.text(10f, color = Tv.ink2, lineHeight = 15f), maxLines = 3, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                PillButton("Update now", onUpdate, primary = true, focusRequester = focus, height = 28.dp, textSize = 10.5f)
                PillButton("Later", onLater, height = 28.dp, textSize = 10.5f)
            }
            Text("Later keeps Update in the top bar until you do it", style = Tv.text(8f, color = Tv.muted))
        }
    }
}

/** Updating: the download, then Android's prompt to install, then the download is deleted. */
@Composable
fun UpdateDialog(state: UpdateState, onCancel: () -> Unit, onRetry: () -> Unit, onAllow: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(state is UpdateState.Failed, state is UpdateState.NeedsPermission) {
        delay(30)
        runCatching { focus.requestFocus() }
    }
    val update = when (state) {
        is UpdateState.Downloading -> state.update
        is UpdateState.NeedsPermission -> state.update
        is UpdateState.Failed -> state.update
        is UpdateState.Available -> state.update
        else -> null
    }
    val progress = (state as? UpdateState.Downloading)?.progress ?: if (state is UpdateState.NeedsPermission) 1f else 0f
    Box(Modifier.fillMaxSize().background(Color(0xC704060A))) {
        Column(
            Modifier.align(Alignment.Center).width(700.dp)
                .shadow(60.dp, RoundedCornerShape(16.dp))
                .clip(RoundedCornerShape(16.dp))
                .background(Tv.panel)
                .border(1.dp, Tv.line, RoundedCornerShape(16.dp))
                .padding(horizontal = 24.dp, vertical = 22.dp),
            verticalArrangement = Arrangement.spacedBy(15.dp),
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("Updating to ${update?.version ?: "the new version"}", style = Tv.text(22f, FontWeight.Bold), modifier = Modifier.weight(1f))
                Text("github.com/bornaradusin/ShieldLauncher", style = Tv.text(10f, color = Tv.muted))
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val size = update?.apkSize ?: 0L
                val label = when (state) {
                    is UpdateState.Downloading -> if (size > 0) "Downloading · ${mb(size * progress)} of ${mb(size.toFloat())} MB" else "Downloading"
                    is UpdateState.NeedsPermission -> "Downloaded"
                    is UpdateState.Failed -> state.message
                    UpdateState.Checking -> "Looking on GitHub"
                    else -> "Starting"
                }
                Row {
                    Text(label, style = Tv.text(10.5f, color = if (state is UpdateState.Failed) Tv.bad else Tv.ink2), modifier = Modifier.weight(1f), maxLines = 2)
                    Text("${(progress * 100).toInt()}%", style = Tv.text(10.5f, color = Tv.ink2))
                }
                Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(Color(0x1FFFFFFF))) {
                    Box(Modifier.fillMaxWidth(progress).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(Tv.accent))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                val downloading = state is UpdateState.Downloading || state is UpdateState.Checking
                val installing = state is UpdateState.NeedsPermission
                Step(Ic.download, "Download", "From the newest GitHub release. If it stops, it carries on from where it was.",
                    if (installing) StepState.DONE else if (downloading) StepState.NOW else StepState.NEXT)
                Step(Ic.box, "Install", if (installing) "First, allow the launcher to install apps. Then Android asks once: choose Update."
                else "Android asks once: choose Update. The launcher closes and opens again.", if (installing) StepState.NOW else StepState.NEXT)
                Step(Ic.trash, "Clean up", "The downloaded file is deleted, and Home says “Updated to ${update?.version ?: "…"}”.", StepState.NEXT)
            }
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    val notes = update?.notes.orEmpty().lines().map { it.trim() }.filter { it.isNotBlank() }.take(4)
                    if (notes.isNotEmpty()) {
                        Text("WHAT’S NEW", style = Tv.text(8f, FontWeight.Bold, Tv.muted, spacing = 1f))
                        notes.forEach { Text(if (it.startsWith("•")) it else "· $it", style = Tv.text(9.5f, color = Tv.ink2), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                }
                when (state) {
                    is UpdateState.NeedsPermission -> PillButton("Allow installing", onAllow, primary = true, focusRequester = focus)
                    is UpdateState.Failed -> PillButton("Try again", onRetry, primary = true, focusRequester = focus)
                    else -> Unit
                }
                PillButton("Cancel", onCancel, focusRequester = if (state is UpdateState.Downloading || state is UpdateState.Checking) focus else null)
            }
        }
    }
}

private enum class StepState { DONE, NOW, NEXT }

@Composable
private fun RowScope.Step(icon: ImageVector, title: String, text: String, state: StepState) {
    val border = when (state) {
        StepState.DONE -> Color(0x4D6EDC96)
        StepState.NOW -> Tv.accent
        StepState.NEXT -> Tv.line
    }
    val bg = when (state) {
        StepState.DONE -> Color(0x146EDC96)
        StepState.NOW -> Color(0x1A7CC4FF)
        StepState.NEXT -> Color(0x08FFFFFF)
    }
    Column(
        Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(bg).border(if (state == StepState.NOW) 1.5.dp else 1.dp, border, RoundedCornerShape(10.dp)).padding(11.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier.size(24.dp).clip(RoundedCornerShape(12.dp)).background(
                when (state) {
                    StepState.DONE -> Tv.ok
                    StepState.NOW -> Tv.accent
                    StepState.NEXT -> Tv.off
                },
            ),
            contentAlignment = Alignment.Center,
        ) { Icon(if (state == StepState.DONE) Ic.check else icon, null, Modifier.size(12.dp), tint = if (state == StepState.NEXT) Tv.muted else Tv.bg) }
        Text(title, style = Tv.text(11.5f, FontWeight.Bold, if (state == StepState.NEXT) Tv.ink2 else Tv.ink))
        Text(text, style = Tv.text(9f, color = Tv.muted, lineHeight = 13f))
        Spacer(Modifier.height(0.dp))
    }
}

private fun mb(bytes: Float) = String.format(java.util.Locale.US, "%.0f", bytes / 1_000_000f)
