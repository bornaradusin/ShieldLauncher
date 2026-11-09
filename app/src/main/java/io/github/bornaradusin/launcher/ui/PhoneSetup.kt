package io.github.bornaradusin.launcher.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import io.github.bornaradusin.launcher.LauncherApp
import io.github.bornaradusin.launcher.server.ListenService
import io.github.bornaradusin.launcher.server.LocalServer
import io.github.bornaradusin.launcher.server.homeIp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import kotlinx.coroutines.delay

/**
 * "Scan to set up": a code for the phone's camera that opens the launcher's page (on the home
 * Wi-Fi), with the address and a 4-number code to type instead. A new code each time this opens.
 */
@Composable
fun PhoneSetupScreen(app: LauncherApp) {
    val code by app.phoneLink.code.collectAsState()
    val phone by app.phoneLink.phone.collectAsState()
    val ports by ListenService.ports.collectAsState()
    val visits by LocalServer.visits.collectAsState()
    DisposableEffect(Unit) {
        app.phoneLink.refreshCode()
        onDispose { }
    }
    val ip = remember { homeIp(app) }
    val address = ip?.let { a -> ports.phone?.let { "$a:$it" } }
    // The page checked from the Shield itself, so a problem on the TV shows here rather than as a blank phone
    var selfTest by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(ports.phone) {
        val port = ports.phone ?: return@LaunchedEffect
        selfTest = withContext(Dispatchers.IO) {
            runCatching {
                app.http.newCall(Request.Builder().url("http://127.0.0.1:$port/").build()).execute().use { it.isSuccessful && (it.body?.string()?.contains("Shield Launcher") == true) }
            }.getOrDefault(false)
        }
    }
    val qr = remember(address, code) { address?.let { qrBitmap("http://$it/?c=$code", 580) } }
    // Something to hold focus, so Back works and nothing behind takes it
    val holder = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(60)
        runCatching { holder.requestFocus() }
    }

    Box(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF0B101B), Tv.bg)))
            .background(Brush.radialGradient(listOf(Color(0x337CC4FF), Color.Transparent), center = androidx.compose.ui.geometry.Offset(1500f, 320f), radius = 900f))
            .focusRequester(holder)
            .focusable(),
    ) {
        Column(Modifier.padding(start = 48.dp, top = 48.dp).width(330.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("SET UP FROM YOUR PHONE", style = Tv.kicker)
            Text("Scan to set up", style = Tv.text(32f, FontWeight.Bold, lineHeight = 35f))
            Text(
                "Arrange the rows and apps, and sign in to Emby and Seerr with your phone’s keyboard. The TV changes as you go.",
                style = Tv.text(12f, color = Tv.muted, lineHeight = 17f),
            )
        }
        Row(Modifier.padding(start = 48.dp, top = 210.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Box(Modifier.size(170.dp).clip(RoundedCornerShape(14.dp)).background(Color.White), contentAlignment = Alignment.Center) {
                if (qr != null) Image(qr.asImageBitmap(), "QR code to set up from your phone", Modifier.size(145.dp), filterQuality = FilterQuality.None)
                else Text("Not on a network", style = Tv.text(11f, color = Tv.bg))
            }
            Column(Modifier.width(170.dp).padding(top = 5.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                Text("1  Point your phone’s camera at the code", style = Tv.text(11f, color = Tv.ink2, lineHeight = 15f))
                Text("2  Phone and Shield on the same Wi-Fi", style = Tv.text(11f, color = Tv.ink2, lineHeight = 15f))
                Text("Or open http://${address ?: "the Shield's address"} and type $code", style = Tv.text(11f, color = Tv.muted, lineHeight = 15f))
            }
        }
        Column(Modifier.padding(start = 48.dp, top = 400.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (phone != null) {
                Row(
                    Modifier.clip(RoundedCornerShape(15.dp)).background(Color(0x1F6EDC96)).padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Box(Modifier.size(6.dp).clip(RoundedCornerShape(3.dp)).background(Tv.ok))
                    Text("$phone is connected", style = Tv.text(11f, color = Tv.okText))
                }
            }
            when {
                ports.phone == null -> Text("The launcher couldn't open a port for the phone page: restart the Shield and try again.", style = Tv.text(10f, color = Tv.bad))
                selfTest == false -> Text("The phone page doesn't answer on the Shield itself (port ${ports.phone}): restart the Shield.", style = Tv.text(10f, color = Tv.bad))
                selfTest == true -> Text(
                    "✓ The page is up on port ${ports.phone}" + if (visits > 0) " · $visits request${if (visits == 1) "" else "s"} from a phone" else " · nothing from a phone yet",
                    style = Tv.text(10f, color = Tv.okText),
                )
            }
            Text(
                "The code changes every time this opens, and only works on your home Wi-Fi. Back closes it.",
                style = Tv.text(9f, color = Tv.muted, lineHeight = 13f), modifier = Modifier.width(350.dp),
            )
        }
        // What the phone's page does
        Column(
            Modifier.align(Alignment.TopEnd).padding(top = 60.dp, end = 48.dp).width(400.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("ON THE PHONE", style = Tv.text(8.5f, FontWeight.Bold, Tv.muted, spacing = 1.2f))
            Feature(Ic.list, "Home", "Rows on or off (SmartTube's too), dragged into order; the apps row; trailers behind films")
            Feature(Ic.play, "Emby", "Server, user and password, and which Emby app plays")
            Feature(Ic.download, "Seerr", "Its server address and API key, for Requester")
            Feature(Ic.gamepad, "Games", "The games Artemis added, to take off Home")
            Feature(Ic.download, "Updates", "Look for a new version and update the TV")
        }
    }
}

@Composable
private fun Feature(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, text: String) {
    Row(
        Modifier.clip(RoundedCornerShape(10.dp)).background(Color(0x0DFFFFFF)).border(1.dp, Tv.line, RoundedCornerShape(10.dp)).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Icon(icon, null, Modifier.size(15.dp), tint = Tv.accent)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = Tv.text(12f, FontWeight.SemiBold))
            Text(text, style = Tv.text(9.5f, color = Tv.muted, lineHeight = 13f))
        }
    }
}

/** A QR code as a picture, black on white, with no border (the white tile around it is the border). */
fun qrBitmap(text: String, size: Int): Bitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M))
    val pixels = IntArray(matrix.width * matrix.height) { i ->
        if (matrix.get(i % matrix.width, i / matrix.width)) android.graphics.Color.rgb(6, 8, 12) else android.graphics.Color.WHITE
    }
    return Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
}
