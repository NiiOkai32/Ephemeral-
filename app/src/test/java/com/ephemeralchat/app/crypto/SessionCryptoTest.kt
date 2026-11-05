package com.ephemeralchat.app.crypto
import org.junit.Test
import org.junit.Assert.*
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
class SessionCryptoTest {
 private fun pair()=KeyPairGenerator.getInstance("EC").apply {initialize(ECGenParameterSpec("secp256r1"))}.generateKeyPair()
 @Test fun bothDevicesDeriveSameDailyKey() {
  val a=pair();val b=pair();val day="2026-11-05"
  val ak=SessionCrypto.deriveSharedKey(a.private,b.public,a.public,day)
  val bk=SessionCrypto.deriveSharedKey(b.private,a.public,b.public,day)
  assertArrayEquals(ak.encoded,bk.encoded)
  val encrypted=SessionCrypto.encrypt(ak,"hello".toByteArray(),"$day|id")
  assertEquals("hello",String(SessionCrypto.decrypt(bk,encrypted,"$day|id")))
  assertFalse(encrypted==SessionCrypto.encrypt(ak,"hello".toByteArray(),"$day|id"))
 }
 @Test fun rejectsWrongDeviceAndDayAndMetadataAndTampering() {
  val a=pair();val b=pair();val c=pair()
  val key=SessionCrypto.deriveSharedKey(a.private,b.public,a.public,"2026-11-05")
  val data=SessionCrypto.encrypt(key,"private".toByteArray(),"context")
  fun fails(block:()->Unit){try{block();fail("Expected authentication failure")}catch(e:java.security.GeneralSecurityException){}}
  fails{SessionCrypto.decrypt(SessionCrypto.deriveSharedKey(c.private,b.public,c.public,"2026-11-05"),data,"context")}
  fails{SessionCrypto.decrypt(SessionCrypto.deriveSharedKey(a.private,b.public,a.public,"2026-11-06"),data,"context")}
  fails{SessionCrypto.decrypt(key,data,"different")}
  val raw=java.util.Base64.getDecoder().decode(data);raw[raw.lastIndex]=(raw.last().toInt() xor 1).toByte()
  fails{SessionCrypto.decrypt(key,java.util.Base64.getEncoder().encodeToString(raw),"context")}
 }
 @Test(expected=IllegalArgumentException::class) fun rejectsTruncatedPacket(){
  SessionCrypto.decrypt(javax.crypto.spec.SecretKeySpec(ByteArray(32),"AES"),"AA==","context")
 }
}
