package com.ephemeralchat.app.transport

import com.ephemeralchat.app.crypto.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import java.util.UUID
import java.security.MessageDigest
import javax.crypto.spec.SecretKeySpec

data class ChatMessage(val id:String,val endpointId:String,val senderName:String,val text:String,val timestamp:Long,val isMine:Boolean,val kind:String="text",val day:String=SessionCrypto.today())
data class SecureSession(val day:String,val key:SecretKeySpec,val fingerprint:String,val peerNonce:String)
class SessionManager(private val transport:SecureTransport,private val keys:DailyKeys,private val scope:CoroutineScope,private val today:()->String=SessionCrypto::today) {
    private val sessions=mutableMapOf<String,SecureSession>()
    private val connectionNonces=mutableMapOf<String,String>()
    private val seen=LinkedHashSet<String>()
    private var activeDay=today()
    val history=MutableStateFlow<List<ChatMessage>>(emptyList())
    val ready=MutableStateFlow<Map<String,String>>(emptyMap())
    val signals=MutableSharedFlow<Pair<String,JSONObject>>(extraBufferCapacity=128)
    init {
        transport.events.onEach { event ->
            when(event) {
                is MeshEvent.Connected -> hello(event.peer.endpointId)
                is MeshEvent.Disconnected -> { sessions.remove(event.endpointId);connectionNonces.remove(event.endpointId);ready.value=ready.value-event.endpointId }
                is MeshEvent.RawMessageReceived -> runCatching { receive(event.endpointId,JSONObject(event.payload)) }.onFailure { transport.errors.tryEmit("Rejected invalid or expired encrypted packet") }
            }
        }.launchIn(scope)
        scope.launch { while(isActive) { delay(1000); val day=today()
            if(activeDay!=day) { keys.dailyPair();seen.clear();activeDay=day }
            history.value=history.value.filter { it.day==day }
            sessions.filterValues { it.day!=day }.keys.toList().forEach { id -> sessions.remove(id);ready.value=ready.value-id; hello(id) }
        } }
    }
    private fun hello(id:String) {
        val day=today(); val pub=keys.publicKeyBase64()
        val nonce=connectionNonces.getOrPut(id) { UUID.randomUUID().toString() }
        transport.sendRaw(id,JSONObject().put("type","hello").put("day",day).put("public",pub).put("nonce",nonce).put("tag",transport.authenticateHello(id,"$day|$pub|$nonce")).toString())
    }
    private fun receive(id:String,j:JSONObject) {
        val day=j.getString("day");require(day==today())
        if(j.getString("type")=="hello") {
            val pub=j.getString("public");val peerNonce=j.getString("nonce")
            require(peerNonce.length==36)
            require(transport.verifyHello(id,"$day|$pub|$peerNonce",j.optString("tag")))
            val local=keys.dailyPair();val peer=keys.decodePeerPublicKey(pub)
            val fingerprint=MessageDigest.getInstance("SHA-256").digest(listOf(keys.publicKeyBase64(),pub).sorted().joinToString("|").toByteArray()).joinToString("") { "%02X".format(it) }.chunked(4).joinToString(" ")
            val previous=sessions[id]
            if(previous!=null) require(previous.fingerprint==fingerprint && previous.peerNonce==peerNonce) { "Key changed during session" }
            val localNonce=connectionNonces.getOrPut(id) { UUID.randomUUID().toString() }
            val connectionContext=listOf(localNonce,peerNonce).sorted().joinToString("|")
            sessions[id]=SecureSession(day,SessionCrypto.deriveSharedKey(local.private,peer,local.public,day,connectionContext),fingerprint,peerNonce)
            ready.value=ready.value+(id to fingerprint)
            if(previous==null) hello(id)
            return
        }
        val session=sessions[id]?:return
        val token=j.getString("id"); require(token.length==36)
        if(token in seen) return
        val body=JSONObject(String(SessionCrypto.decrypt(session.key,j.getString("body"),"v1|$day|$token"),Charsets.UTF_8))
        require(seen.size<10000) { "Session message limit reached" };seen.add(token)
        when(body.getString("kind")) {
            "text","sticker","voice" -> {
                val kind=body.getString("kind");val text=body.getString("text")
                require((kind=="text" && text.length in 1..4000)||(kind=="sticker" && text in stickers)||(kind=="voice" && text.length in 1..2800000))
                append(ChatMessage(token,id,"Contact",text,System.currentTimeMillis(),false,kind,day))
            }
            "signal" -> signals.emitSafe(id to body.getJSONObject("signal"))
            else -> error("Unsupported message")
        }
    }
    private fun <T> MutableSharedFlow<T>.emitSafe(value:T) { tryEmit(value) }
    private fun append(message:ChatMessage) {
        val list=(history.value+message).takeLast(200).toMutableList()
        var size=list.sumOf { it.text.length }
        while(size>8_000_000 && list.size>1) size-=list.removeAt(0).text.length
        history.value=list
    }
    fun fingerprintFor(id:String)=ready.value[id]
    fun hasSession(id:String)=sessions[id]?.day==today()
    private fun send(id:String,body:JSONObject):String {
        val session=sessions[id]?:error("Wait for daily key exchange")
        check(session.day==today()) { "Daily keys are rotating. Please retry." }
        val token=UUID.randomUUID().toString()
        val encrypted=SessionCrypto.encrypt(session.key,body.toString().toByteArray(),"v1|${session.day}|$token")
        transport.sendRaw(id,JSONObject().put("type","packet").put("day",session.day).put("id",token).put("body",encrypted).toString())
        return token
    }
    fun sendMessage(id:String,text:String,kind:String="text") {
        require((kind=="text" && text.length in 1..4000)||(kind=="sticker" && text in stickers)||(kind=="voice" && text.length<=2800000))
        val token=send(id,JSONObject().put("kind",kind).put("text",text))
        append(ChatMessage(token,id,"You",text,System.currentTimeMillis(),true,kind,today()))
    }
    fun sendSignal(id:String,signal:JSONObject) { send(id,JSONObject().put("kind","signal").put("signal",signal)) }
    fun clearHistory() { history.value=emptyList() }
    companion object { val stickers=listOf("🌙","✨","💜","🔥","👋","🎉","🫶","😂","🥹","🚀","💯","🌻") }
}
