package io.github.rymos.turbotube.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import io.github.rymos.turbotube.core.account.DeviceCode
import kotlinx.coroutines.CancellationException

@Composable
fun LoginScreen(onDone: () -> Unit) {
    val container = LocalContainer.current
    var code by remember { mutableStateOf<DeviceCode?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        while (true) {
            try {
                val next = container.auth.requestDeviceCode()
                code = next
                error = null
                if (container.auth.awaitLogin(next)) {
                    container.onLoginChanged()
                    onDone()
                    return@LaunchedEffect
                }
                // Code expired or was declined: show a fresh one
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
                kotlinx.coroutines.delay(10_000)
            }
        }
    }

    val current = code
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when {
            error != null -> Message("Anmeldung fehlgeschlagen", error.orEmpty())
            current == null -> Text("Lädt …")
            else -> Row(
                horizontalArrangement = Arrangement.spacedBy(48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Mit YouTube anmelden", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        "1. Öffne auf dem Handy oder Computer:",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        current.verificationUrl.removePrefix("https://"),
                        style = MaterialTheme.typography.headlineSmall,
                        color = Accent,
                    )
                    Text("2. Gib diesen Code ein:", style = MaterialTheme.typography.titleMedium)
                    Text(
                        current.userCode,
                        fontSize = 48.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                            .padding(horizontal = 24.dp, vertical = 8.dp),
                    )
                    Text(
                        "3. Wähle dein Google-Konto und bestätige. Der Fernseher meldet sich dann " +
                            "von selbst an.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val qr = remember(current.verificationUrl) { qrCode(current.verificationUrl) }
                Image(
                    bitmap = qr,
                    contentDescription = "QR-Code zur Anmeldeseite",
                    modifier = Modifier
                        .size(240.dp)
                        .background(Color.White, RoundedCornerShape(12.dp))
                        .padding(12.dp),
                )
            }
        }
    }
}

@Composable
fun Message(title: String, text: String) {
    Box(Modifier.fillMaxSize().padding(64.dp), contentAlignment = Alignment.Center) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.headlineMedium)
            Text(text, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

private fun qrCode(text: String, size: Int = 512): ImageBitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
    val pixels = IntArray(size * size) { i ->
        if (matrix[i % size, i / size]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
    }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888).asImageBitmap()
}
