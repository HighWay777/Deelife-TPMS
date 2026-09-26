package com.example.tpms.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageInstaller
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.example.tpms.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

sealed class UpdateState {
    object Idle : UpdateState()
    object Checking : UpdateState()
    object UpToDate : UpdateState()
    data class Available(val info: UpdateInfo) : UpdateState()
    /** [progress] is 0..1, or -1 when the size is unknown. */
    data class Downloading(val info: UpdateInfo, val progress: Float) : UpdateState()
    data class NeedsInstallPermission(val info: UpdateInfo) : UpdateState()
    data class Installing(val info: UpdateInfo) : UpdateState()
    data class Failed(val message: String, val info: UpdateInfo?) : UpdateState()

    val updateInfo: UpdateInfo?
        get() = when (this) {
            is Available -> info
            is Downloading -> info
            is NeedsInstallPermission -> info
            is Installing -> info
            is Failed -> info
            else -> null
        }
}

/**
 * Checks the public Gitea repository for a newer release, downloads the APK over the
 * head unit's own network connection and hands it to Android's PackageInstaller.
 * Android always asks the user to confirm the install; nothing is installed silently.
 */
object UpdateManager {
    private const val TAG = "UpdateManager"
    private const val PREFS = "update_prefs"
    private const val KEY_AUTO_CHECK = "auto_check"
    private const val KEY_LAST_CHECK = "last_check_ms"
    private const val KEY_SKIPPED = "skipped_version"
    private const val AUTO_CHECK_INTERVAL_MS = 6 * 60 * 60_000L
    private const val MANIFEST_ASSET = "update.json"
    private const val UPDATE_DIR = "updates"
    private val USER_AGENT = "Deelife-TPMS/${BuildConfig.VERSION_NAME} (Android ${Build.VERSION.RELEASE})"

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var prefs: SharedPreferences? = null
    private var job: Job? = null

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state = _state.asStateFlow()

    private val _dialogVisible = MutableStateFlow(false)
    val dialogVisible = _dialogVisible.asStateFlow()

    private val _autoCheck = MutableStateFlow(true)
    val autoCheck = _autoCheck.asStateFlow()

    val installedVersionName: String get() = BuildConfig.VERSION_NAME
    val installedVersionCode: Int get() = BuildConfig.VERSION_CODE

    fun init(context: Context) {
        if (prefs != null) return
        val app = context.applicationContext
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _autoCheck.value = prefs!!.getBoolean(KEY_AUTO_CHECK, true)
        scope.launch(Dispatchers.IO) { cleanUpOldDownloads(app) }
    }

    fun setAutoCheck(enabled: Boolean) {
        _autoCheck.value = enabled
        prefs?.edit()?.putBoolean(KEY_AUTO_CHECK, enabled)?.apply()
    }

    fun showDialog() {
        if (_state.value.updateInfo != null) _dialogVisible.value = true
    }

    /** "Later": hide the dialog, keep the pending update (and any running download). */
    fun dismissDialog() {
        _dialogVisible.value = false
    }

    fun skipVersion() {
        _state.value.updateInfo?.let { prefs?.edit()?.putString(KEY_SKIPPED, it.versionName)?.apply() }
        _dialogVisible.value = false
    }

    /** Throttled, silent check; safe to call often (on resume, from a timer). */
    fun maybeAutoCheck(context: Context) {
        init(context)
        if (!_autoCheck.value || job?.isActive == true) return
        val s = _state.value
        if (s !is UpdateState.Idle && s !is UpdateState.UpToDate && s !is UpdateState.Failed) return
        val elapsed = System.currentTimeMillis() - (prefs?.getLong(KEY_LAST_CHECK, 0L) ?: 0L)
        if (elapsed in 0 until AUTO_CHECK_INTERVAL_MS) return
        check(context.applicationContext, userInitiated = false)
    }

    fun checkNow(context: Context) {
        init(context)
        check(context.applicationContext, userInitiated = true)
    }

    /** Called from MainActivity.onResume. */
    fun onResume(context: Context) {
        maybeAutoCheck(context)
        // Returning from the "Install unknown apps" screen: continue automatically once granted.
        val s = _state.value
        if (s is UpdateState.NeedsInstallPermission && canInstallPackages(context)) {
            startUpdate(context)
        }
    }

    private fun check(context: Context, userInitiated: Boolean) {
        if (job?.isActive == true) return
        if (!isOnline(context)) {
            // Auto-check: stay quiet and retry later (the head unit may not be online yet).
            if (userInitiated) _state.value = UpdateState.Failed("No internet connection. Connect the head unit to Wi-Fi or a hotspot.", null)
            return
        }
        _state.value = UpdateState.Checking
        job = scope.launch {
            try {
                val info = withContext(Dispatchers.IO) { fetchLatest() }
                prefs?.edit()?.putLong(KEY_LAST_CHECK, System.currentTimeMillis())?.apply()
                if (info == null) {
                    _state.value = UpdateState.UpToDate
                    return@launch
                }
                val skipped = prefs?.getString(KEY_SKIPPED, null) == info.versionName
                _state.value = UpdateState.Available(info)
                _dialogVisible.value = userInitiated || !skipped
                Log.i(TAG, "Update available: ${info.versionName} (skipped=$skipped)")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Update check failed: ${e.message}")
                _state.value = if (userInitiated) UpdateState.Failed("Update check failed: ${e.message}", null) else UpdateState.Idle
            }
        }
    }

    // -----------------------------------------------------------------------
    // Download + install
    // -----------------------------------------------------------------------

    /**
     * Downloads and installs the pending update. When [ignorePermission] is true the
     * "Install unknown apps" pre-check is skipped and the system installer asks instead
     * (used on head units whose settings app hides that screen).
     */
    fun startUpdate(context: Context, ignorePermission: Boolean = false) {
        val info = _state.value.updateInfo ?: return
        if (job?.isActive == true) return
        val app = context.applicationContext
        _dialogVisible.value = true
        if (!ignorePermission && !canInstallPackages(app)) {
            _state.value = UpdateState.NeedsInstallPermission(info)
            return
        }
        job = scope.launch {
            try {
                _state.value = UpdateState.Downloading(info, if (info.apkSize > 0) 0f else -1f)
                val apk = withContext(Dispatchers.IO) {
                    download(app, info) { p -> _state.value = UpdateState.Downloading(info, p) }
                }
                _state.value = UpdateState.Installing(info)
                withContext(Dispatchers.IO) {
                    verifyArchive(app, apk)
                    install(app, apk)
                }
                // InstallResultReceiver takes it from here.
            } catch (e: CancellationException) {
                _state.value = UpdateState.Available(info)
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Update failed", e)
                _state.value = UpdateState.Failed(e.message ?: "Update failed", info)
            }
        }
    }

    fun cancelDownload() {
        job?.cancel()
    }

    /** Opens Android's "Install unknown apps" screen for this app (Android 8+). */
    fun openInstallPermissionSettings(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            startUpdate(context, ignorePermission = true)
            return
        }
        try {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            Log.w(TAG, "Unknown-sources settings screen not available: ${e.message}")
            startUpdate(context, ignorePermission = true)
        }
    }

    fun openReleasePage(context: Context) {
        val url = _state.value.updateInfo?.pageUrl ?: return
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Log.w(TAG, "No browser available: ${e.message}")
        }
    }

    /** Result of the PackageInstaller session, delivered by [InstallResultReceiver]. */
    fun onInstallResult(status: Int, message: String?) {
        val info = _state.value.updateInfo
        when (status) {
            PackageInstaller.STATUS_SUCCESS -> {
                _state.value = UpdateState.Idle
                _dialogVisible.value = false
            }
            PackageInstaller.STATUS_FAILURE_ABORTED ->
                _state.value = UpdateState.Failed("Installation was cancelled.", info)
            PackageInstaller.STATUS_FAILURE_CONFLICT ->
                _state.value = UpdateState.Failed(
                    "The installed app is signed with a different key. Uninstall it once, then install the new version from the release page.", info)
            PackageInstaller.STATUS_FAILURE_STORAGE ->
                _state.value = UpdateState.Failed("Not enough storage space to install the update.", info)
            else ->
                _state.value = UpdateState.Failed("Installation failed: ${message ?: "error $status"}", info)
        }
    }

    // -----------------------------------------------------------------------
    // Internals
    // -----------------------------------------------------------------------

    private fun fetchLatest(): UpdateInfo? {
        val conn = open(BuildConfig.UPDATE_API_URL)
        val body = try {
            when (val code = conn.responseCode) {
                200 -> conn.inputStream.bufferedReader().use { it.readText() }
                404 -> return null // no published release yet
                else -> throw IOException("server returned HTTP $code")
            }
        } finally {
            conn.disconnect()
        }
        val release = ReleaseParser.parseRelease(body)
        val manifest = release.assets.firstOrNull { it.name == MANIFEST_ASSET }?.let {
            try { ReleaseParser.parseManifest(httpGetText(it.url)) }
            catch (e: Exception) { Log.w(TAG, "Ignoring unreadable $MANIFEST_ASSET: ${e.message}"); null }
        }
        val info = ReleaseParser.buildUpdateInfo(release, manifest) ?: return null
        if (info.minSdk > Build.VERSION.SDK_INT) return null
        return info.takeIf { ReleaseParser.isNewer(it, installedVersionCode, installedVersionName) }
    }

    private fun httpGetText(url: String): String {
        val conn = open(url)
        try {
            val code = conn.responseCode
            if (code != 200) throw IOException("HTTP $code for $url")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
        }

    private fun updateDir(context: Context) = File(context.cacheDir, UPDATE_DIR)

    private suspend fun download(context: Context, info: UpdateInfo, onProgress: (Float) -> Unit): File {
        val dir = updateDir(context).apply { mkdirs() }
        val target = File(dir, "Deelife-TPMS-${info.versionName}.apk")
        if (target.exists()) {
            if (matches(target, info)) return target
            target.delete()
        }
        val part = File(dir, target.name + ".part")
        var offset = if (part.exists()) part.length() else 0L
        if (info.apkSize in 1 until offset) { part.delete(); offset = 0L }

        if (info.apkSize <= 0 || offset < info.apkSize) {
            val conn = open(info.apkUrl)
            if (offset > 0) conn.setRequestProperty("Range", "bytes=$offset-")
            try {
                val append = when (val code = conn.responseCode) {
                    206 -> true
                    200 -> { offset = 0L; false }
                    416 -> { part.delete(); throw IOException("Download could not be resumed, please try again.") }
                    else -> throw IOException("Download failed: HTTP $code")
                }
                val length = conn.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
                val total = when {
                    length > 0 -> offset + length
                    info.apkSize > 0 -> info.apkSize
                    else -> -1L
                }
                conn.inputStream.use { input ->
                    FileOutputStream(part, append).use { output ->
                        val buf = ByteArray(64 * 1024)
                        var done = offset
                        var lastPercent = -1
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val percent = (done * 100 / total).toInt()
                                if (percent != lastPercent) {
                                    lastPercent = percent
                                    onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                                }
                            }
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }
        }

        if (!matches(part, info)) {
            part.delete()
            throw IOException("Downloaded file is damaged (size/checksum mismatch). Please try again.")
        }
        if (!part.renameTo(target)) throw IOException("Could not save the downloaded update.")
        return target
    }

    private fun matches(file: File, info: UpdateInfo): Boolean {
        if (info.apkSize > 0 && file.length() != info.apkSize) return false
        val expected = info.sha256 ?: return true
        return sha256(file).equals(expected, ignoreCase = true)
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Refuse anything that is not a newer build of this very app before bothering the installer. */
    private fun verifyArchive(context: Context, apk: File) {
        @Suppress("DEPRECATION")
        val pkg = context.packageManager.getPackageArchiveInfo(apk.path, 0)
            ?: throw IOException("Downloaded file is not a valid APK.")
        if (pkg.packageName != context.packageName) {
            throw IOException("Downloaded APK belongs to another app (${pkg.packageName}).")
        }
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pkg.longVersionCode
                   else @Suppress("DEPRECATION") pkg.versionCode.toLong()
        if (code <= installedVersionCode) {
            throw IOException("Downloaded APK (build $code) is not newer than the installed build $installedVersionCode.")
        }
    }

    private fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
        }
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                session.openWrite("base.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out, 64 * 1024) }
                    session.fsync(out)
                }
                val intent = Intent(context, InstallResultReceiver::class.java)
                    .setAction(InstallResultReceiver.ACTION_INSTALL_STATUS)
                // The installer fills in status extras, so this PendingIntent must be mutable (explicit intent).
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
                val pending = PendingIntent.getBroadcast(context, sessionId, intent, flags)
                session.commit(pending.intentSender)
            }
        } catch (e: Exception) {
            try { installer.abandonSession(sessionId) } catch (_: Exception) {}
            throw e
        }
    }

    private fun canInstallPackages(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    @Suppress("DEPRECATION")
    private fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        return cm.activeNetworkInfo?.isConnected == true
    }

    /** Remove downloads of versions that are already installed (or older). */
    private fun cleanUpOldDownloads(context: Context) {
        val files = updateDir(context).listFiles() ?: return
        for (f in files) {
            val version = f.name.removePrefix("Deelife-TPMS-").removeSuffix(".part").removeSuffix(".apk")
            if (ReleaseParser.compareVersions(version, installedVersionName) <= 0) f.delete()
        }
    }
}
