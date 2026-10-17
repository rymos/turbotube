package io.github.rymos.turbotube.update

import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.rymos.turbotube.BuildConfig
import io.github.rymos.turbotube.TurbotubeApp
import io.github.rymos.turbotube.core.update.AppUpdate
import io.github.rymos.turbotube.core.update.UpdateChecker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val update: AppUpdate) : UpdateState
    data class Downloading(val update: AppUpdate, val progress: Float) : UpdateState
    data object Installing : UpdateState
    data object NeedsPermission : UpdateState
    data class Failed(val message: String) : UpdateState
}

/**
 * Updates the app from GitHub releases without a browser: download the APK straight into a
 * PackageInstaller session and let Android show its usual "Install update?" dialog.
 */
class Updater(private val context: Context, private val http: OkHttpClient) {
    var state by mutableStateOf<UpdateState>(UpdateState.Idle)
        private set

    /** The newest update seen so far, so it can still be installed after a detour or an error. */
    var lastFound by mutableStateOf<AppUpdate?>(null)
        private set

    val currentBuild: Int get() = BuildConfig.BUILD_NUMBER

    suspend fun check(quiet: Boolean = false) {
        if (state is UpdateState.Downloading || state is UpdateState.Installing) return
        if (!quiet) state = UpdateState.Checking
        state = try {
            val update = UpdateChecker.check(http, currentBuild)
            lastFound = update
            update?.let { UpdateState.Available(it) } ?: UpdateState.UpToDate
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (quiet) UpdateState.Idle else UpdateState.Failed("Prüfen fehlgeschlagen: ${e.message}")
        }
    }

    suspend fun install(update: AppUpdate) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            state = UpdateState.NeedsPermission
            openInstallPermission()
            return
        }
        state = UpdateState.Downloading(update, 0f)
        try {
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                .apply { setAppPackageName(context.packageName) }
            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                download(update, session)
                state = UpdateState.Installing
                val intent = Intent(context, InstallResultReceiver::class.java)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
                val pending = PendingIntent.getBroadcast(context, sessionId, intent, flags)
                session.commit(pending.intentSender)
            }
        } catch (e: CancellationException) {
            state = UpdateState.Available(update)
            throw e
        } catch (e: Exception) {
            state = UpdateState.Failed("Update fehlgeschlagen: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private suspend fun download(update: AppUpdate, session: PackageInstaller.Session) =
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(update.apkUrl).build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Download: HTTP ${response.code}")
                val body = response.body
                val total = body.contentLength().takeIf { it > 0 } ?: update.sizeBytes
                session.openWrite("turbotube.apk", 0, total).use { out ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        var done = 0L
                        var lastShown = -1
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            out.write(buffer, 0, read)
                            done += read
                            if (total > 0) {
                                val percent = (done * 100 / total).toInt()
                                if (percent != lastShown) {
                                    lastShown = percent
                                    state = UpdateState.Downloading(update, percent / 100f)
                                }
                            }
                        }
                    }
                    session.fsync(out)
                }
            }
        }

    private fun openInstallPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            // Some TVs have no such screen; the panel then explains the way through the settings
        }
    }

    internal fun onInstallResult(status: Int, message: String?) {
        state = when (status) {
            // The installed build is the one we were offering; forget it or the menu keeps
            // offering the same update.
            PackageInstaller.STATUS_SUCCESS -> {
                lastFound = null
                UpdateState.UpToDate
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> UpdateState.Idle
            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                UpdateState.Failed(
                    "Das Update ist anders signiert als die installierte Version. Deinstalliere " +
                        "Turbotube einmal und installiere es neu, danach klappen Updates hier.",
                )
            else -> UpdateState.Failed("Installation fehlgeschlagen: ${message ?: "Fehler $status"}")
        }
    }
}

/** Gets the answer of the package installer; asks the user to confirm when Android wants that. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
            context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        val app = context.applicationContext as? TurbotubeApp ?: return
        app.container.updater.onInstallResult(
            status,
            intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE),
        )
    }
}
