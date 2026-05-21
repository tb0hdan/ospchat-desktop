package com.ospchat.desktop.media

import com.ospchat.shared.media.AudioCallSession
import com.ospchat.shared.media.AudioCallSessionFactory
import com.ospchat.shared.util.Log
import dev.onvoid.webrtc.CreateSessionDescriptionObserver
import dev.onvoid.webrtc.PeerConnectionFactory
import dev.onvoid.webrtc.PeerConnectionObserver
import dev.onvoid.webrtc.RTCAnswerOptions
import dev.onvoid.webrtc.RTCConfiguration
import dev.onvoid.webrtc.RTCIceCandidate
import dev.onvoid.webrtc.RTCIceConnectionState
import dev.onvoid.webrtc.RTCOfferOptions
import dev.onvoid.webrtc.RTCPeerConnection
import dev.onvoid.webrtc.RTCPeerConnectionState
import dev.onvoid.webrtc.RTCSdpType
import dev.onvoid.webrtc.RTCSessionDescription
import dev.onvoid.webrtc.SetSessionDescriptionObserver
import dev.onvoid.webrtc.media.audio.AudioOptions
import dev.onvoid.webrtc.media.audio.AudioTrack
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Desktop JVM actual of [AudioCallSession], backed by `dev.onvoid.webrtc`
 * (libwebrtc JNI bindings). Audio-only — exactly one local audio track is
 * added at construction time. ICE servers are intentionally empty: OSPChat
 * is LAN-only TOFU, so we gather host candidates only.
 *
 * The native PeerConnection callbacks run on libwebrtc's signaling thread.
 * State and ICE candidates are republished via Flows so the rest of the app
 * stays on Kotlin coroutines.
 */
class JvmAudioCallSession(
    factory: PeerConnectionFactory,
) : AudioCallSession {
    private val _state = MutableStateFlow(AudioCallSession.State.NEW)
    override val state: StateFlow<AudioCallSession.State> = _state.asStateFlow()

    // `replay = 64` (not `extraBufferCapacity`) because libwebrtc's
    // signaling thread starts firing `onIceCandidate` the moment
    // `setLocalDescription` returns — which is *inside* `createOffer` /
    // `acceptOffer`, well before `CallRepository.bindSession` has scheduled
    // its `scope.launch { collect { … } }`. With `replay = 0`, a
    // `tryEmit` against a flow with zero subscribers is silently
    // discarded (the `extraBufferCapacity` buffer only kicks in for
    // existing slow subscribers); the first dozen-ish host candidates
    // get dropped on the floor and one side ends up with no usable ICE.
    // `replay = 64` preserves emissions for any future subscriber and
    // is more than enough headroom for LAN ICE gathering (typically
    // <10 candidates per session). `DROP_OLDEST` keeps tryEmit
    // non-suspending on the signaling thread.
    private val iceFlow =
        MutableSharedFlow<AudioCallSession.IceCandidate>(
            replay = 64,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    override val localIceCandidates: Flow<AudioCallSession.IceCandidate> = iceFlow.asSharedFlow()

    private val closed = AtomicBoolean(false)

    private val audioSource = factory.createAudioSource(AudioOptions())
    private val audioTrack: AudioTrack = factory.createAudioTrack("ospchat-audio", audioSource)

    private val peer: RTCPeerConnection =
        factory.createPeerConnection(
            RTCConfiguration().apply {
                // iceServers stays empty — LAN host candidates only.
            },
            object : PeerConnectionObserver {
                override fun onIceCandidate(candidate: RTCIceCandidate) {
                    iceFlow.tryEmit(
                        AudioCallSession.IceCandidate(
                            sdpMid = candidate.sdpMid,
                            sdpMLineIndex = candidate.sdpMLineIndex,
                            candidate = candidate.sdp,
                        ),
                    )
                }

                override fun onConnectionChange(newState: RTCPeerConnectionState) {
                    _state.value =
                        when (newState) {
                            RTCPeerConnectionState.NEW -> AudioCallSession.State.NEW
                            RTCPeerConnectionState.CONNECTING -> AudioCallSession.State.NEGOTIATING
                            RTCPeerConnectionState.CONNECTED -> AudioCallSession.State.CONNECTED
                            RTCPeerConnectionState.DISCONNECTED -> AudioCallSession.State.NEGOTIATING
                            RTCPeerConnectionState.FAILED -> AudioCallSession.State.FAILED
                            RTCPeerConnectionState.CLOSED -> AudioCallSession.State.CLOSED
                        }
                }

                override fun onIceConnectionChange(state: RTCIceConnectionState) {
                    // RTCPeerConnectionState is the authoritative state; this
                    // is just a log breadcrumb for connectivity debugging.
                    Log.d(TAG, "ICE connection state: $state")
                }
            },
        )

    init {
        peer.addTrack(audioTrack, listOf("ospchat-stream"))
    }

    override suspend fun createOffer(): String {
        val sdp = awaitSdp { observer -> peer.createOffer(RTCOfferOptions(), observer) }
        awaitSetSdp { observer -> peer.setLocalDescription(sdp, observer) }
        _state.value = AudioCallSession.State.NEGOTIATING
        return sdp.sdp
    }

    override suspend fun acceptOffer(remoteSdp: String): String {
        val remote = RTCSessionDescription(RTCSdpType.OFFER, remoteSdp)
        awaitSetSdp { observer -> peer.setRemoteDescription(remote, observer) }
        val answer = awaitSdp { observer -> peer.createAnswer(RTCAnswerOptions(), observer) }
        awaitSetSdp { observer -> peer.setLocalDescription(answer, observer) }
        _state.value = AudioCallSession.State.NEGOTIATING
        return answer.sdp
    }

    override suspend fun setRemoteAnswer(sdp: String) {
        val remote = RTCSessionDescription(RTCSdpType.ANSWER, sdp)
        awaitSetSdp { observer -> peer.setRemoteDescription(remote, observer) }
    }

    override suspend fun addRemoteIce(candidate: AudioCallSession.IceCandidate) {
        peer.addIceCandidate(
            RTCIceCandidate(
                candidate.sdpMid,
                candidate.sdpMLineIndex,
                candidate.candidate,
            ),
        )
    }

    override fun setMuted(muted: Boolean) {
        audioTrack.isEnabled = !muted
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // `RTCPeerConnection` in dev.onvoid.webrtc extends plain
        // `NativeObject`, not `DisposableNativeObject` — `close()` is both
        // shutdown and native-resource release for this library (no
        // separate `dispose()` exists). This contrasts with Android's
        // stream-webrtc fork where the two are separate calls. Audio
        // source isn't disposed here either; its native lifetime is bound
        // to the track that owns it.
        runCatching { peer.close() }.onFailure { Log.w(TAG, "peer.close failed", it) }
        runCatching { audioTrack.dispose() }
        _state.value = AudioCallSession.State.CLOSED
    }

    private suspend fun awaitSdp(start: (CreateSessionDescriptionObserver) -> Unit): RTCSessionDescription =
        suspendCancellableCoroutine { cont ->
            start(
                object : CreateSessionDescriptionObserver {
                    override fun onSuccess(description: RTCSessionDescription) {
                        cont.resume(description)
                    }

                    override fun onFailure(error: String) {
                        cont.resumeWithException(IllegalStateException("WebRTC SDP failure: $error"))
                    }
                },
            )
        }

    private suspend fun awaitSetSdp(start: (SetSessionDescriptionObserver) -> Unit): Unit =
        suspendCancellableCoroutine { cont ->
            start(
                object : SetSessionDescriptionObserver {
                    override fun onSuccess() {
                        cont.resume(Unit)
                    }

                    override fun onFailure(error: String) {
                        cont.resumeWithException(IllegalStateException("WebRTC setSDP failure: $error"))
                    }
                },
            )
        }

    private companion object {
        const val TAG = "JvmAudioCallSession"
    }
}

/**
 * Factory holding the single shared [PeerConnectionFactory] (heavy — owns the
 * signaling thread, worker thread, network thread, and the audio device
 * module). One factory per process; cheap [JvmAudioCallSession]s per call.
 */
class JvmAudioCallSessionFactory : AudioCallSessionFactory {
    // Lazy so the native lib load (~5 MB native binary) happens on first call
    // rather than at app startup. The first call will pay the load cost.
    private val factory: PeerConnectionFactory by lazy { PeerConnectionFactory() }

    override fun create(): AudioCallSession = JvmAudioCallSession(factory)

    fun shutdown() {
        runCatching { factory.dispose() }
    }
}
