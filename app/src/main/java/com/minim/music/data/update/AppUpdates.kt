package com.minim.music.data.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File

/** v4.9.6: where an update the user said yes to has got to. */
sealed class UpdateFlowState {
    object Idle : UpdateFlowState()
    /** [total] is -1 when the size isn't known. */
    data class Downloading(val update: AvailableUpdate, val done: Long, val total: Long) : UpdateFlowState()
    /** Downloaded and checked: ready for Android's installer. */
    data class Ready(val update: AvailableUpdate, val file: File) : UpdateFlowState()
    data class Failed(val update: AvailableUpdate, val problem: Problem) : UpdateFlowState()

    enum class Problem { OFFLINE, SERVER, STORAGE, BROKEN, OTHER_APP, OLDER }
}

/**
 * v4.9.6: the in-app updater (checks GitHub Releases, downloads the APK, hands it to Android's
 * package installer). One per app, so a download keeps going when the pop-up is swiped away.
 *
 * Android 9-15 (minSdk 28): installing needs the "Install unknown apps" permission for Minim
 * (REQUEST_INSTALL_PACKAGES in the manifest + the user turning it on, checked with
 * canRequestPackageInstalls); the file is shared with the installer through the FileProvider
 * (cache/updates/), with a read grant, via ACTION_VIEW, which every version from 9 to 15 accepts.
 * On some phones turning the permission on restarts the app; the APK stays saved, so the next
 * open offers "Install now" straight away.
 */
class AppUpdates(private val context: Context, client: OkHttpClient) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val checker by lazy { UpdateChecker(baseClient, userAgent = userAgent()) }
    private val baseClient = client
    private var downloader: ApkDownloader? = null
    private var downloadJob: Job? = null

    private val _state = MutableStateFlow<UpdateFlowState>(UpdateFlowState.Idle)
    val state: StateFlow<UpdateFlowState> = _state

    val dir: File get() = File(context.cacheDir, "updates")

    /** The installed versionName ("4.9.5-debug" for a debug build). */
    val installedVersionName: String? by lazy { packageInfo()?.versionName }
    private val installedVersionCode: Long by lazy {
        packageInfo()?.let { if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else @Suppress("DEPRECATION") it.versionCode.toLong() } ?: 0L
    }

    /** "4.9.5": the version as the Settings row shows it. */
    val installedVersionLabel: String get() = installedVersionName?.substringBefore('-').orEmpty()

    /** One quiet check of the latest release. Never throws. */
    suspend fun check(): UpdateCheckResult = withContext(Dispatchers.IO) {
        try {
            checker.check(installedVersionName)
        } catch (e: Throwable) {
            UpdateCheckResult.Failed(e.javaClass.simpleName)
        }
    }

    /** True while a download runs or a downloaded file waits for the installer. */
    val isBusy: Boolean get() = _state.value is UpdateFlowState.Downloading

    /** A finished, checked download of [update] already on disk, if there is one. */
    fun savedApk(update: AvailableUpdate): File? {
        val f = File(dir, UpdateRules.apkFileName(update.version))
        if (!f.isFile || f.length() <= 0L) return null
        if (update.apk.size > 0 && f.length() != update.apk.size) return null
        return if (checkArchive(f) == UpdateRules.ArchiveCheck.OK) f else null
    }

    /** Starts downloading [update] (or reuses a saved copy). Progress and the end come through [state]. */
    fun startDownload(update: AvailableUpdate) {
        val now = _state.value
        if (now is UpdateFlowState.Downloading && now.update.version == update.version) return
        cancelDownload()
        _state.value = UpdateFlowState.Downloading(update, 0L, update.apk.size)
        val d = ApkDownloader(baseClient)
        downloader = d
        downloadJob = scope.launch {
            try {
                savedApk(update)?.let {
                    _state.value = UpdateFlowState.Ready(update, it)
                    return@launch
                }
                val result = d.download(
                    url = update.apk.url,
                    dir = dir,
                    fileName = UpdateRules.apkFileName(update.version),
                    expectedSize = update.apk.size,
                    userAgent = userAgent()
                ) { done, total ->
                    if (downloader === d) _state.value = UpdateFlowState.Downloading(update, done, total)
                }
                if (downloader !== d) return@launch
                _state.value = when (result) {
                    is DownloadResult.Done -> when (checkArchive(result.file)) {
                        UpdateRules.ArchiveCheck.OK -> UpdateFlowState.Ready(update, result.file)
                        UpdateRules.ArchiveCheck.OTHER_APP -> fail(update, result.file, UpdateFlowState.Problem.OTHER_APP)
                        UpdateRules.ArchiveCheck.OLDER -> fail(update, result.file, UpdateFlowState.Problem.OLDER)
                        UpdateRules.ArchiveCheck.BROKEN -> fail(update, result.file, UpdateFlowState.Problem.BROKEN)
                    }
                    is DownloadResult.Cancelled -> UpdateFlowState.Idle
                    is DownloadResult.Failed -> UpdateFlowState.Failed(
                        update,
                        when (result.kind) {
                            DownloadResult.Kind.OFFLINE -> UpdateFlowState.Problem.OFFLINE
                            DownloadResult.Kind.SERVER -> UpdateFlowState.Problem.SERVER
                            DownloadResult.Kind.STORAGE -> UpdateFlowState.Problem.STORAGE
                            DownloadResult.Kind.BROKEN -> UpdateFlowState.Problem.BROKEN
                        }
                    )
                }
            } catch (e: Throwable) {
                if (downloader === d) _state.value = UpdateFlowState.Failed(update, UpdateFlowState.Problem.BROKEN)
            } finally {
                if (downloader === d) downloader = null
            }
        }
    }

    private fun fail(update: AvailableUpdate, file: File, problem: UpdateFlowState.Problem): UpdateFlowState {
        file.delete()
        return UpdateFlowState.Failed(update, problem)
    }

    fun cancelDownload() {
        downloader?.cancel()
        downloader = null
        downloadJob?.cancel()
        downloadJob = null
        if (_state.value is UpdateFlowState.Downloading) _state.value = UpdateFlowState.Idle
    }

    /** Back to idle after a failure or once the installer has been opened. */
    fun reset() {
        if (_state.value !is UpdateFlowState.Downloading) _state.value = UpdateFlowState.Idle
    }

    /** Whether Android lets Minim open the installer ("Install unknown apps" for Minim). */
    fun canInstall(): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.packageManager.canRequestPackageInstalls() else true
    } catch (e: Exception) {
        false
    }

    /** The system page where the user turns on "Allow from this source" for Minim. */
    fun unknownSourcesIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    /** Fallback when a phone has no per-app page: the general security settings. */
    fun securitySettingsIntent(): Intent = Intent(Settings.ACTION_SECURITY_SETTINGS)

    /** Android's package installer for [file], or null when the file can't be shared. */
    fun installIntent(file: File): Intent? = try {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    } catch (e: Exception) {
        null
    }

    /** Deletes half-finished and already-installed downloads. Safe to call on every start. */
    fun cleanUp() {
        scope.launch {
            try {
                val installed = AppVersion.installed(installedVersionName)
                dir.listFiles()?.forEach { f ->
                    if (_state.value is UpdateFlowState.Downloading) return@forEach
                    if (UpdateRules.isStaleDownload(f.name, installed)) f.delete()
                }
            } catch (e: Exception) {
                // Only tidying
            }
        }
    }

    private fun checkArchive(file: File): UpdateRules.ArchiveCheck {
        val info: PackageInfo? = try {
            val pm = context.packageManager
            if (Build.VERSION.SDK_INT >= 33) {
                pm.getPackageArchiveInfo(file.absolutePath, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageArchiveInfo(file.absolutePath, 0)
            }
        } catch (e: Exception) {
            null
        }
        val code = info?.let { if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else @Suppress("DEPRECATION") it.versionCode.toLong() } ?: 0L
        return UpdateRules.checkArchive(info?.packageName, code, context.packageName, installedVersionCode)
    }

    private fun packageInfo(): PackageInfo? = try {
        if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }
    } catch (e: Exception) {
        null
    }

    private fun userAgent(): String = "Minim/" + (try { installedVersionLabel } catch (e: Exception) { "" }) +
        " (Android ${Build.VERSION.RELEASE}; +https://github.com/${UpdateChecker.REPO})"

    companion object {
        const val APK_MIME = "application/vnd.android.package-archive"

        /** Starts [intent] if some app can take it. */
        fun tryStart(context: Context, intent: Intent): Boolean = try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        } catch (e: SecurityException) {
            false
        }
    }
}
