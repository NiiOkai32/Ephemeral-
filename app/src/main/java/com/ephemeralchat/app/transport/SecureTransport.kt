package com.ephemeralchat.app.transport
import kotlinx.coroutines.flow.MutableSharedFlow
interface SecureTransport {
    val events:MutableSharedFlow<MeshEvent>
    val errors:MutableSharedFlow<String>
    fun sendRaw(id:String,text:String)
    fun authenticateHello(id:String,payload:String):String
    fun verifyHello(id:String,payload:String,tag:String):Boolean
}
