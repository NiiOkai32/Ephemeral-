package com.ephemeralchat.app

import android.app.Application
import android.os.Build
import com.ephemeralchat.app.crypto.KeyManager
import com.ephemeralchat.app.transport.MeshTransport
import com.ephemeralchat.app.transport.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

class EphemeralChatApp : Application() {

    lateinit var keyManager: KeyManager
        private set
    lateinit var meshTransport: MeshTransport
        private set
    lateinit var sessionManager: SessionManager
        private set

    lateinit var calls: com.ephemeralchat.app.call.CallController
        private set

    private val appScope = CoroutineScope(SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        cacheDir.listFiles()?.filter { it.name.startsWith("voice-") }?.forEach { it.delete() }
        keyManager = KeyManager(this)
        val deviceLabel = "${Build.MODEL}-${(1000..9999).random()}"
        meshTransport = MeshTransport(this, keyManager, deviceLabel)
        sessionManager = SessionManager(meshTransport, keyManager, appScope)
        calls = com.ephemeralchat.app.call.CallController(this, sessionManager, appScope)
    }
}
