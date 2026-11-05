package com.ephemeralchat.app.call

import android.content.Context
import com.ephemeralchat.app.transport.SessionManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import org.webrtc.*
import java.util.UUID

data class CallState(val peer:String="",val id:String="",val phase:String="idle",val video:Boolean=false,val muted:Boolean=false,val cameraOff:Boolean=false)
/** Foreground-only WebRTC calls. Signaling travels through the daily encrypted channel.
 * No camera or microphone is opened until a user starts or accepts a call. */
class CallController(private val context:Context,private val sessions:SessionManager,private val scope:CoroutineScope) {
    val state=MutableStateFlow(CallState())
    val error=MutableStateFlow<String?>(null)
    val egl=EglBase.create()
    private val factory:PeerConnectionFactory
    private var pc:PeerConnection?=null
    private var capturer:CameraVideoCapturer?=null
    private var helper:SurfaceTextureHelper?=null
    private var audioSource:AudioSource?=null
    private var videoSource:VideoSource?=null
    var localVideo:VideoTrack?=null;private set
    val remoteVideo=MutableStateFlow<VideoTrack?>(null)
    private var audio:AudioTrack?=null
    private var pendingOffer:String?=null
    private var remoteReady=false
    private val candidates=mutableListOf<IceCandidate>()
    private var timeout:Job?=null
    private val audioManager=context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
    init {
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
        factory=PeerConnectionFactory.builder().setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext,true,true)).setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext)).createPeerConnectionFactory()
        sessions.signals.onEach { (peer,j) -> runCatching { receive(peer,j) }.onFailure { fail("Call signaling failed") } }.launchIn(scope)
    }
    fun start(peer:String,video:Boolean) {
        if(state.value.phase!="idle") return
        state.value=CallState(peer,UUID.randomUUID().toString(),"calling",video)
        runCatching { createPeer();pc!!.createOffer(sdpObserver { sdp -> pc!!.setLocalDescription(sdpObserver(),sdp);send("offer",JSONObject().put("sdp",sdp.description).put("video",video)) },MediaConstraints()) }.onFailure { fail("Microphone or camera unavailable") }
        armTimeout()
    }
    private fun armTimeout() { timeout?.cancel();timeout=scope.launch { delay(45000);if(state.value.phase!="connected") { error.value="Call timed out"; hangup() } } }
    private fun send(type:String,j:JSONObject=JSONObject()) { sessions.sendSignal(state.value.peer,j.put("type",type).put("call",state.value.id)) }
    private fun receive(peer:String,j:JSONObject) {
        val type=j.getString("type");val id=j.getString("call")
        if(type=="offer") {
            if(state.value.phase!="idle") { sessions.sendSignal(peer,JSONObject().put("type","busy").put("call",id));return }
            require(j.getString("sdp").length<100000)
            pendingOffer=j.getString("sdp");state.value=CallState(peer,id,"incoming",j.getBoolean("video"));armTimeout();return
        }
        if(peer!=state.value.peer||id!=state.value.id) return
        when(type) {
            "answer" -> pc?.setRemoteDescription(object:SdpObserver {
                override fun onSetSuccess() { scope.launch {remoteReady=true;flushCandidates()} }
                override fun onSetFailure(e:String?) {scope.launch{fail("Invalid call answer")}}
                override fun onCreateSuccess(s:SessionDescription?) {}
                override fun onCreateFailure(e:String?) {}
            },SessionDescription(SessionDescription.Type.ANSWER,j.getString("sdp")))
            "ice" -> {
                val candidate=IceCandidate(j.optString("mid"),j.getInt("line"),j.getString("candidate"))
                if(remoteReady) pc?.addIceCandidate(candidate) else if(candidates.size<128) candidates.add(candidate)
            }
            "end","busy" -> { if(type=="busy") error.value="Contact is busy";close() }
        }
    }
    fun accept() {
        if(state.value.phase!="incoming") return
        runCatching {
            createPeer();state.value=state.value.copy(phase="connecting")
            pc!!.setRemoteDescription(object:SdpObserver {
                override fun onSetSuccess() { scope.launch { remoteReady=true;flushCandidates();pc!!.createAnswer(sdpObserver { sdp -> pc!!.setLocalDescription(sdpObserver(),sdp);send("answer",JSONObject().put("sdp",sdp.description)) },MediaConstraints()) } }
                override fun onSetFailure(e:String?) { scope.launch { fail("Unable to accept call") } }
                override fun onCreateSuccess(s:SessionDescription?) {}
                override fun onCreateFailure(e:String?) {}
            },SessionDescription(SessionDescription.Type.OFFER,pendingOffer!!))
        }.onFailure { fail("Allow microphone and camera permissions to accept") }
    }
    private fun flushCandidates() { candidates.forEach { pc?.addIceCandidate(it) };candidates.clear() }
    private fun createPeer() {
        audioManager.mode=android.media.AudioManager.MODE_IN_COMMUNICATION
        @Suppress("DEPRECATION")
        audioManager.isSpeakerphoneOn=state.value.video
        val prefs=context.getSharedPreferences("settings",Context.MODE_PRIVATE)
        val servers=mutableListOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
        val turn=prefs.getString("turn","").orEmpty()
        if(turn.startsWith("turn:")||turn.startsWith("turns:")) servers+=PeerConnection.IceServer.builder(turn).setUsername(prefs.getString("turnUser","")).setPassword(com.ephemeralchat.app.ui.CallCredentials.password).createIceServer()
        val config=PeerConnection.RTCConfiguration(servers).apply { sdpSemantics=PeerConnection.SdpSemantics.UNIFIED_PLAN }
        pc=factory.createPeerConnection(config,object:PeerConnection.Observer {
            override fun onIceCandidate(c:IceCandidate) { scope.launch { runCatching { send("ice",JSONObject().put("mid",c.sdpMid).put("line",c.sdpMLineIndex).put("candidate",c.sdp)) } } }
            override fun onConnectionChange(s:PeerConnection.PeerConnectionState) { scope.launch {
                when(s) {
                    PeerConnection.PeerConnectionState.CONNECTED -> { state.value=state.value.copy(phase="connected");timeout?.cancel() }
                    PeerConnection.PeerConnectionState.FAILED -> fail("Call connection failed. A TURN server may be needed.")
                    else -> {}
                }
            } }
            override fun onAddTrack(r:RtpReceiver,streams:Array<out MediaStream>) { (r.track() as? VideoTrack)?.let { remoteVideo.value=it } }
            override fun onSignalingChange(s:PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(s:PeerConnection.IceConnectionState) {}
            override fun onIceConnectionReceivingChange(v:Boolean) {}
            override fun onIceGatheringChange(s:PeerConnection.IceGatheringState) {}
            override fun onIceCandidatesRemoved(c:Array<out IceCandidate>) {}
            override fun onAddStream(s:MediaStream) {}
            override fun onRemoveStream(s:MediaStream) {}
            override fun onDataChannel(c:DataChannel) {}
            override fun onRenegotiationNeeded() {}
        })?:error("WebRTC unavailable")
        audioSource=factory.createAudioSource(MediaConstraints())
        audio=factory.createAudioTrack("audio",audioSource)
        pc!!.addTrack(audio,listOf("ephemeral"))
        if(state.value.video) {
            val enumerator=Camera2Enumerator(context)
            val name=enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }?:enumerator.deviceNames.first()
            capturer=enumerator.createCapturer(name,null)
            videoSource=factory.createVideoSource(false)
            helper=SurfaceTextureHelper.create("Camera",egl.eglBaseContext)
            capturer!!.initialize(helper,context,videoSource!!.capturerObserver)
            capturer!!.startCapture(640,480,24)
            localVideo=factory.createVideoTrack("video",videoSource)
            pc!!.addTrack(localVideo,listOf("ephemeral"))
        }
    }
    private fun sdpObserver(onCreated:(SessionDescription)->Unit={}):SdpObserver=object:SdpObserver {
        override fun onCreateSuccess(s:SessionDescription) { scope.launch { runCatching { onCreated(s) }.onFailure { fail("Call negotiation failed") } } }
        override fun onSetSuccess() {}
        override fun onCreateFailure(e:String?) { scope.launch { fail("Call negotiation failed") } }
        override fun onSetFailure(e:String?) { scope.launch { fail("Call negotiation failed") } }
    }
    fun toggleMute() { state.value=state.value.copy(muted=!state.value.muted);audio?.setEnabled(!state.value.muted) }
    fun toggleCamera() { state.value=state.value.copy(cameraOff=!state.value.cameraOff);localVideo?.setEnabled(!state.value.cameraOff) }
    fun hangup() { runCatching { if(state.value.phase!="idle") send("end") };close() }
    private fun fail(message:String) { error.value=message;hangup() }
    private fun close() {
        timeout?.cancel();pc?.close();pc?.dispose();pc=null
        runCatching { capturer?.stopCapture() };capturer?.dispose();capturer=null
        localVideo?.dispose();localVideo=null;remoteVideo.value=null
        videoSource?.dispose();videoSource=null;helper?.dispose();helper=null
        audio?.dispose();audio=null;audioSource?.dispose();audioSource=null
        audioManager.mode=android.media.AudioManager.MODE_NORMAL
        @Suppress("DEPRECATION")
        audioManager.isSpeakerphoneOn=false
        remoteReady=false;candidates.clear();pendingOffer=null;state.value=CallState()
    }
}
