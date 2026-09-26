package com.example.tpms.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.tpms.TpmsManager
import com.example.tpms.update.ReleaseParser
import com.example.tpms.update.UpdateInfo
import com.example.tpms.update.UpdateManager
import com.example.tpms.update.UpdateState

private val Surface1    = Color(0xFF161A24)
private val Surface2    = Color(0xFF1E2330)
private val Surface3    = Color(0xFF252A3A)
private val Accent      = Color(0xFF4A90FF)
private val AccentGreen = Color(0xFF00E676)
private val Muted       = Color(0xFF9999AA)
private val ErrorRed    = Color(0xFFFF5555)

private fun sizeText(info: UpdateInfo): String =
    if (info.apkSize > 0) " · %.1f MB".format(info.apkSize / 1_048_576f) else ""

/** Update prompt shown on top of every screen. Never covers an active tyre alarm. */
@Composable
fun UpdateDialogHost() {
    val context = LocalContext.current
    val visible by UpdateManager.dialogVisible.collectAsState()
    val state by UpdateManager.state.collectAsState()
    val activeAlarms by TpmsManager.activeAlarms.collectAsState()

    if (!visible) return
    val info = state.updateInfo ?: return
    val busy = state is UpdateState.Downloading || state is UpdateState.Installing
    // An update prompt must never hide a tyre warning; it re-appears once the alarm clears.
    if (activeAlarms.isNotEmpty() && !busy) return

    Dialog(
        onDismissRequest = { if (!busy) UpdateManager.dismissDialog() },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = !busy)
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Surface1,
            modifier = Modifier.fillMaxWidth(0.9f).widthIn(max = 600.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("🔄  Update available", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                Text(
                    "Installed v${UpdateManager.installedVersionName}  →  New v${info.versionName}${sizeText(info)}",
                    color = Accent, fontSize = 14.sp, fontWeight = FontWeight.Bold
                )
                val notes = remember(info.notes) { ReleaseParser.plainNotes(info.notes) }
                if (notes.isNotEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 170.dp)
                            .background(Surface2, RoundedCornerShape(10.dp))
                            .verticalScroll(rememberScrollState()).padding(10.dp)
                    ) {
                        Text(notes, color = Color(0xFFCCCCDD), fontSize = 13.sp)
                    }
                }

                when (val s = state) {
                    is UpdateState.Downloading -> {
                        if (s.progress >= 0f) {
                            LinearProgressIndicator(progress = { s.progress }, color = AccentGreen,
                                trackColor = Color(0x33FFFFFF), modifier = Modifier.fillMaxWidth())
                            Text("Downloading… ${(s.progress * 100).toInt()}%", color = Muted, fontSize = 13.sp)
                        } else {
                            LinearProgressIndicator(color = AccentGreen, trackColor = Color(0x33FFFFFF),
                                modifier = Modifier.fillMaxWidth())
                            Text("Downloading…", color = Muted, fontSize = 13.sp)
                        }
                    }
                    is UpdateState.Installing -> {
                        LinearProgressIndicator(color = AccentGreen, trackColor = Color(0x33FFFFFF),
                            modifier = Modifier.fillMaxWidth())
                        Text("Confirm the installation in the Android dialog. Monitoring restarts automatically.",
                            color = Muted, fontSize = 13.sp)
                    }
                    is UpdateState.NeedsInstallPermission ->
                        Text("Android needs permission for TPMS to install updates. Tap \"Allow\", enable " +
                             "\"Allow from this source\", then press Back to return here.",
                            color = Color(0xFFFFD600), fontSize = 13.sp)
                    is UpdateState.Failed ->
                        Text(s.message, color = ErrorRed, fontSize = 13.sp)
                    else ->
                        Text("Tyre monitoring pauses for a few seconds while the update installs and then restarts automatically.",
                            color = Muted, fontSize = 13.sp)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    when (state) {
                        is UpdateState.Downloading ->
                            DialogButton("Cancel", primary = false) { UpdateManager.cancelDownload() }
                        is UpdateState.Installing ->
                            DialogButton("Hide", primary = false) { UpdateManager.dismissDialog() }
                        is UpdateState.NeedsInstallPermission -> {
                            DialogButton("Later", primary = false) { UpdateManager.dismissDialog() }
                            DialogButton("Allow", primary = true) { UpdateManager.openInstallPermissionSettings(context) }
                        }
                        is UpdateState.Failed -> {
                            DialogButton("Release page", primary = false) { UpdateManager.openReleasePage(context) }
                            DialogButton("Later", primary = false) { UpdateManager.dismissDialog() }
                            DialogButton("Retry", primary = true) { UpdateManager.startUpdate(context) }
                        }
                        else -> {
                            DialogButton("Skip version", primary = false) { UpdateManager.skipVersion() }
                            DialogButton("Later", primary = false) { UpdateManager.dismissDialog() }
                            DialogButton("Update now", primary = true) { UpdateManager.startUpdate(context) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DialogButton(text: String, primary: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = if (primary) Color(0xFF2E7D32) else Surface3),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.height(44.dp)
    ) { Text(text, color = Color.White, fontSize = 14.sp, fontWeight = if (primary) FontWeight.Bold else FontWeight.Normal) }
}

/** "App Updates" card for the Settings screen. */
@Composable
fun UpdateSettingsCard() {
    val context = LocalContext.current
    val state by UpdateManager.state.collectAsState()
    val autoCheck by UpdateManager.autoCheck.collectAsState()

    val (statusText, statusColor) = when (val s = state) {
        is UpdateState.Idle -> "Updates come from git.vhelectronics.com" to Muted
        is UpdateState.Checking -> "Checking for updates…" to Muted
        is UpdateState.UpToDate -> "✓ You have the latest version" to AccentGreen
        is UpdateState.Available -> "v${s.info.versionName} is available" to Color(0xFFFFD600)
        is UpdateState.Downloading ->
            (if (s.progress >= 0f) "Downloading v${s.info.versionName}… ${(s.progress * 100).toInt()}%"
             else "Downloading v${s.info.versionName}…") to Accent
        is UpdateState.NeedsInstallPermission -> "Waiting for install permission" to Color(0xFFFFD600)
        is UpdateState.Installing -> "Installing v${s.info.versionName}…" to Accent
        is UpdateState.Failed -> s.message to ErrorRed
    }

    Column(
        modifier = Modifier.fillMaxWidth().background(Surface1, RoundedCornerShape(12.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Installed Version", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text("v${UpdateManager.installedVersionName} (build ${UpdateManager.installedVersionCode})",
                    color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Text("Auto-check", color = Muted, fontSize = 12.sp)
            Spacer(modifier = Modifier.width(6.dp))
            Switch(checked = autoCheck, onCheckedChange = { UpdateManager.setAutoCheck(it) },
                colors = SwitchDefaults.colors(checkedThumbColor = AccentGreen, checkedTrackColor = AccentGreen.copy(alpha = 0.4f)))
        }
        Text(statusText, color = statusColor, fontSize = 12.sp, maxLines = 3)
        val hasUpdate = state.updateInfo != null
        val busy = state is UpdateState.Checking
        Button(
            onClick = { if (hasUpdate) UpdateManager.showDialog() else UpdateManager.checkNow(context) },
            enabled = !busy,
            colors = ButtonDefaults.buttonColors(containerColor = if (hasUpdate) Color(0xFF2E7D32) else Surface3),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth().height(36.dp),
            contentPadding = PaddingValues(0.dp)
        ) {
            Text(if (hasUpdate) "Show Update ⬇" else "Check for Updates 🔄", color = Color.White, fontSize = 13.sp)
        }
    }
}
