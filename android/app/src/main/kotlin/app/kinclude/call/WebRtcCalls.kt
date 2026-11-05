package app.kinclude.call

import android.content.Context
import android.media.AudioManager
import app.kinclude.core.CallEnvironment
import app.kinclude.core.IceServer
import app.kinclude.core.LocalMedia
import app.kinclude.core.PeerConnectionPort
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** What a remote tile shows: their video, when they send any. */
class RemoteMedia(val video: VideoTrack?)

/**
 * `CallEnvironment` over Google's WebRTC (the maintained
 * `io.github.webrtc-sdk:android` build KithMoot pins). `RoomCall` in
 * `android/core` decides who to connect to and when; this only makes the
 * connections and the camera and microphone. Initialisation follows
 * KithMoot's `WebRtcEngine`: WebRTC's own echo canceller and noise
 * suppressor rather than the handset's.
 */
class WebRtcCalls(private val context: Context) : CallEnvironment {
    val eglBase: EglBase = EglBase.create()

    private val factory: PeerConnectionFactory by lazy {
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context.applicationContext).createInitializationOptions())
        val audio = JavaAudioDeviceModule.builder(context.applicationContext)
            .setUseHardwareAcousticEchoCanceler(false)
            .setUseHardwareNoiseSuppressor(false)
            .createAudioDeviceModule()
        PeerConnectionFactory.builder()
            .setAudioDeviceModule(audio)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
    }

    /** The camera and microphone of the call in progress, for the self-view. */
    @Volatile var current: Local? = null
        private set

    override suspend fun startLocal(video: Boolean): LocalMedia = Local(video).also { current = it }

    override fun createPeerConnection(iceServers: List<IceServer>, local: LocalMedia): PeerConnectionPort =
        Peer(iceServers, local as Local)

    inner class Local(withVideo: Boolean) : LocalMedia {
        private val audioSource = factory.createAudioSource(MediaConstraints())
        val audio: AudioTrack = factory.createAudioTrack("kinclude-mic", audioSource)
        private var capturer: CameraVideoCapturer? = null
        private var helper: SurfaceTextureHelper? = null
        val video: VideoTrack?
        private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        init {
            video = if (withVideo) camera() else null
            @Suppress("DEPRECATION")
            run {
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                audioManager.isSpeakerphoneOn = withVideo
            }
        }

        private fun camera(): VideoTrack? {
            val enumerator = Camera2Enumerator(context)
            val name = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) } ?: enumerator.deviceNames.firstOrNull() ?: return null
            val cap = enumerator.createCapturer(name, null) ?: return null
            val h = SurfaceTextureHelper.create("kinclude-camera", eglBase.eglBaseContext)
            val source = factory.createVideoSource(false)
            cap.initialize(h, context, source.capturerObserver)
            cap.startCapture(640, 480, 24)
            capturer = cap
            helper = h
            return factory.createVideoTrack("kinclude-camera", source)
        }

        override fun setMuted(muted: Boolean) { audio.setEnabled(!muted) }
        override fun setCameraOff(off: Boolean) { video?.setEnabled(!off) }

        override fun stop() {
            if (current === this) current = null
            runCatching { capturer?.stopCapture() }
            capturer?.dispose(); helper?.dispose()
            video?.dispose(); audio.dispose(); audioSource.dispose()
            @Suppress("DEPRECATION")
            run { audioManager.isSpeakerphoneOn = false; audioManager.mode = AudioManager.MODE_NORMAL }
        }
    }

    private inner class Peer(iceServers: List<IceServer>, local: Local) : PeerConnectionPort {
        override var onIceCandidate: ((String) -> Unit)? = null
        override var onRemoteMedia: ((Any) -> Unit)? = null
        override var onStateChange: (() -> Unit)? = null
        @Volatile private var remoteSet = false

        private val pc: PeerConnection = requireNotNull(factory.createPeerConnection(
            PeerConnection.RTCConfiguration(iceServers.map { s ->
                PeerConnection.IceServer.builder(s.urls).apply {
                    s.username?.let { setUsername(it) }
                    s.credential?.let { setPassword(it) }
                }.createIceServer()
            }).apply { sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN },
            object : PeerConnection.Observer {
                override fun onSignalingChange(state: PeerConnection.SignalingState?) = Unit
                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) = Unit
                override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) = Unit
                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
                override fun onAddStream(stream: MediaStream?) = Unit
                override fun onRemoveStream(stream: MediaStream?) = Unit
                override fun onDataChannel(channel: DataChannel?) = Unit
                override fun onRenegotiationNeeded() = Unit
                override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) = Unit
                override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) { onStateChange?.invoke() }
                override fun onIceCandidate(candidate: IceCandidate?) {
                    val c = candidate ?: return
                    // The browser's RTCIceCandidateInit, so a phone and the web
                    // harness read each other's candidates.
                    onIceCandidate?.invoke(buildJsonObject {
                        put("candidate", c.sdp); put("sdpMid", c.sdpMid); put("sdpMLineIndex", c.sdpMLineIndex)
                    }.toString())
                }
                override fun onTrack(transceiver: RtpTransceiver?) {
                    val track = transceiver?.receiver?.track()
                    onRemoteMedia?.invoke(RemoteMedia(track as? VideoTrack))
                }
            },
        ))

        init {
            pc.addTrack(local.audio, listOf("kinclude"))
            local.video?.let { pc.addTrack(it, listOf("kinclude")) }
        }

        private suspend fun create(offer: Boolean): String = suspendCancellableCoroutine { k ->
            val observer = object : SdpObserver {
                override fun onCreateSuccess(d: SessionDescription?) { if (k.isActive) k.resume(d!!.description) }
                override fun onCreateFailure(error: String?) { if (k.isActive) k.resumeWithException(IllegalStateException(error ?: "create failed")) }
                override fun onSetSuccess() = Unit
                override fun onSetFailure(error: String?) = Unit
            }
            if (offer) pc.createOffer(observer, MediaConstraints()) else pc.createAnswer(observer, MediaConstraints())
        }

        private suspend fun set(local: Boolean, type: String, sdp: String) = suspendCancellableCoroutine<Unit> { k ->
            val observer = object : SdpObserver {
                override fun onSetSuccess() { if (k.isActive) k.resume(Unit) }
                override fun onSetFailure(error: String?) { if (k.isActive) k.resumeWithException(IllegalStateException(error ?: "set failed")) }
                override fun onCreateSuccess(d: SessionDescription?) = Unit
                override fun onCreateFailure(error: String?) = Unit
            }
            val desc = SessionDescription(SessionDescription.Type.fromCanonicalForm(type), sdp)
            if (local) pc.setLocalDescription(observer, desc) else pc.setRemoteDescription(observer, desc)
        }

        override suspend fun createOffer() = create(offer = true)
        override suspend fun createAnswer() = create(offer = false)
        override suspend fun setLocal(type: String, sdp: String) = set(true, type, sdp)
        override suspend fun setRemote(type: String, sdp: String) { set(false, type, sdp); remoteSet = true }

        override suspend fun addIceCandidate(json: String) {
            val o = Json.parseToJsonElement(json).jsonObject
            pc.addIceCandidate(IceCandidate(o["sdpMid"]?.jsonPrimitive?.content, o["sdpMLineIndex"]?.jsonPrimitive?.int ?: 0, o.getValue("candidate").jsonPrimitive.content))
        }

        override val hasRemote: Boolean get() = remoteSet
        override val state: String get() = pc.connectionState().name.lowercase()
        override fun close() { runCatching { pc.close(); pc.dispose() } }
    }

    companion object {
        fun ice(line: String): IceServer = parseIceLine(line)
    }
}
