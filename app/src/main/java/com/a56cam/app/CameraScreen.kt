package com.a56cam.app

import android.content.Intent
import android.util.Size
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.a56cam.camera.AppLog
import com.a56cam.camera.CameraController
import com.a56cam.camera.CameraController.FocusState
import com.a56cam.camera.CameraMath
import java.util.Locale

@Composable
fun CameraScreen(vm: CameraViewModel, deviceOrientation: () -> Int, onOpenSettings: () -> Unit) {
    val st by vm.controller.state.collectAsState()
    val photo by vm.photo.collectAsState()
    val context = LocalContext.current
    val lens = st.lens

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .safeDrawingPadding(),
    ) {
        // Top bar: settings and the two locks.
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onOpenSettings) { Text("Settings", color = Color.White) }
            Row {
                LockChip("AE lock", st.aeLock, lens?.aeLockAvailable == true) { vm.controller.setAeLock(!st.aeLock) }
                Spacer(Modifier.size(8.dp))
                LockChip("WB lock", st.awbLock, lens?.awbLockAvailable == true) { vm.controller.setAwbLock(!st.awbLock) }
            }
        }

        // Preview, exactly the shape of the photo (4:3 portrait on this phone).
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(if (lens != null) lens.previewSize.height.toFloat() / lens.previewSize.width else 0.75f),
        ) {
            if (lens != null) {
                PreviewSurface(vm.controller, lens.previewSize)
                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(lens.id) {
                            detectTapGestures(
                                onTap = { o ->
                                    vm.controller.focusAt(CameraMath.Point(o.x / size.width, o.y / size.height))
                                },
                                onLongPress = { vm.controller.resetFocus() },
                            )
                        },
                )
                FocusRing(st)
            }
            ShutterFlash(photo.shots)
            val status = st.error ?: if (!st.open) "Starting camera…" else null
            status?.let {
                Text(
                    it,
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center).background(Color(0x99000000)).padding(12.dp),
                )
            }
        }

        // Exposure compensation.
        if (lens != null && lens.evStep > 0f && lens.evMax > lens.evMin) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    String.format(Locale.US, "EV %+.1f", st.evIndex * lens.evStep),
                    color = Color.White,
                    fontSize = 13.sp,
                    modifier = Modifier.clickable { vm.controller.setEv(0) }.padding(end = 8.dp),
                )
                Slider(
                    value = st.evIndex.toFloat(),
                    onValueChange = { vm.controller.setEv(Math.round(it)) },
                    valueRange = lens.evMin.toFloat()..lens.evMax.toFloat(),
                    steps = (lens.evMax - lens.evMin - 1).coerceAtLeast(0),
                    colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White),
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (st.focusPoint != null) {
            Text(
                "Focus and exposure set by tap. Long-press the preview or tap here for auto.",
                color = Color(0xFFFFD54F),
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 16.dp).clickable { vm.controller.resetFocus() },
            )
        }

        Spacer(Modifier.weight(1f))

        // Lens choice for the current side (e.g. 0.6x and 1x on the back).
        val sameSide = st.lenses.filter { it.front == lens?.front }
        if (sameSide.size > 1) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                sameSide.forEach { l ->
                    val selected = l.id == lens?.id
                    Text(
                        l.zoomLabel,
                        color = if (selected) Color.Black else Color.White,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier
                            .padding(horizontal = 6.dp)
                            .clip(CircleShape)
                            .background(if (selected) Color(0xFFFFD54F) else Color(0x33FFFFFF))
                            .clickable(enabled = !st.capturing) { vm.selectLens(l.id) }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        photo.message?.let {
            Text(it, color = Color(0xFFFF8A80), fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp))
        }

        // Thumbnail, shutter, switch camera.
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0x33FFFFFF))
                    .clickable(enabled = photo.lastUri != null) {
                        val uri = photo.lastUri ?: return@clickable
                        try {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/jpeg")
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                            )
                        } catch (e: Exception) {
                            AppLog.w("No app to open $uri", e)
                        }
                    },
            ) {
                photo.thumbnail?.let {
                    Image(it.asImageBitmap(), "Last photo", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
            }

            val canShoot = st.open && !st.capturing && !photo.saving
            Box(
                Modifier
                    .size(78.dp)
                    .border(4.dp, Color.White, CircleShape)
                    .padding(8.dp)
                    .clip(CircleShape)
                    .background(if (canShoot) Color.White else Color.Gray)
                    .clickable(enabled = canShoot) { vm.shoot(deviceOrientation()) },
            )

            val hasOtherSide = st.lenses.any { it.front != lens?.front }
            Box(
                Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(Color(0x33FFFFFF))
                    .alpha(if (hasOtherSide) 1f else 0.3f)
                    .clickable(enabled = hasOtherSide && !st.capturing) { vm.switchFacing() },
                contentAlignment = Alignment.Center,
            ) {
                Text("⇄", color = Color.White, fontSize = 24.sp)
            }
        }
    }
}

/** SurfaceView sized to the camera's preview stream; the controller opens the camera once it is ready. */
@Composable
private fun PreviewSurface(controller: CameraController, previewSize: Size) {
    val callback = remember(controller) {
        object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = Unit

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                controller.onSurfaceChanged(holder.surface, Size(width, height))
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                controller.onSurfaceDestroyed()
            }
        }
    }
    AndroidView(
        factory = { ctx -> SurfaceView(ctx).apply { holder.addCallback(callback) } },
        update = { view -> view.holder.setFixedSize(previewSize.width, previewSize.height) },
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
private fun FocusRing(st: CameraController.State) {
    val point = st.focusPoint ?: return
    val color = when (st.focusState) {
        FocusState.FOCUSED -> Color(0xFF69F0AE)
        FocusState.FAILED -> Color(0xFFFF5252)
        else -> Color.White
    }
    Canvas(Modifier.fillMaxSize()) {
        drawCircle(
            color = color,
            radius = 36.dp.toPx(),
            center = Offset(point.x * size.width, point.y * size.height),
            style = Stroke(width = 2.dp.toPx()),
        )
    }
}

/** A brief white flash over the preview after each saved photo. */
@Composable
private fun ShutterFlash(shots: Int) {
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(shots) {
        if (shots == 0) return@LaunchedEffect
        alpha.snapTo(0.7f)
        alpha.animateTo(0f, tween(250))
    }
    if (alpha.value > 0f) {
        Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = alpha.value)))
    }
}

@Composable
private fun LockChip(label: String, locked: Boolean, available: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = when {
            !available -> Color.Gray
            locked -> Color.Black
            else -> Color.White
        },
        fontSize = 13.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (locked) Color(0xFFFFD54F) else Color(0x33FFFFFF))
            .clickable(enabled = available, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}
