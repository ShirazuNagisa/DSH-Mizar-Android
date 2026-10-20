package me.shirazu.dshmizar.ui.pairing

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import me.shirazu.dshmizar.AppViewModel
import me.shirazu.dshmizar.R
import me.shirazu.dshmizar.ui.CameraScanner
import me.shirazu.dshmizar.ui.MizarColors
import me.shirazu.dshmizar.ui.design.ButtonShape
import me.shirazu.dshmizar.ui.design.Corner
import me.shirazu.dshmizar.ui.design.GlassCircleLabel
import me.shirazu.dshmizar.ui.design.LocalReduceMotion
import me.shirazu.dshmizar.ui.design.MizarMarkSearching
import me.shirazu.dshmizar.ui.design.Motion
import me.shirazu.dshmizar.ui.design.Radius
import me.shirazu.dshmizar.ui.design.Space
import me.shirazu.dshmizar.ui.design.Transitions
import me.shirazu.dshmizar.ui.design.glassCapsule
import me.shirazu.dshmizar.ui.design.pressable
import me.shirazu.dshmizar.ui.design.shapedClickable
import me.shirazu.mizar.kit.PairingTarget
import me.shirazu.mizar.kit.PairingUri

/**
 * Getting a device connected, once -- ported from iOS `Views/PairingView.swift`.
 *
 * Two ways in, because the QR path can fail for reasons that have nothing to do
 * with the app: a camera that will not focus, a code on a screen across the
 * room. The scan is the one big button; the manual path folds out beneath it
 * for the times the scan cannot work.
 *
 * The camera is asked for at the moment "Scan" is tapped, never before: a
 * permission most sessions never need, asked at launch, is a permission the
 * operator learns to refuse with their eyes closed.
 */

/** Nothing paired yet: the one thing to do is scan the code on the computer. */
@Composable
fun UnpairedScreen(model: AppViewModel) {
    var authority by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var deviceName by remember { mutableStateOf("Android") }
    var scanning by remember { mutableStateOf(false) }
    var showManual by remember { mutableStateOf(false) }

    if (scanning) {
        ScannerScreen(
            onCode = { scanned ->
                scanning = false
                PairingUri.parse(scanned)?.let { target -> model.pair(target, deviceName) }
            },
            onCancel = { scanning = false },
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // A signal looking for something to answer it.
        MizarMarkSearching(modifier = Modifier.width(84.dp).padding(top = 28.dp, bottom = 26.dp))

        Text(
            stringResource(R.string.unpaired_title),
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
            textAlign = TextAlign.Center,
        )
        Text(
            stringResource(R.string.unpaired_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )

        // The scan is the one big button.
        Button(
            onClick = { scanning = true },
            modifier = Modifier.fillMaxWidth().padding(top = 32.dp).height(54.dp),
            shape = ButtonShape.Primary,
        ) {
            Icon(Icons.Default.QrCodeScanner, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.scan_code),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            )
        }

        // The manual path folds out beneath it.
        val chevron by animateFloatAsState(if (showManual) 180f else 0f, Motion.pick(Motion.snappy()), label = "manualChevron")
        Row(
            modifier = Modifier
                .padding(top = 10.dp)
                .pressable(scale = 0.97f)
                .shapedClickable(Corner.sm) { showManual = !showManual }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.enter_manually),
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(6.dp))
            Icon(
                Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier.size(14.dp).rotate(chevron),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        AnimatedVisibility(visible = showManual, enter = Transitions.riseIn(), exit = Transitions.riseOut()) {
            ManualForm(
                authority = authority,
                onAuthority = { authority = it },
                code = code,
                onCode = { code = it.uppercase() },
                deviceName = deviceName,
                onDeviceName = { deviceName = it },
                onPair = {
                    model.pair(
                        PairingTarget(
                            authority = authority.trim(),
                            code = code.trim(),
                            hostName = null,
                            protocolVersion = null,
                            baseUrl = null,
                        ),
                        deviceName,
                    )
                },
            )
        }
    }
}

/** The folded-out manual path: host, code, and what this phone calls itself. */
@Composable
private fun ManualForm(
    authority: String,
    onAuthority: (String) -> Unit,
    code: String,
    onCode: (String) -> Unit,
    deviceName: String,
    onDeviceName: (String) -> Unit,
    onPair: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, Corner.md)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, Corner.md),
        ) {
            ManualField(
                value = authority,
                onValueChange = onAuthority,
                placeholder = stringResource(R.string.host_label) + "  192.168.1.20:19390",
                imeAction = ImeAction.Next,
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp)
                    .height(0.5.dp)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
            )
            ManualField(
                value = code,
                onValueChange = onCode,
                placeholder = stringResource(R.string.pairing_code),
                imeAction = ImeAction.Next,
                monospace = true,
                capitalize = true,
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(R.string.device_name),
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, Corner.md)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, Corner.md),
            ) {
                ManualField(
                    value = deviceName,
                    onValueChange = onDeviceName,
                    placeholder = "Android",
                    imeAction = ImeAction.Done,
                )
            }
        }

        Button(
            onClick = onPair,
            enabled = authority.isNotBlank() && code.isNotBlank(),
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp).height(50.dp),
            shape = ButtonShape.Primary,
        ) {
            Text(
                stringResource(R.string.pair),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            )
        }
    }
}

/** One borderless field inside the grouped card. */
@Composable
private fun ManualField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    imeAction: ImeAction,
    monospace: Boolean = false,
    capitalize: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(placeholder, maxLines = 1) },
        singleLine = true,
        textStyle = if (monospace) {
            MaterialTheme.typography.bodyLarge.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
        } else {
            MaterialTheme.typography.bodyLarge
        },
        keyboardOptions = KeyboardOptions(
            imeAction = imeAction,
            capitalization = if (capitalize) KeyboardCapitalization.Characters else KeyboardCapitalization.None,
        ),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            focusedBorderColor = Color.Transparent,
            unfocusedBorderColor = Color.Transparent,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * The camera, with a frame to aim and a way out -- iOS `ScannerSheet`. The
 * scanner itself (`CameraScanner`) is untouched; this is the chrome around it:
 * four corners that close in on the code and turn green when it is read.
 */
@Composable
internal fun ScannerScreen(onCode: (String) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        granted = allowed
    }
    var locked by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (granted) {
            CameraScanner(onCode = { value ->
                if (!locked) {
                    locked = true
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onCode(value)
                }
            })
        } else {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.camera_needed),
                    color = Color.White,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = { request.launch(Manifest.permission.CAMERA) }, shape = ButtonShape.Primary) {
                    Text(stringResource(R.string.allow_camera))
                }
            }
        }

        // The frame: four corners that close in on the code when it is read. The
        // frame is laid out once at its open size and closes in by scale alone, so
        // the lock moves no layout; under Reduce Motion it is simply closed.
        val reduceMotion = LocalReduceMotion.current
        val frameScale by animateFloatAsState(
            if (locked) ViewfinderLocked / ViewfinderOpen else 1f,
            if (reduceMotion) snap() else Motion.bouncy(),
            label = "viewfinderScale",
        )
        val frameColor = if (locked) MizarColors.Running else Color.White
        ViewfinderCorners(
            color = frameColor,
            scale = { frameScale },
            modifier = Modifier.align(Alignment.Center).size(ViewfinderOpen),
        )

        // The way out: a glass circle, top trailing, as on iOS.
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(20.dp),
        ) {
            GlassCircleLabel(size = 44.dp, onClick = onCancel, camera = true) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(R.string.cancel),
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 40.dp)
                .glassCapsule(camera = true)
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            Text(
                stringResource(if (locked) R.string.connecting else R.string.point_at_code),
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
            )
        }
    }
}

/** The viewfinder's two sizes: waiting for a code, and closed in on one. */
private val ViewfinderOpen = 250.dp
private val ViewfinderLocked = 200.dp

/**
 * Four rounded corners of a square -- iOS `ViewfinderCorners`.
 *
 * Drawn at [scale] by the layer, not by layout: the arms scale with the frame, as
 * they always have, while the stroke and the corner are drawn at `1 / scale` so a
 * frame that has closed in keeps a 4 dp line and the ladder's corner.
 */
@Composable
private fun ViewfinderCorners(color: Color, scale: () -> Float, modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(
        modifier.graphicsLayer {
            val s = scale()
            scaleX = s
            scaleY = s
        },
    ) {
        val s = scale().coerceAtLeast(0.01f)
        val arm = size.width * 0.18f
        val r = Radius.lg.toPx() / s
        val stroke = Stroke(width = 4.dp.toPx() / s, cap = StrokeCap.Round)
        val w = size.width
        val h = size.height

        fun corner(startX: Float, startY: Float, horizontal: Float, vertical: Float) {
            // One rounded corner: an arm along each axis, joined by a quad.
            drawLine(color, androidx.compose.ui.geometry.Offset(startX, startY + arm * vertical), androidx.compose.ui.geometry.Offset(startX, startY + r * vertical), strokeWidth = stroke.width, cap = StrokeCap.Round)
            drawArc(
                color = color,
                startAngle = when {
                    horizontal > 0 && vertical > 0 -> 180f
                    horizontal < 0 && vertical > 0 -> 270f
                    horizontal < 0 && vertical < 0 -> 0f
                    else -> 90f
                },
                sweepAngle = 90f,
                useCenter = false,
                topLeft = androidx.compose.ui.geometry.Offset(
                    if (horizontal > 0) startX else startX - 2 * r,
                    if (vertical > 0) startY else startY - 2 * r,
                ),
                size = androidx.compose.ui.geometry.Size(2 * r, 2 * r),
                style = stroke,
            )
            drawLine(color, androidx.compose.ui.geometry.Offset(startX + r * horizontal, startY), androidx.compose.ui.geometry.Offset(startX + arm * horizontal, startY), strokeWidth = stroke.width, cap = StrokeCap.Round)
        }

        corner(0f, 0f, 1f, 1f) // top left
        corner(w, 0f, -1f, 1f) // top right
        corner(w, h, -1f, -1f) // bottom right
        corner(0f, h, 1f, -1f) // bottom left
    }
}
