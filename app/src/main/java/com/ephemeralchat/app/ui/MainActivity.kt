package com.ephemeralchat.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import com.ephemeralchat.app.EphemeralChatApp
import com.ephemeralchat.app.service.MeshConnectionService

class MainActivity:ComponentActivity() {
    private var afterPermissions:(()->Unit)?=null
    private val permissions=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val action=afterPermissions;afterPermissions=null
        if(result.values.all { it }) action?.invoke()
        else android.widget.Toast.makeText(this,"Permission denied. You can enable it in Android Settings.",android.widget.Toast.LENGTH_LONG).show()
    }
    fun requestMedia(video:Boolean,action:()->Unit) {
        ask(buildList { add(Manifest.permission.RECORD_AUDIO);if(video)add(Manifest.permission.CAMERA) }.toTypedArray(),action)
    }
    fun startNearby() {
        val list=buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if(Build.VERSION.SDK_INT>=31) { add(Manifest.permission.BLUETOOTH_ADVERTISE);add(Manifest.permission.BLUETOOTH_CONNECT);add(Manifest.permission.BLUETOOTH_SCAN) }
            if(Build.VERSION.SDK_INT>=33)add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }.toTypedArray()
        ask(list) { ContextCompat.startForegroundService(this,Intent(this,MeshConnectionService::class.java)) }
    }
    private fun ask(list:Array<String>,action:()->Unit) {
        if(list.all { ContextCompat.checkSelfPermission(this,it)==PackageManager.PERMISSION_GRANTED })action()
        else { afterPermissions=action;permissions.launch(list) }
    }
    override fun onStop() {
        super.onStop()
        // Foreground-only calls deliberately release camera and microphone when hidden.
        (application as EphemeralChatApp).calls.hangup()
    }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        window.statusBarColor=0xFF101016.toInt()
        @Suppress("DEPRECATION")
        window.navigationBarColor=0xFF101016.toInt()
        androidx.core.view.WindowCompat.getInsetsController(window,window.decorView).apply {
            isAppearanceLightStatusBars=false
            isAppearanceLightNavigationBars=false
        }
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        setContent {
            MaterialTheme(colorScheme=darkColorScheme(primary=Color(0xFFC4A7FF),onPrimary=Color(0xFF251446),primaryContainer=Color(0xFF493172),background=Color(0xFF101016),surface=Color(0xFF191820),surfaceVariant=Color(0xFF292633),secondary=Color(0xFF9CDBCE))) {
                EphemeralApp(application as EphemeralChatApp,this)
            }
        }
    }
}
