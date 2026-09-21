package com.example.tpms.ui

import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.tpms.TpmsManager
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive

private val Surface0    = Color(0xFF0B0D14)
private val Surface1    = Color(0xFF161A24)
private val Surface2    = Color(0xFF1E2330)
private val Surface3    = Color(0xFF252A3A)
private val Accent      = Color(0xFF4A90FF)
private val AccentGreen = Color(0xFF00E676)

@Composable
fun SettingsUI(onBack: () -> Unit) {
    val context      = LocalContext.current
    val useBar       by TpmsManager.useBar.collectAsState()
    val highPressure by TpmsManager.highPressurePsi.collectAsState()
    val lowPressure  by TpmsManager.lowPressurePsi.collectAsState()
    val highTemp     by TpmsManager.highTempC.collectAsState()
    val alarmSoundUri by TpmsManager.alarmSoundUri.collectAsState()
    val isTesting    by TpmsManager.isTestingSound.collectAsState()
    DisposableEffect(Unit) { onDispose { TpmsManager.setTestingSound(false) } }
    var soundName by remember { mutableStateOf("Default System Alarm") }
    LaunchedEffect(alarmSoundUri) {
        soundName = if (alarmSoundUri != null) {
            try { RingtoneManager.getRingtone(context, Uri.parse(alarmSoundUri))?.getTitle(context) ?: "Unknown Sound" }
            catch (_: Exception) { "Unknown Sound" }
        } else "Default System Alarm"
    }
    val soundPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            @Suppress("DEPRECATION")
            val uri: Uri? = result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            TpmsManager.setAlarmSoundUri(uri?.toString())
        }
    }
    var displayExpanded by remember { mutableStateOf(true) }
    var soundExpanded by remember { mutableStateOf(true) }
    var calibrationExpanded by remember { mutableStateOf(true) }
    var thresholdsExpanded by remember { mutableStateOf(true) }

    Box(modifier = Modifier.fillMaxSize().background(Surface0).padding(16.dp)) {
        Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(
                modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("⚙️ Settings", color = Color.White, fontSize = 22.sp,
                    fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(bottom = 2.dp))
                SettingsSectionHeader("📺  Display", displayExpanded) { displayExpanded = !displayExpanded }
                if (displayExpanded) {
                    val useFahrenheit by TpmsManager.useFahrenheit.collectAsState()
                    Column(modifier = Modifier.fillMaxWidth().background(Surface1, RoundedCornerShape(12.dp)).padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Pressure Unit", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                Text(if (useBar) "BAR" else "PSI", color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                            Switch(checked = useBar, onCheckedChange = { TpmsManager.setUseBar(it) },
                                colors = SwitchDefaults.colors(checkedThumbColor = AccentGreen, checkedTrackColor = AccentGreen.copy(alpha = 0.4f)))
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        HorizontalDivider(color = Color(0x11FFFFFF))
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Temperature Unit", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                Text(if (useFahrenheit) "Fahrenheit (°F)" else "Celsius (°C)", color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                            Switch(checked = useFahrenheit, onCheckedChange = { TpmsManager.setUseFahrenheit(it) },
                                colors = SwitchDefaults.colors(checkedThumbColor = AccentGreen, checkedTrackColor = AccentGreen.copy(alpha = 0.4f)))
                        }
                    }
                }
                SettingsSectionHeader("🎵  Alarm Sound", soundExpanded) { soundExpanded = !soundExpanded }
                if (soundExpanded) {
                    Column(modifier = Modifier.fillMaxWidth().background(Surface1, RoundedCornerShape(12.dp)).padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Current Sound", color = Color(0xFF9999AA), fontSize = 12.sp)
                        Text(soundName, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        Button(
                            onClick = {
                                val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                                    putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM or RingtoneManager.TYPE_NOTIFICATION)
                                    putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Select Alarm Sound")
                                    putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                                    putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                                    alarmSoundUri?.let { putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(it)) }
                                }
                                soundPickerLauncher.launch(intent)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Surface3),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth().height(36.dp),
                            contentPadding = PaddingValues(0.dp)
                        ) { Text("Select Sound 🎵", color = Color.White, fontSize = 13.sp) }
                    }
                }
                SettingsSectionHeader("📐  Sensor Calibration", calibrationExpanded) { calibrationExpanded = !calibrationExpanded }
                if (calibrationExpanded) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            CalibrationItem("FL", 0, useBar)
                            CalibrationItem("RL", 16, useBar)
                        }
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            CalibrationItem("FR", 1, useBar)
                            CalibrationItem("RR", 17, useBar)
                        }
                    }
                }
            }
            Column(
                modifier = Modifier.weight(1.2f).fillMaxHeight()
                    .padding(bottom = 60.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SettingsSectionHeader("🔔  Alarm Thresholds", thresholdsExpanded) { thresholdsExpanded = !thresholdsExpanded }
                if (thresholdsExpanded) {
                    SettingStepper(
                        title = if (useBar) "High Pressure (BAR)" else "High Pressure (PSI)",
                        value = highPressure, useBar = useBar, isPressure = true,
                        minVal = 35f, maxVal = 60f, step = 1f,
                        onValueChange = { TpmsManager.setHighPressurePsi(it) }
                    )
                    SettingStepper(
                        title = if (useBar) "Low Pressure (BAR)" else "Low Pressure (PSI)",
                        value = lowPressure, useBar = useBar, isPressure = true,
                        minVal = 15f, maxVal = 35f, step = 1f,
                        onValueChange = { TpmsManager.setLowPressurePsi(it) }
                    )
                    val useFahrenheit by TpmsManager.useFahrenheit.collectAsState()
                    SettingStepper(
                        title = if (useFahrenheit) "High Temperature (°F)" else "High Temperature (°C)",
                        value = highTemp.toFloat(), useBar = false, isPressure = false,
                        minVal = 0f, maxVal = 80f, step = 1f,
                        onValueChange = { TpmsManager.setHighTempC(Math.round(it)) }
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            TpmsManager.setUseBar(true)
                            TpmsManager.setUseFahrenheit(false)
                            TpmsManager.setHighPressurePsi(45f)
                            TpmsManager.setLowPressurePsi(28f)
                            TpmsManager.setHighTempC(65)
                            TpmsManager.setAlarmSoundUri(null)
                            TpmsManager.resetCalibration()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Surface3),
                        shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f).height(44.dp),
                        contentPadding = PaddingValues(0.dp)
                    ) { Text("Reset Defaults", color = Color.White, fontSize = 13.sp) }
                    Button(
                        onClick = { TpmsManager.setTestingSound(!isTesting) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isTesting) Color(0xFFC62828) else Surface3),
                        shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f).height(44.dp),
                        contentPadding = PaddingValues(0.dp)
                    ) { Text(if (isTesting) "Stop 🛑" else "Test Alarm 🔊", color = Color.White, fontSize = 13.sp) }
                }
            }
        }
        Button(
            onClick = onBack,
            colors = ButtonDefaults.buttonColors(containerColor = Surface2),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.align(Alignment.BottomEnd).width(220.dp).height(48.dp)
        ) { Text("← Return to Dashboard", color = Color.White, fontSize = 15.sp) }
    }
}

@Composable
private fun SettingsSectionHeader(title: String, isExpanded: Boolean, onToggle: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() }
            .padding(vertical = 4.dp)
    ) {
        Text(title, color = Color(0xFF7788AA), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.width(8.dp))
        HorizontalDivider(modifier = Modifier.weight(1f), color = Color(0x22FFFFFF))
        Spacer(modifier = Modifier.width(8.dp))
        Text(if (isExpanded) "▲" else "▼", color = Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun SettingStepper(
    title: String, value: Float, useBar: Boolean, isPressure: Boolean,
    minVal: Float, maxVal: Float, step: Float, onValueChange: (Float) -> Unit
) {
    val useFahrenheit by TpmsManager.useFahrenheit.collectAsState()
    
    val displayValue = if (isPressure) {
        if (useBar) "%.1f Bar".format(value / 14.5038f) else "${value.toInt()} PSI"
    } else {
        if (useFahrenheit) "${Math.round(value * 9f / 5f + 32f)} °F" else "${value.toInt()} °C"
    }
    
    val fraction = ((value - minVal) / (maxVal - minVal)).coerceIn(0f, 1f)

    val onStepClick: (Boolean) -> Unit = { isIncrement ->
        val nextVal: Float
        if (isPressure) {
            if (useBar) {
                val currentBar = value / 14.5038f
                val roundedBar = Math.round(currentBar * 10f) / 10f
                val nextBar = if (isIncrement) roundedBar + 0.1f else roundedBar - 0.1f
                val nextPsi = nextBar * 14.5038f
                nextVal = nextPsi.coerceIn(minVal - 2f, maxVal + 2f)
            } else {
                val roundedPsi = Math.round(value)
                val nextPsi = if (isIncrement) roundedPsi + 1 else roundedPsi - 1
                nextVal = nextPsi.toFloat().coerceIn(minVal, maxVal)
            }
        } else {
            if (useFahrenheit) {
                val tempF = value * 9f / 5f + 32f
                val roundedF = Math.round(tempF)
                val nextF = if (isIncrement) roundedF + 1 else roundedF - 1
                var nextC = Math.round((nextF - 32f) * 5f / 9f)
                if (isIncrement && nextC <= value.toInt()) nextC = value.toInt() + 1
                if (!isIncrement && nextC >= value.toInt()) nextC = value.toInt() - 1
                nextVal = nextC.toFloat().coerceIn(minVal, maxVal)
            } else {
                val roundedC = Math.round(value)
                val nextC = if (isIncrement) roundedC + 1 else roundedC - 1
                nextVal = nextC.toFloat().coerceIn(minVal, maxVal)
            }
        }
        onValueChange(nextVal)
    }

    Column(modifier = Modifier.fillMaxWidth().background(Surface1, RoundedCornerShape(12.dp)).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = Color(0xFF9999AA), fontSize = 13.sp)
                Spacer(modifier = Modifier.height(2.dp))
                Text(displayValue, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HoldButton("-") { onStepClick(false) }
                HoldButton("+") { onStepClick(true) }
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        // Idea D: arc gauge with speedometer needle
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Canvas(modifier = Modifier.width(140.dp).height(75.dp)) {
                val sw = 5.dp.toPx()
                val pad = sw / 2f
                val w = size.width
                val h = size.height
                val radius = w / 2f - pad
                val cx = w / 2f
                val cy = h - pad

                val arcRect = Size(radius * 2, radius * 2)
                val topLeft = Offset(pad, cy - radius)

                // 1. Draw track
                drawArc(
                    color = Color(0x25FFFFFF),
                    startAngle = 180f, sweepAngle = 180f, useCenter = false,
                    topLeft = topLeft, size = arcRect,
                    style = Stroke(width = sw, cap = StrokeCap.Round)
                )

                // 2. Draw filled portion
                val arcColor = when {
                    fraction > 0.8f -> Color(0xFFFF5555)
                    fraction > 0.5f -> Color(0xFFFFAA00)
                    else            -> Accent
                }
                drawArc(
                    color = arcColor.copy(alpha = 0.85f),
                    startAngle = 180f, sweepAngle = 180f * fraction, useCenter = false,
                    topLeft = topLeft, size = arcRect,
                    style = Stroke(width = sw, cap = StrokeCap.Round)
                )

                // 3. Draw needle
                val needleAngleRad = Math.toRadians((180f + 180f * fraction).toDouble())
                val needleLength = radius - 6.dp.toPx()
                val tx = cx + needleLength * Math.cos(needleAngleRad).toFloat()
                val ty = cy + needleLength * Math.sin(needleAngleRad).toFloat()
                
                // Draw needle line
                drawLine(
                    color = Color(0xFFFF3D00), // Neon orange-red for needle
                    start = Offset(cx, cy),
                    end = Offset(tx, ty),
                    strokeWidth = 3.dp.toPx(),
                    cap = StrokeCap.Round
                )

                // 4. Draw center pivot pin
                drawCircle(
                    color = Color.White,
                    radius = 5.dp.toPx(),
                    center = Offset(cx, cy)
                )
                drawCircle(
                    color = Color(0xFF1E2330),
                    radius = 2.dp.toPx(),
                    center = Offset(cx, cy)
                )
            }
        }
    }
}

@Composable
fun HoldButton(text: String, onStep: () -> Unit) {
    val currentOnStep by rememberUpdatedState(onStep)
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    Box(
        modifier = Modifier.size(44.dp).background(Surface3, RoundedCornerShape(8.dp))
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false); down.consume()
                    view.playSoundEffect(android.view.SoundEffectConstants.CLICK); currentOnStep()
                    val job = scope.launch {
                        delay(400)
                        while (isActive) { view.playSoundEffect(android.view.SoundEffectConstants.CLICK); currentOnStep(); delay(100) }
                    }
                    waitForUpOrCancellation(); job.cancel()
                }
            },
        contentAlignment = Alignment.Center
    ) { Text(text, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold) }
}

@Composable
fun CalibrationItem(label: String, position: Int, useBar: Boolean) {
    val calPsi = when (position) {
        0 -> TpmsManager.calFl.collectAsState().value
        1 -> TpmsManager.calFr.collectAsState().value
        16 -> TpmsManager.calRl.collectAsState().value
        17 -> TpmsManager.calRr.collectAsState().value
        else -> 0f
    }
    val displayText = if (useBar) {
        val bar = calPsi / 14.5038f; val sign = if (bar > 0f) "+" else ""; "$label: $sign%.2f Bar".format(bar)
    } else {
        val sign = if (calPsi > 0f) "+" else ""; "$label: $sign%.1f PSI".format(calPsi)
    }
    Row(
        modifier = Modifier.fillMaxWidth().background(Surface1, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(displayText, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            HoldButtonMini("-") { TpmsManager.adjustCalibration(position, isIncrement = false) }
            HoldButtonMini("+") { TpmsManager.adjustCalibration(position, isIncrement = true) }
        }
    }
}

@Composable
fun HoldButtonMini(text: String, onStep: () -> Unit) {
    val currentOnStep by rememberUpdatedState(onStep)
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    Box(
        modifier = Modifier.size(30.dp).background(Surface3, RoundedCornerShape(6.dp))
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false); down.consume()
                    view.playSoundEffect(android.view.SoundEffectConstants.CLICK); currentOnStep()
                    val job = scope.launch {
                        delay(400)
                        while (isActive) { view.playSoundEffect(android.view.SoundEffectConstants.CLICK); currentOnStep(); delay(100) }
                    }
                    waitForUpOrCancellation(); job.cancel()
                }
            },
        contentAlignment = Alignment.Center
    ) { Text(text, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
}
