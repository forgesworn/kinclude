package app.kinclude.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kinclude.AppGraph
import app.kinclude.call.RemoteMedia
import app.kinclude.core.RoomCall
import kotlinx.coroutines.launch
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

private data class Tile(val key: String, val name: String, val video: VideoTrack?, val connected: Boolean)

@Composable
fun CallScreen(graph: AppGraph, roomId: String, back: () -> Unit) {
    val household by graph.household.collectAsStateWithLifecycle()
    val h = household ?: return
    val room = h.rooms[roomId] ?: run { back(); return }
    val call = remember(roomId) { graph.callFor(room) }
    val scope = rememberCoroutineScope()
    val state = fromGraph(graph, roomId) {
        val views = h.views().values.toList()
        val names = { pk: String -> nameOf(h, views, pk) }
        Triple(call.status, call.peers().map { p ->
            Tile(p.device, names(p.participant), (p.media as? RemoteMedia)?.video, p.connection == "connected")
        }, call.onCall().map { names(it.participant) }.distinct())
    } ?: return
    val (status, tiles, onCall) = state
    val startCall = rememberCall(graph, room, onStarted = {})
    LaunchedEffect(call.ended) { if (call.ended != null) back() }

    Box(Modifier.fillMaxSize().background(Brand.Room)) {
        if (status == RoomCall.Status.IDLE) {
            Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(if (onCall.isEmpty()) "No call right now." else "${list(onCall)} ${if (onCall.size > 1) "are" else "is"} on a call.",
                    color = Brand.Paper, fontFamily = FontFamily.Serif, fontSize = 22.sp)
                Text("Everyone in the room sees who is on a call.", color = Brand.Paper.copy(alpha = 0.7f))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button({ startCall(false) }) { Text("Join with voice") }
                    OutlinedButton({ startCall(true) }) { Text("Join with video", color = Brand.Paper) }
                }
                OutlinedButton(back) { Text("Back", color = Brand.Paper) }
            }
            return@Box
        }
        if (tiles.isEmpty()) {
            Text(if (status == RoomCall.Status.JOINING) "Joining…" else "Waiting for others to join…",
                Modifier.align(Alignment.Center), color = Brand.Paper, fontFamily = FontFamily.Serif, fontSize = 22.sp)
        } else {
            LazyVerticalGrid(GridCells.Fixed(if (tiles.size <= 1) 1 else 2), Modifier.fillMaxSize().safeDrawingPadding().padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(tiles, key = { it.key }) { t -> VideoTile(graph.calls.eglBase, t.video, t.name, Modifier.fillMaxWidth().aspectRatio(if (tiles.size <= 1) 0.6f else 0.75f)) }
            }
        }
        // Your own camera, small, in the corner, as every call app does.
        if (call.video && !call.cameraOff) {
            val local = graph.calls.current?.video
            Box(Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(16.dp).size(96.dp, 128.dp).clip(RoundedCornerShape(12.dp)).background(Brand.Card)) {
                Text("You", Modifier.align(Alignment.BottomStart).padding(6.dp), color = Color.White, style = MaterialTheme.typography.labelSmall)
                if (local != null) VideoTile(graph.calls.eglBase, local, "You", Modifier.fillMaxSize(), mirror = true)
            }
        }
        Row(Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(24.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Round(if (call.muted) Icons.Filled.MicOff else Icons.Filled.Mic, if (call.muted) "Unmute" else "Mute", Brand.Card) {
                scope.launch { call.setMuted(!call.muted) }
            }
            if (call.video) Round(if (call.cameraOff) Icons.Filled.VideocamOff else Icons.Filled.Videocam, if (call.cameraOff) "Camera on" else "Camera off", Brand.Card) {
                scope.launch { call.setCameraOff(!call.cameraOff) }
            }
            Round(Icons.Filled.CallEnd, "Leave call", Color(0xFFC62828)) { scope.launch { call.leave(); back() } }
        }
    }
}

@Composable
private fun Round(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, colour: Color, onClick: () -> Unit) {
    FilledIconButton(onClick, Modifier.size(64.dp), shape = CircleShape, colors = IconButtonDefaults.filledIconButtonColors(containerColor = colour, contentColor = Color.White)) {
        Icon(icon, label, Modifier.size(28.dp))
    }
}

@Composable
private fun VideoTile(egl: EglBase, track: VideoTrack?, name: String, modifier: Modifier, mirror: Boolean = false) {
    Surface(modifier, shape = RoundedCornerShape(16.dp), color = Brand.Card) {
        Box {
            if (track != null) {
                val context = LocalContext.current
                val renderer = remember(track) {
                    SurfaceViewRenderer(context).apply {
                        init(egl.eglBaseContext, null)
                        setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                        setEnableHardwareScaler(true)
                        setMirror(mirror)
                    }
                }
                DisposableEffect(track) {
                    track.addSink(renderer)
                    onDispose { runCatching { track.removeSink(renderer) }; renderer.release() }
                }
                AndroidView({ renderer }, Modifier.fillMaxSize())
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Avatar(name, 72.dp) }
            }
            Text(name, Modifier.align(Alignment.BottomStart).padding(10.dp), color = Color.White, fontWeight = FontWeight.SemiBold)
        }
    }
}
