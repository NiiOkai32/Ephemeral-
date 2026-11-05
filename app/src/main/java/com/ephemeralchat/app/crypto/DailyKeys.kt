package com.ephemeralchat.app.crypto
import java.security.KeyPair
import java.security.PublicKey
interface DailyKeys {
    fun dailyPair():KeyPair
    fun publicKeyBase64():String
    fun decodePeerPublicKey(value:String):PublicKey
}
