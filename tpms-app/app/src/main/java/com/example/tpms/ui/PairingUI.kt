package com.example.tpms.ui

import androidx.compose.animation.animateColor
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.example.tpms.PairingState
import com.example.tpms.PairingStep
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.tpms.TpmsManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.geometry.Offset
import com.example.tpms.R

private val Surface0    = Color(0xFF0B0D14)
private val Surface1    = Color(0xFF161A24)
private val Surface2    = Color(0xFF1E2330)
private val Accent      = Color(0xFF4A90FF)
private val AccentGreen = Color(0xFF00E676)

private val TIRE_POSITIONS = listOf(
    Triple("Front Left",  "FL", 0),
    Triple("Front Right", "FR", 1),
    Triple("Rear Left",   "RL", 16),
    Triple("Rear Right",  "RR", 17)
)

@Composable
fun PairingUI(onBack: () -> Unit, onPairPosition: (Int) -> Unit) {
    val pairingState by TpmsManager.pairingState.collectAsState()
    DisposableEffect(Unit) {
        TpmsManager.stopPairing()
        onDispose { TpmsManager.stopPairing() }
    }
    Box(
        modifier = Modifier.fillMaxSize()
            .background(Brush.radialGradient(listOf(Surface1, Surface0), radius = 1000f))
            .padding(16.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("🔔 Sensor Pairing", color = Color.White, fontSize = 20.sp,
                fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
            WizardInstruction(pairingState)
            Spacer(modifier = Modifier.height(16.dp))
            val handleCardClick: (Int) -> Unit = { pos ->
                if (pairingState is PairingState.Pairing && (pairingState as PairingState.Pairing).positionCode == pos)
                    TpmsManager.stopPairing()
                else
                    onPairPosition(pos)
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                val activePos = when (pairingState) {
                    is PairingState.Pairing -> (pairingState as PairingState.Pairing).positionCode
                    is PairingState.Success -> (pairingState as PairingState.Success).positionCode
                    else -> null
                }
                val isSuccess = pairingState is PairingState.Success
                Box(modifier = Modifier.fillMaxHeight().align(Alignment.Center)) {
                    PairingCarOutline(activePositionCode = activePos, isSuccess = isSuccess)
                }
                Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(modifier = Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        PairingCard(TIRE_POSITIONS[0].first, TIRE_POSITIONS[0].second, TIRE_POSITIONS[0].third, pairingState, handleCardClick, Modifier.weight(1f))
                        PairingCard(TIRE_POSITIONS[2].first, TIRE_POSITIONS[2].second, TIRE_POSITIONS[2].third, pairingState, handleCardClick, Modifier.weight(1f))
                    }
                    Spacer(modifier = Modifier.width(96.dp))
                    Column(modifier = Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        PairingCard(TIRE_POSITIONS[1].first, TIRE_POSITIONS[1].second, TIRE_POSITIONS[1].third, pairingState, handleCardClick, Modifier.weight(1f))
                        PairingCard(TIRE_POSITIONS[3].first, TIRE_POSITIONS[3].second, TIRE_POSITIONS[3].third, pairingState, handleCardClick, Modifier.weight(1f))
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = { TpmsManager.stopPairing(); onBack() },
                colors = ButtonDefaults.buttonColors(containerColor = Surface2),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) {
                Text("← Return to Dashboard", color = Color.White, fontSize = 15.sp)
            }
        }
    }
}

@Composable
private fun PairingCarOutline(activePositionCode: Int?, isSuccess: Boolean) {
    val inf = rememberInfiniteTransition(label = "pairing_car")
    val pulseAlpha by inf.animateFloat(
        initialValue = 0.3f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(750), RepeatMode.Reverse), label = "alpha"
    )
    Box(
        modifier = Modifier.width(90.dp).fillMaxHeight(0.9f),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(id = R.drawable.car_outline),
            contentDescription = "Car outline",
            modifier = Modifier.fillMaxSize(),
            colorFilter = ColorFilter.tint(Color.White.copy(alpha = 0.15f))
        )
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val positions = listOf(0, 1, 16, 17)
            positions.forEach { pos ->
                val isActive = activePositionCode == pos
                if (isActive) {
                    val color = if (isSuccess) AccentGreen else Accent
                    val alpha = if (isSuccess) 1f else pulseAlpha
                    val cx = when (pos) {
                        0 -> w * 20f / 120f
                        16 -> w * 20f / 120f
                        1 -> w * 100f / 120f
                        else -> w * 100f / 120f
                    }
                    val cy = when (pos) {
                        0 -> h * 60f / 240f
                        1 -> h * 60f / 240f
                        16 -> h * 180f / 240f
                        else -> h * 180f / 240f
                    }
                    drawCircle(
                        color = color.copy(alpha = alpha * 0.35f),
                        radius = w * 14f / 120f,
                        center = Offset(cx, cy)
                    )
                    drawCircle(
                        color = color,
                        radius = w * 6f / 120f,
                        center = Offset(cx, cy)
                    )
                }
            }
        }
    }
}

@Composable
private fun WizardInstruction(state: PairingState) {
    val inf = rememberInfiniteTransition(label = "wizard")
    val spinDeg by inf.animateFloat(0f, 360f,
        infiniteRepeatable(tween(1200, easing = LinearEasing)), label = "spin")
    val breathAlpha by inf.animateFloat(0.6f, 1f,
        infiniteRepeatable(tween(800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "bra")
    val (icon, message, color) = when (state) {
        is PairingState.Idle ->
            Triple("🔔", "Tap a tyre position below to begin.", Color(0xFF9999BB))
        is PairingState.Pairing -> when (state.step) {
            PairingStep.WAITING_FOR_DROP ->
                Triple("🔩", "Step 1: Unscrew the valve sensor briefly.", Color(0xFFFFD600))
            PairingStep.WAITING_FOR_RISE ->
                Triple("✅", "Step 2: Screw the sensor back on tightly.", AccentGreen)
        }
        is PairingState.Success ->
            Triple("🎉", "Pairing complete! Sensor registered.", AccentGreen)
    }
    Row(
        modifier = Modifier.fillMaxWidth().background(Surface2, RoundedCornerShape(12.dp)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(icon, fontSize = 24.sp,
            modifier = if (state is PairingState.Pairing && state.step == PairingStep.WAITING_FOR_DROP)
                Modifier.rotate(spinDeg) else Modifier)
        Text(message, color = color.copy(alpha = if (state is PairingState.Pairing) breathAlpha else 1f),
            fontSize = 13.sp, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun PairingCard(
    label: String, shortCode: String, posCode: Int,
    state: PairingState, onPair: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val isPairing = state is PairingState.Pairing && state.positionCode == posCode
    val isSuccess = state is PairingState.Success && state.positionCode == posCode
    val inf = rememberInfiniteTransition(label = "card")
    val pulseBorder by inf.animateColor(
        initialValue = AccentGreen, targetValue = AccentGreen.copy(alpha = 0.3f),
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "pb")
    val bgColor     = when { isSuccess -> Color(0xCC004D22); isPairing -> Color(0xCC0E2E16); else -> Surface1 }
    val borderColor = when { isSuccess -> AccentGreen; isPairing -> pulseBorder; else -> Color(0x22FFFFFF) }
    Card(
        modifier = modifier.fillMaxWidth().height(120.dp),
        colors = CardDefaults.cardColors(containerColor = bgColor),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.5.dp, borderColor),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isPairing) 12.dp else 6.dp),
        onClick = { onPair(posCode) }
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceEvenly) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = when { isSuccess -> AccentGreen.copy(0.3f); isPairing -> AccentGreen.copy(0.2f); else -> Accent.copy(0.15f) },
                border = androidx.compose.foundation.BorderStroke(1.dp, if (isPairing || isSuccess) AccentGreen else Accent.copy(0.5f))
            ) {
                Text(shortCode, color = if (isPairing || isSuccess) AccentGreen else Accent,
                    fontSize = 13.sp, fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            }
            Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                color = Color(0xFFCCCCDD), textAlign = TextAlign.Center)
            if (isPairing) {
                val elapsed = 120 - (state as PairingState.Pairing).timeRemainingSec
                LinearProgressIndicator(
                    progress = (elapsed / 120f).coerceIn(0f, 1f),
                    color = AccentGreen, trackColor = Color(0x33FFFFFF),
                    modifier = Modifier.fillMaxWidth(0.85f).height(4.dp))
            }
            val statusText = when {
                isSuccess -> "✅ Paired!"
                isPairing -> {
                    val p = state as PairingState.Pairing
                    val stepTxt = if (p.step == PairingStep.WAITING_FOR_DROP) "Unscrew…" else "Screw back…"
                    "${p.timeRemainingSec}s — $stepTxt"
                }
                else -> "Tap to pair"
            }
            val statusColor = when { isSuccess -> AccentGreen; isPairing -> AccentGreen; else -> Color(0xFF6666AA) }
            Text(statusText, fontSize = 12.sp, color = statusColor, textAlign = TextAlign.Center)
        }
    }
}
