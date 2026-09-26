package com.example.tpms.ui

import androidx.compose.animation.animateColor
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import androidx.compose.ui.unit.sp
import com.example.tpms.R
import com.example.tpms.TireData
import com.example.tpms.TpmsManager
import com.example.tpms.TpmsState

private val Surface0    = Color(0xFF0B0D14)
private val Surface1    = Color(0xFF161A24)
private val Surface2    = Color(0xFF1E2330)
private val Accent      = Color(0xFF4A90FF)
private val AccentGreen = Color(0xFF00E676)

@Composable
fun TpmsDashboard(state: TpmsState, onPairingClick: () -> Unit, onSettingsClick: () -> Unit) {
    val useBar          by TpmsManager.useBar.collectAsState()
    val lowPressurePsi  by TpmsManager.lowPressurePsi.collectAsState()
    val highPressurePsi by TpmsManager.highPressurePsi.collectAsState()
    val highTempC       by TpmsManager.highTempC.collectAsState()
    val isConnected     by TpmsManager.isConnected.collectAsState()
    val activeAlarms    by TpmsManager.activeAlarms.collectAsState()
    var snoozeTireName by remember { mutableStateOf<String?>(null) }
    
    var currentTime by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); currentTime = System.currentTimeMillis() } }

    val checkActive: (TireData?) -> Boolean = { data ->
        data != null && (currentTime - data.lastUpdatedMs) < 80_000L
    }
    val isVehicleMoving = checkActive(state.frontLeft) || checkActive(state.frontRight) ||
                          checkActive(state.rearLeft) || checkActive(state.rearRight)

    Box(
        modifier = Modifier.fillMaxSize()
            .background(Brush.radialGradient(listOf(Surface1, Surface0), radius = 1400f))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp)) {
                Text(text = "🚗  Vehicle TPMS",
                    color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 0.5.sp, modifier = Modifier.weight(1f))
                val pillColor = if (isConnected) AccentGreen else Color(0xFFFFD600)
                val pillText  = if (isConnected) "● USB Connected" else "○ Waiting for USB"
                val pillT = rememberInfiniteTransition(label = "pill")
                val pillAlpha by pillT.animateFloat(
                    initialValue = if (isConnected) 0.25f else 0.15f,
                    targetValue  = if (isConnected) 0.25f else 0.40f,
                    animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pa")
                Surface(shape = RoundedCornerShape(20.dp), color = pillColor.copy(alpha = pillAlpha),
                    border = androidx.compose.foundation.BorderStroke(1.dp, pillColor)) {
                    Text(pillText, color = pillColor, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
                }
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                Image(painter = painterResource(id = R.drawable.car_outline),
                    contentDescription = "Car outline",
                    modifier = Modifier.width(110.dp).fillMaxHeight(0.85f).align(Alignment.Center),
                    colorFilter = ColorFilter.tint(Accent.copy(alpha = 0.45f)))
                Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Column(modifier = Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        TireCard("Front Left",  state.frontLeft,  useBar, lowPressurePsi, highPressurePsi, highTempC, isVehicleMoving, currentTime, activeAlarms["Front Left"],  { if (it) snoozeTireName = "Front Left"  }, Modifier.weight(1f).fillMaxWidth())
                        TireCard("Rear Left",   state.rearLeft,   useBar, lowPressurePsi, highPressurePsi, highTempC, isVehicleMoving, currentTime, activeAlarms["Rear Left"],   { if (it) snoozeTireName = "Rear Left"   }, Modifier.weight(1f).fillMaxWidth())
                    }
                    Spacer(modifier = Modifier.width(114.dp))
                    Column(modifier = Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        TireCard("Front Right", state.frontRight, useBar, lowPressurePsi, highPressurePsi, highTempC, isVehicleMoving, currentTime, activeAlarms["Front Right"], { if (it) snoozeTireName = "Front Right" }, Modifier.weight(1f).fillMaxWidth())
                        TireCard("Rear Right",  state.rearRight,  useBar, lowPressurePsi, highPressurePsi, highTempC, isVehicleMoving, currentTime, activeAlarms["Rear Right"],  { if (it) snoozeTireName = "Rear Right"  }, Modifier.weight(1f).fillMaxWidth())
                    }
                }
            }
            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onPairingClick, colors = ButtonDefaults.buttonColors(containerColor = Surface2),
                    shape = RoundedCornerShape(12.dp), modifier = Modifier.height(48.dp).weight(1f)) {
                    Text("ID Study 🔔", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
                Button(onClick = onSettingsClick, colors = ButtonDefaults.buttonColors(containerColor = Surface2),
                    shape = RoundedCornerShape(12.dp), modifier = Modifier.height(48.dp).weight(1f)) {
                    Text("Settings ⚙️", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
    snoozeTireName?.let { SnoozeBottomSheet(tireName = it, onDismiss = { snoozeTireName = null }) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SnoozeBottomSheet(tireName: String, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = Surface2,
        dragHandle = { Box(Modifier.padding(vertical = 8.dp).width(40.dp).height(4.dp).background(Color(0x55FFFFFF), RoundedCornerShape(2.dp))) }
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("⏱ Snooze — $tireName", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 4.dp))
            Text("Select snooze duration.", color = Color(0xFF9999BB), fontSize = 13.sp)
            Spacer(modifier = Modifier.height(4.dp))
            val keys = listOf("$tireName:Low", "$tireName:High", "$tireName:Temp", "$tireName:Batt", "$tireName:SlowLeak", "$tireName:Offline")
            val isCurrentlySnoozed = keys.any { (TpmsManager.snoozedAlarms[it] ?: 0L) > System.currentTimeMillis() }

            if (isCurrentlySnoozed) {
                Button(onClick = { TpmsManager.unSnoozeTireAlarms(tireName); onDismiss() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)), shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, AccentGreen),
                    modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    Text("🔔 Un-snooze / Resume Alerts", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }

            listOf(
                Triple("Snooze 10 Min ⏱", 10 * 60_000L, AccentGreen.copy(alpha = 0.18f)),
                Triple("Snooze 30 Min ⏱", 30 * 60_000L, Accent.copy(alpha = 0.18f)),
                Triple("Snooze Until Restart 🔄", Long.MAX_VALUE, Color(0xFFFF8C00).copy(alpha = 0.18f))
            ).forEach { (label, duration, bg) ->
                Button(onClick = { TpmsManager.snoozeTireAlarms(tireName, duration); onDismiss() },
                    colors = ButtonDefaults.buttonColors(containerColor = bg), shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x33FFFFFF)),
                    modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    Text(label, color = Color.White, fontSize = 14.sp)
                }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text("Close", color = Color(0xFF9999AA), fontSize = 14.sp)
            }
        }
    }
}

fun Modifier.coloredShadow(color: Color, borderRadius: Dp = 16.dp, blurRadius: Dp = 14.dp, spread: Dp = 4.dp): Modifier =
    this.drawBehind {
        drawIntoCanvas {
            val paint = Paint()
            val fp = paint.asFrameworkPaint()
            val s = spread.toPx()
            if (blurRadius != 0.dp) fp.maskFilter = android.graphics.BlurMaskFilter(blurRadius.toPx(), android.graphics.BlurMaskFilter.Blur.NORMAL)
            fp.color = color.toArgb()
            it.drawRoundRect(-s, -s, size.width + s, size.height + s, borderRadius.toPx(), borderRadius.toPx(), paint)
        }
    }

fun tempColor(tempC: Int): Color = when {
    tempC >= 70 -> Color(0xFFFF3333)  // red   - danger
    tempC >= 61 -> Color(0xFFFF7700)  // orange - hot
    tempC >= 50 -> Color(0xFFFFCC00)  // amber  - warm
    tempC >= 35 -> Color(0xFF66DDFF)  // cyan   - moderate
    else        -> Color(0xFF4488FF)  // blue   - cold
}

fun trendArrow(trend: Int): String = when (trend) { 1 -> " ▲"; -1 -> " ▼"; else -> "" }

@Composable
fun TireCard(
    title: String, data: TireData?, useBar: Boolean,
    lowPsi: Float, highPsi: Float, highTmp: Int,
    isVehicleMoving: Boolean, currentTime: Long,
    alarmKey: String?,
    onClick: (isAlert: Boolean) -> Unit, modifier: Modifier = Modifier
) {
    val isAlert = alarmKey != null
    // Only the alarm that is actually active counts: a Fast Leak (never snoozable) must not
    // look "snoozed" just because the tyre's other warnings were silenced.
    val maxSnoozeUntil = alarmKey?.let { TpmsManager.snoozedAlarms[it] } ?: 0L
    val isSnoozed = isAlert && maxSnoozeUntil > currentTime
    val minutesLeft = if (maxSnoozeUntil == Long.MAX_VALUE) -1
                      else ((maxSnoozeUntil - currentTime + 59999L) / 60000L).toInt().coerceAtLeast(0)
    val isStale = data != null && (currentTime - data.lastUpdatedMs) > 10_000L
    val trend = TpmsManager.getTrend(title)
    val inf = rememberInfiniteTransition(label = "pulse")
    val pulseBg by inf.animateColor(
        initialValue = if (isSnoozed) Color(0xCC5A4A10) else Color(0xCC5A1010),
        targetValue  = if (isSnoozed) Color(0xCC8E7A15) else Color(0xCC8A1515),
        animationSpec = infiniteRepeatable(tween(750, easing = LinearEasing), RepeatMode.Reverse), label = "bg")
    val pulseGlow by inf.animateColor(
        initialValue = if (isSnoozed) Color(0xFFFFD600) else Color(0xFFE53935),
        targetValue  = if (isSnoozed) Color(0x55FFD600) else Color(0x55E53935),
        animationSpec = infiniteRepeatable(tween(750, easing = LinearEasing), RepeatMode.Reverse), label = "glow")
    val breathAlpha by inf.animateFloat(
        initialValue = 0.3f, targetValue = 0.7f,
        animationSpec = infiniteRepeatable(tween(1200, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "br")
    val cardBg      = if (isAlert) pulseBg else Surface1
    val borderColor = when { isAlert -> pulseGlow; isStale -> Color(0x33FF8C00); else -> Color(0x22FFFFFF) }
    val pressColor  = when { isAlert && !isSnoozed -> Color(0xFFFF5555); isSnoozed -> Color(0xFFFFD600); else -> Color.White }
    val glowMod = if (isAlert && !isSnoozed) modifier.coloredShadow(pulseGlow) else modifier
    Card(modifier = glowMod, colors = CardDefaults.cardColors(containerColor = cardBg),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.5.dp, borderColor),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isAlert) 14.dp else 6.dp),
        onClick = { onClick(isAlert) }
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceEvenly) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                Text(title, color = if (isStale) Color(0xFF777788) else Color(0xFFEEEEFF),
                    fontSize = 14.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                if (isStale) { Spacer(Modifier.width(4.dp)); Text("⚠", color = Color(0xFFFF8C00), fontSize = 13.sp) }
            }
            if (data != null) {
                val pressStr = if (useBar) "${"%.2f".format(data.pressurePsi / 14.5038)} Bar${trendArrow(trend)}"
                               else "${"%.1f".format(data.pressurePsi)} PSI${trendArrow(trend)}"
                val useFahrenheit by TpmsManager.useFahrenheit.collectAsState()
                val tempStr = if (useFahrenheit) "${Math.round(data.temperatureC * 9f / 5f + 32f)} °F"
                              else "${data.temperatureC} °C"

                Text(pressStr, color = if (isStale) pressColor.copy(alpha = 0.6f) else pressColor,
                    fontSize = 27.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
                Text(tempStr,
                    color = if (isStale) tempColor(data.temperatureC).copy(alpha = 0.5f) else tempColor(data.temperatureC),
                    fontSize = 18.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                val statusLabel = when {
                    isStale                          -> "⚠ Signal Lost"
                    alarmKey?.endsWith(":Low") == true -> "⬇ Low Pres"
                    alarmKey?.endsWith(":High") == true -> "⬆ High Pres"
                    alarmKey?.endsWith(":Temp") == true -> "🔥 Hot!"
                    alarmKey?.endsWith(":FastLeak") == true -> "⚡ Fast Leak!"
                    alarmKey?.endsWith(":SlowLeak") == true -> "🐢 Slow Leak"
                    alarmKey?.endsWith(":Batt") == true -> "🪫 Low Batt"
                    else                             -> "✓ Normal"
                }
                val statusColor = when {
                    isStale               -> Color(0xFFFF8C00)
                    isAlert && !isSnoozed -> Color(0xFFFF5555)
                    isSnoozed             -> Color(0xFFFFD600)
                    else                  -> AccentGreen
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(statusLabel, color = statusColor, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center)
                    Text(if (data.isLowBattery) "🪫" else "🔋", fontSize = 16.sp)
                }

                // Sensor state heuristics & age indicator (diagnostic dot)
                val ageSec = ((currentTime - data.lastUpdatedMs) / 1000L).toInt()
                val (diagLabel, diagColor, isBlinking) = when {
                    ageSec > 300 -> Triple("Offline", Color(0xFF888899), false)
                    ageSec >= 80 && isVehicleMoving -> Triple("Signal Drop", Color(0xFFFF3333), true)
                    ageSec >= 80 -> Triple("Idle", Color(0xFFFFAA00), false)
                    else -> Triple("Running", AccentGreen, false)
                }

                val blinkingAlpha by inf.animateFloat(
                    initialValue = 0.3f, targetValue = 1f,
                    animationSpec = infiniteRepeatable(tween(500), RepeatMode.Reverse), label = "blinkAlpha"
                )
                val finalAlpha = if (isBlinking) blinkingAlpha else 1f

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "●",
                        color = diagColor.copy(alpha = finalAlpha),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = diagLabel,
                        color = diagColor.copy(alpha = finalAlpha),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                    val ageText = when {
                        ageSec < 5   -> "live"
                        ageSec < 60  -> "${ageSec}s"
                        else         -> "${ageSec / 60}m"
                    }
                    Text(
                        text = "($ageText)",
                        color = Color(0x99FFFFFF),
                        fontSize = 11.sp
                    )
                }

                if (isSnoozed) {
                    Text(if (minutesLeft < 0) "snoozed (Restart)" else "snoozed ${minutesLeft}m",
                        color = Color(0xFFFFD600), fontSize = 11.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                }
            } else {
                Text("⟳", color = Accent.copy(alpha = breathAlpha), fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Text("Searching…", color = Color(0xFF555577).copy(alpha = breathAlpha), fontSize = 13.sp)
            }
        }
    }
}
