package com.ephemeralchat.app.transport

import android.content.Context
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import com.ephemeralchat.app.crypto.KeyManager
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.*
import org.json.JSONObject
import java.util.UUID
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.util.Base64

data class PeerDevice(val endpointId: String, val displayName: String)
data class PairingRequest(val endpointId: String,val name: String,val code: String)
sealed class MeshEvent {
    data class Connected(val peer: PeerDevice): MeshEvent()
    data class Disconnected(val endpointId: String): MeshEvent()
    data class RawMessageReceived(val endpointId: String,val payload: String): MeshEvent()
}
class MeshTransport(context: Context, private val keyManager: KeyManager, private val localDisplayName: String):SecureTransport {
    private val client=Nearby.getConnectionsClient(context)
    override val events=MutableSharedFlow<MeshEvent>(extraBufferCapacity=256)
    override val errors=MutableSharedFlow<String>(extraBufferCapacity=16)
    val status=MutableStateFlow("Nearby discovery is off")
    val discoveredPeers=MutableStateFlow<Map<String,PeerDevice>>(emptyMap())
    val connectedPeers=MutableStateFlow<Map<String,PeerDevice>>(emptyMap())
    val pairing=MutableStateFlow<PairingRequest?>(null)
    private val names=mutableMapOf<String,String>()
    private val transfers=mutableMapOf<String,Pair<Long,MutableMap<Int,String>>>()
    private val expected=mutableMapOf<String,Int>()
    private val main=android.os.Handler(android.os.Looper.getMainLooper())
    private var socket: WebSocket?=null
    private var invite: String?=null
    private val http=OkHttpClient.Builder().pingInterval(20,java.util.concurrent.TimeUnit.SECONDS).build()
    private val callback=object: PayloadCallback() {
        override fun onPayloadReceived(id:String,p:Payload) { p.asBytes()?.let { receive(id,String(it,Charsets.UTF_8)) } }
        override fun onPayloadTransferUpdate(id:String,u:PayloadTransferUpdate) {
            if(u.status==PayloadTransferUpdate.Status.FAILURE) errors.tryEmit("Delivery failed. Reconnect and try again.")
        }
    }
    private val lifecycle=object: ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(id:String,info:ConnectionInfo) {
            if(pairing.value!=null) { client.rejectConnection(id); return }
            names[id]=info.endpointName
            pairing.value=PairingRequest(id,info.endpointName,info.authenticationDigits)
        }
        override fun onConnectionResult(id:String,result:ConnectionResolution) {
            if(result.status.isSuccess) connected(id,names[id]?:id)
            else errors.tryEmit("Connection was declined or failed.")
        }
        override fun onDisconnected(id:String) { disconnected(id) }
    }
    fun answerPairing(accept:Boolean) {
        pairing.value?.let { if(accept) client.acceptConnection(it.endpointId,callback) else client.rejectConnection(it.endpointId) }
        pairing.value=null
    }
    private fun connected(id:String,name:String) {
        val peer=PeerDevice(id,name)
        connectedPeers.value=connectedPeers.value+(id to peer)
        events.tryEmit(MeshEvent.Connected(peer))
    }
    private fun disconnected(id:String) {
        connectedPeers.value=connectedPeers.value-id
        events.tryEmit(MeshEvent.Disconnected(id))
    }
    fun startAdvertising() {
        client.startAdvertising(localDisplayName,"com.ephemeralchat.v1",lifecycle,AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build())
            .addOnFailureListener { errors.tryEmit("Nearby advertising failed. Check Bluetooth, Wi-Fi, Location and permissions.") }
    }
    fun startDiscovery() {
        client.startDiscovery("com.ephemeralchat.v1",object:EndpointDiscoveryCallback() {
            override fun onEndpointFound(id:String,info:DiscoveredEndpointInfo) { discoveredPeers.value=discoveredPeers.value+(id to PeerDevice(id,info.endpointName)) }
            override fun onEndpointLost(id:String) { discoveredPeers.value=discoveredPeers.value-id }
        },DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()).addOnSuccessListener { status.value="Searching nearby" }.addOnFailureListener { errors.tryEmit("Discovery unavailable. Enable Bluetooth, Wi-Fi and Location.") }
    }
    fun requestConnection(id:String) { client.requestConnection(localDisplayName,id,lifecycle).addOnFailureListener { errors.tryEmit("Unable to connect. Please try again.") } }
    fun connectInternet(url:String,secret:String) {
        require(url.startsWith("wss://")) { "Use a secure wss:// relay URL" }
        require(secret.matches(Regex("[A-Za-z0-9_-]{43}"))) { "Use a generated 43-character invitation code" }
        disconnectInternet()
        invite=secret
        val room=MessageDigest.getInstance("SHA-256").digest(secret.toByteArray()).joinToString("") { "%02x".format(it) }
        status.value="Connecting to internet relay…"
        val target=url.trimEnd('/')+"/room/"+room
        socket=http.newWebSocket(Request.Builder().url(target).build(),object:WebSocketListener() {
            override fun onOpen(ws:WebSocket,r:Response) { main.post { if(ws===socket) status.value="Waiting for your contact on relay" } }
            override fun onMessage(ws:WebSocket,text:String) { main.post {
                if(ws!==socket) return@post
                runCatching {
                    val j=JSONObject(text)
                    when(j.getString("type")) {
                        "peer" -> connected("internet","Internet contact")
                        "left" -> disconnected("internet")
                        "data" -> receive("internet",j.getString("data"))
                    }
                }.onFailure { errors.tryEmit("Invalid relay response") }
            } }
            override fun onFailure(ws:WebSocket,t:Throwable,r:Response?) { main.post { if(ws===socket) { disconnected("internet"); status.value="Relay disconnected"; errors.tryEmit("Internet connection failed. Check relay URL and connectivity.") } } }
            override fun onClosed(ws:WebSocket,c:Int,r:String) { main.post { if(ws===socket) { disconnected("internet"); status.value="Relay disconnected" } } }
        })
    }
    fun disconnectInternet() { socket?.close(1000,"Leaving"); socket=null; disconnected("internet"); invite=null }
    override fun authenticateHello(id:String,payload:String):String {
        if(id!="internet") return ""
        val mac=Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec((invite?:error("No invite")).toByteArray(),"HmacSHA256"))
        return Base64.getEncoder().encodeToString(mac.doFinal(payload.toByteArray()))
    }
    override fun verifyHello(id:String,payload:String,tag:String):Boolean = MessageDigest.isEqual(authenticateHello(id,payload).toByteArray(),tag.toByteArray())
    override fun sendRaw(id:String,text:String) {
        if(id=="internet") {
            check(socket?.send(JSONObject().put("type","data").put("data",text).toString())==true) { "Relay disconnected" }
            return
        }
        val parts=text.chunked(24000)
        require(parts.size<=256)
        val token=UUID.randomUUID().toString()
        parts.forEachIndexed { n,part ->
            val frame=JSONObject().put("id",token).put("n",n).put("count",parts.size).put("data",part).toString()
            client.sendPayload(id,Payload.fromBytes(frame.toByteArray())).addOnFailureListener { errors.tryEmit("Message delivery failed") }
        }
    }
    private fun receive(id:String,text:String) {
        if(id=="internet") { if(text.length<=6500000) events.tryEmit(MeshEvent.RawMessageReceived(id,text)); return }
        runCatching {
            val j=JSONObject(text); val count=j.getInt("count"); val n=j.getInt("n")
            require(count in 1..256 && n in 0 until count)
            val token=id+":"+j.getString("id"); val now=System.currentTimeMillis()
            transfers.keys.filter { now-transfers.getValue(it).first>60000 }.toList().forEach { transfers.remove(it); expected.remove(it) }
            require(token in transfers || transfers.size<8)
            val entry=transfers.getOrPut(token) { expected[token]=count; now to mutableMapOf() }
            require(expected[token]==count)
            val part=j.getString("data"); require(part.length<=24000)
            entry.second[n]=part
            if(entry.second.size==count) {
                val payload=(0 until count).joinToString("") { entry.second.getValue(it) }
                transfers.remove(token);expected.remove(token)
                events.tryEmit(MeshEvent.RawMessageReceived(id,payload))
            }
        }.onFailure { errors.tryEmit("Rejected malformed transfer") }
    }
    fun stopAll() { client.stopAdvertising();client.stopDiscovery();client.stopAllEndpoints();disconnectInternet() }
}
