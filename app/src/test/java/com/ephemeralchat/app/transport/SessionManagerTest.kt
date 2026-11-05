package com.ephemeralchat.app.transport
import com.ephemeralchat.app.crypto.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Test
import org.junit.Assert.*
import org.json.JSONObject
import java.security.*
import java.security.spec.*
import java.util.Base64

@OptIn(ExperimentalCoroutinesApi::class)
class SessionManagerTest {
 private class Keys:DailyKeys {
  private var pair:KeyPair?=null
  fun rotate(){pair=null}
  override fun dailyPair():KeyPair = pair?:KeyPairGenerator.getInstance("EC").apply{initialize(ECGenParameterSpec("secp256r1"))}.generateKeyPair().also{pair=it}
  override fun publicKeyBase64()=Base64.getEncoder().encodeToString(dailyPair().public.encoded)
  override fun decodePeerPublicKey(value:String):PublicKey=KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(value)))
 }
 private class Link:SecureTransport {
  override val events=MutableSharedFlow<MeshEvent>(extraBufferCapacity=256)
  override val errors=MutableSharedFlow<String>(extraBufferCapacity=16)
  lateinit var other:Link
  val sent=mutableListOf<String>()
  override fun sendRaw(id:String,text:String){sent+=text;other.events.tryEmit(MeshEvent.RawMessageReceived("peer",text))}
  override fun authenticateHello(id:String,payload:String)="authenticated-test-link"
  override fun verifyHello(id:String,payload:String,tag:String)=tag==authenticateHello(id,payload)
 }
 @Test fun exchangeMessagesReplayProtectionMediaAndSignals()= runTest {
  val a=Link();val b=Link();a.other=b;b.other=a
  val scope=CoroutineScope(backgroundScope.coroutineContext+UnconfinedTestDispatcher(testScheduler))
  val alice=SessionManager(a,Keys(),scope);val bob=SessionManager(b,Keys(),scope)
  a.events.emit(MeshEvent.Connected(PeerDevice("peer","Bob")))
  runCurrent();assertTrue(alice.hasSession("peer"));assertTrue(bob.hasSession("peer"))
  assertEquals(alice.fingerprintFor("peer"),bob.fingerprintFor("peer"))
  alice.sendMessage("peer","hello");runCurrent()
  assertEquals("hello",bob.history.value.single().text)
  val packet=a.sent.last();assertFalse(packet.contains("hello"))
  b.events.emit(MeshEvent.RawMessageReceived("peer",packet));runCurrent();assertEquals(1,bob.history.value.size)
  bob.sendMessage("peer","🌙","sticker");runCurrent();assertEquals("sticker",alice.history.value.last().kind)
  alice.sendMessage("peer",Base64.getEncoder().encodeToString(byteArrayOf(1,2,3)),"voice");runCurrent();assertEquals("voice",bob.history.value.last().kind)
  var received=false;val job=scope.launch {bob.signals.collect{received=it.second.getString("type")=="offer"}}
  alice.sendSignal("peer",JSONObject().put("type","offer"));runCurrent();assertTrue(received);job.cancel()
  a.events.emit(MeshEvent.Disconnected("peer"));runCurrent();assertFalse(alice.hasSession("peer"))
 }
 @Test fun rejectsInvalidHandshakeAndMalformedCiphertextWithoutKillingSession()=runTest {
  val a=Link();val b=Link();a.other=b;b.other=a
  val scope=CoroutineScope(backgroundScope.coroutineContext+UnconfinedTestDispatcher(testScheduler))
  val alice=SessionManager(a,Keys(),scope);val bob=SessionManager(b,Keys(),scope)
  b.events.emit(MeshEvent.RawMessageReceived("peer","not-json"))
  a.events.emit(MeshEvent.Connected(PeerDevice("peer","Bob")));runCurrent()
  val invalid=JSONObject().put("type","hello").put("day",SessionCrypto.today()).put("public",Keys().publicKeyBase64()).put("tag","forged")
  b.events.emit(MeshEvent.RawMessageReceived("peer",invalid.toString()));runCurrent()
  alice.sendMessage("peer","still alive");runCurrent();assertEquals("still alive",bob.history.value.single().text)
 }
 @Test fun midnightExpiresHistoryAndReplacesBothKeys()=runTest {
  val a=Link();val b=Link();a.other=b;b.other=a
  val ak=Keys();val bk=Keys();var day=SessionCrypto.today()
  val scope=CoroutineScope(backgroundScope.coroutineContext+UnconfinedTestDispatcher(testScheduler))
  val alice=SessionManager(a,ak,scope){day};val bob=SessionManager(b,bk,scope){day}
  a.events.emit(MeshEvent.Connected(PeerDevice("peer","Bob")));runCurrent()
  alice.sendMessage("peer","yesterday");runCurrent();val old=a.sent.last();val oldPrint=alice.fingerprintFor("peer")
  day=java.time.LocalDate.parse(day).plusDays(1).toString();ak.rotate();bk.rotate()
  advanceTimeBy(1001);runCurrent()
  assertTrue(alice.history.value.isEmpty());assertTrue(bob.history.value.isEmpty())
  assertNotEquals(oldPrint,alice.fingerprintFor("peer"))
  b.events.emit(MeshEvent.RawMessageReceived("peer",old));runCurrent();assertTrue(bob.history.value.isEmpty())
  alice.sendMessage("peer","today");runCurrent();assertEquals("today",bob.history.value.single().text)
 }
}
