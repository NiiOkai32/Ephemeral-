package com.ephemeralchat.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.MediaDataSource
import android.os.Build
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.ephemeralchat.app.EphemeralChatApp
import com.ephemeralchat.app.crypto.SessionCrypto
import com.ephemeralchat.app.transport.SessionManager
import kotlinx.coroutines.delay
import java.io.File
import java.security.SecureRandom
import java.util.Base64
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EphemeralApp(app:EphemeralChatApp,activity:MainActivity) {
    val transport=app.meshTransport
    val connected by transport.connectedPeers.collectAsState()
    val discovered by transport.discoveredPeers.collectAsState()
    val pairing by transport.pairing.collectAsState()
    val status by transport.status.collectAsState()
    val ready by app.sessionManager.ready.collectAsState()
    val call by app.calls.state.collectAsState()
    val callError by app.calls.error.collectAsState()
    var selected by remember { mutableStateOf<String?>(null) }
    var settings by remember { mutableStateOf(false) }
    var security by remember { mutableStateOf(false) }
    val snacks=remember { SnackbarHostState() }
    LaunchedEffect(Unit) { transport.errors.collect { snacks.showSnackbar(it) } }
    LaunchedEffect(callError) { callError?.let { snacks.showSnackbar(it);app.calls.error.value=null } }
    Scaffold(snackbarHost={ SnackbarHost(snacks) },topBar={
        TopAppBar(title={ Column {
            Text(if(selected==null) "ephemeral" else connected[selected]?.displayName?:"Contact",fontWeight=FontWeight.Bold)
            Text(if(selected==null) "A little closer. A lot more private." else if(ready.containsKey(selected)) "Daily keys connected · UTC" else "Waiting for secure connection",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.secondary)
        } },navigationIcon={ if(selected!=null) IconButton(onClick={selected=null}) { Icon(Icons.Default.ArrowBack,"Back") } },actions={
            if(selected==null) IconButton(onClick={settings=true}) { Icon(Icons.Default.Settings,"Internet settings") }
            else {
                IconButton(enabled=ready.containsKey(selected),onClick={ activity.requestMedia(false) { selected?.let { app.calls.start(it,false) } } }) { Icon(Icons.Default.Call,"Audio call") }
                IconButton(enabled=ready.containsKey(selected),onClick={ activity.requestMedia(true) { selected?.let { app.calls.start(it,true) } } }) { Icon(Icons.Default.Videocam,"Video call") }
                IconButton(onClick={security=true}) { Icon(Icons.Default.Lock,"Security information") }
            }
        })
    }) { padding ->
        if(selected==null) {
            LazyColumn(Modifier.padding(padding).fillMaxSize(),contentPadding=PaddingValues(22.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
                item {
                    Surface(shape=RoundedCornerShape(28.dp),color=MaterialTheme.colorScheme.primaryContainer) {
                        Column(Modifier.fillMaxWidth().padding(24.dp)) {
                            Icon(Icons.Default.AutoAwesome,null,Modifier.size(32.dp))
                            Spacer(Modifier.height(16.dp))
                            Text("Here today.\nPrivate, always.",fontSize=30.sp,lineHeight=36.sp,fontWeight=FontWeight.Bold)
                            Spacer(Modifier.height(12.dp))
                            Text("A new key from each device, every day. Messages live in memory and clear at midnight UTC or when the app closes.",style=MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                item { Text("YOUR CONNECTIONS",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.secondary) }
                items(connected.values.toList(),key={it.endpointId}) { peer ->
                    ListItem(headlineContent={Text(peer.displayName)},supportingContent={Text(if(peer.endpointId in ready) "Encrypted · Tap to chat" else "Exchanging today's keys…")},leadingContent={Icon(Icons.Default.Person,null)},trailingContent={Icon(Icons.Default.ChevronRight,null)},modifier=Modifier.clickable { selected=peer.endpointId })
                }
                if(connected.isEmpty())item { Text("No conversations yet. Connect nearby, or invite someone over the internet.",color=MaterialTheme.colorScheme.onSurfaceVariant) }
                item {
                    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Button(modifier=Modifier.fillMaxWidth(),onClick={activity.startNearby()}) { Icon(Icons.Default.Wifi,null);Spacer(Modifier.width(8.dp));Text("Find nearby") }
                        OutlinedButton(modifier=Modifier.fillMaxWidth(),onClick={settings=true}) { Text("Internet invite") }
                    }
                    Text(status,Modifier.padding(top=10.dp),style=MaterialTheme.typography.bodySmall)
                }
                items(discovered.values.filter { it.endpointId !in connected },key={it.endpointId}) { peer ->
                    ListItem(headlineContent={Text(peer.displayName)},supportingContent={Text("Nearby · Tap to pair")},leadingContent={Icon(Icons.Default.Bluetooth,null)},modifier=Modifier.clickable { transport.requestConnection(peer.endpointId) })
                }
                item { Text("Keep both apps open. Compare pairing codes in person, and share internet invitations through a trusted channel.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        } else Conversation(app,activity,selected!!,Modifier.padding(padding))
    }
    if(pairing!=null) AlertDialog(onDismissRequest={transport.answerPairing(false)},title={Text("Confirm your contact")},text={Column {Text("Compare this code on both phones before accepting.");Text(pairing!!.code,fontSize=36.sp,modifier=Modifier.padding(vertical=18.dp));Text(pairing!!.name)}},confirmButton={TextButton(onClick={transport.answerPairing(true)}) {Text("Codes match")}},dismissButton={TextButton(onClick={transport.answerPairing(false)}) {Text("Reject")}})
    if(settings) InternetSettings(activity,transport,onClose={settings=false})
    if(security) AlertDialog(onDismissRequest={security=false},title={Text("Today's safety number")},text={Column {Text(ready[selected]?:"Not connected");Spacer(Modifier.height(16.dp));Text("Compare the entire number with your contact through a trusted channel. Keys rotate at 00:00 UTC. Expired messages cannot be read. This is a custom protocol and has not received an independent security audit.")}},confirmButton={TextButton(onClick={security=false}){Text("Done")}})
    if(call.phase!="idle") androidx.compose.ui.window.Dialog(onDismissRequest={app.calls.hangup()},properties=androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(20.dp)) {
                Text(if(call.video) "Private video call" else "Private audio call",style=MaterialTheme.typography.headlineMedium)
                Text(call.phase.replaceFirstChar {it.uppercase()})
                val remote by app.calls.remoteVideo.collectAsState()
                if(call.video && call.phase!="incoming") {
                    VideoSurface(remote,app,Modifier.weight(1f).fillMaxWidth())
                    VideoSurface(app.calls.localVideo,app,Modifier.height(140.dp).width(190.dp))
                } else Box(Modifier.weight(1f),contentAlignment=Alignment.Center){Icon(Icons.Default.Person,null,Modifier.size(100.dp))}
                if(call.phase=="incoming")Button(onClick={activity.requestMedia(call.video){app.calls.accept()}}){Text("Accept call")}
                Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    if(call.phase!="incoming") {
                        OutlinedButton(onClick={app.calls.toggleMute()}){Text(if(call.muted)"Unmute" else "Mute")}
                        if(call.video)OutlinedButton(onClick={app.calls.toggleCamera()}){Text(if(call.cameraOff)"Camera on" else "Camera off")}
                    }
                    Button(onClick={app.calls.hangup()},colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.error)){Text(if(call.phase=="incoming")"Decline" else "End")}
                }
                Text("Calls end when the app leaves the foreground.",style=MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable private fun VideoSurface(track:VideoTrack?,app:EphemeralChatApp,modifier:Modifier) {
    var renderer by remember { mutableStateOf<SurfaceViewRenderer?>(null) }
    AndroidView(factory={ ctx -> SurfaceViewRenderer(ctx).apply { init(app.calls.egl.eglBaseContext,null);setEnableHardwareScaler(true);renderer=this } },modifier=modifier)
    DisposableEffect(track,renderer) { val view=renderer;if(view!=null)track?.addSink(view);onDispose { if(view!=null)runCatching{track?.removeSink(view)} } }
    DisposableEffect(Unit) { onDispose { renderer?.release() } }
}

@Composable private fun Conversation(app:EphemeralChatApp,activity:MainActivity,id:String,modifier:Modifier) {
    val all by app.sessionManager.history.collectAsState()
    val ready by app.sessionManager.ready.collectAsState()
    val messages=all.filter {it.endpointId==id}
    var input by remember(id) { mutableStateOf("") }
    var stickers by remember {mutableStateOf(false)}
    var error by remember {mutableStateOf<String?>(null)}
    var recording by remember {mutableStateOf(false)}
    var seconds by remember {mutableIntStateOf(0)}
    val voice=remember {VoiceRecorder(activity)}
    val player=remember {VoicePlayer()}
    val list=rememberLazyListState()
    fun send(value:String,kind:String="text") { runCatching{app.sessionManager.sendMessage(id,value,kind)}.onFailure{error=it.message} }
    fun finishRecording() {
        recording=false
        runCatching { voice.finish()?.let {send(Base64.getEncoder().encodeToString(it),"voice")} }.onFailure {error="Recording failed"}
    }
    DisposableEffect(id) {
        val observer=androidx.lifecycle.LifecycleEventObserver { _,event ->
            if(event==androidx.lifecycle.Lifecycle.Event.ON_STOP) { voice.cancel();recording=false;player.close() }
        }
        activity.lifecycle.addObserver(observer)
        onDispose{activity.lifecycle.removeObserver(observer);voice.cancel();player.close()}
    }
    LaunchedEffect(recording) { if(recording) {seconds=0;while(seconds<60){delay(1000);seconds++};finishRecording()} }
    LaunchedEffect(messages.size) { if(messages.isNotEmpty())list.animateScrollToItem(messages.lastIndex) }
    Column(modifier.fillMaxSize().imePadding()) {
        Surface(color=MaterialTheme.colorScheme.surfaceVariant) {Text("🔒  Daily end-to-end encryption · ${SessionCrypto.today()}",Modifier.fillMaxWidth().padding(12.dp),style=MaterialTheme.typography.labelSmall)}
        if(messages.isEmpty())Box(Modifier.weight(1f).fillMaxWidth(),contentAlignment=Alignment.Center){Column(horizontalAlignment=Alignment.CenterHorizontally){Icon(Icons.Default.Forum,null,Modifier.size(48.dp),tint=MaterialTheme.colorScheme.primary);Spacer(Modifier.height(16.dp));Text("Just between you two.");Text("Say something worth sharing.",style=MaterialTheme.typography.bodySmall)}}
        else LazyColumn(Modifier.weight(1f).fillMaxWidth(),state=list,contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            items(messages,key={it.id}) { msg ->
                Box(Modifier.fillMaxWidth(),contentAlignment=if(msg.isMine)Alignment.CenterEnd else Alignment.CenterStart) {
                    Surface(shape=RoundedCornerShape(20.dp),color=if(msg.isMine)MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,modifier=Modifier.widthIn(max=300.dp)) {
                        Column(Modifier.padding(14.dp)) {
                            when(msg.kind) {
                                "voice" -> TextButton(onClick={runCatching{player.play(Base64.getDecoder().decode(msg.text))}.onFailure{error="Unable to play voice note"}}){Icon(Icons.Default.PlayArrow,"Play voice note");Text("Voice note")}
                                "sticker" -> Text(msg.text,fontSize=64.sp)
                                else -> Text(msg.text)
                            }
                            Text(java.text.SimpleDateFormat("HH:mm",java.util.Locale.getDefault()).format(java.util.Date(msg.timestamp))+(if(msg.isMine)" · Sent to transport" else ""),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        error?.let {Text(it,color=MaterialTheme.colorScheme.error,modifier=Modifier.padding(12.dp))}
        if(stickers)Column(Modifier.padding(8.dp)) { SessionManager.stickers.chunked(6).forEach {row->Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly){row.forEach {s->TextButton(enabled=id in ready,onClick={send(s,"sticker");stickers=false}){Text(s,fontSize=28.sp)}}}} }
        Row(Modifier.fillMaxWidth().padding(8.dp).navigationBarsPadding(),verticalAlignment=Alignment.CenterVertically) {
            IconButton(onClick={stickers=!stickers}){Icon(Icons.Default.EmojiEmotions,"Stickers")}
            if(recording) { Text("Recording ${seconds}s / 60s",Modifier.weight(1f));IconButton(onClick={voice.cancel();recording=false}){Icon(Icons.Default.Close,"Discard recording")} }
            else OutlinedTextField(input,{if(it.length<=4000)input=it},Modifier.weight(1f),placeholder={Text("Message…")},shape=RoundedCornerShape(28.dp),maxLines=4)
            if(input.isNotBlank()&&!recording)IconButton(enabled=id in ready,onClick={send(input.trim());input=""}){Icon(Icons.Default.Send,"Send")}
            else IconButton(enabled=id in ready,onClick={if(recording)finishRecording() else activity.requestMedia(false) {runCatching{voice.start();recording=true}.onFailure{error="Microphone unavailable"}}}){Icon(if(recording)Icons.Default.Stop else Icons.Default.Mic,if(recording)"Send voice note" else "Record voice note")}
        }
    }
}

@Composable private fun InternetSettings(context:Context,transport:com.ephemeralchat.app.transport.MeshTransport,onClose:()->Unit) {
    val prefs=remember {context.getSharedPreferences("settings",Context.MODE_PRIVATE)}
    var relay by remember {mutableStateOf(prefs.getString("relay","").orEmpty())}
    var invite by remember {mutableStateOf("")}
    var turn by remember {mutableStateOf(prefs.getString("turn","").orEmpty())}
    var user by remember {mutableStateOf(prefs.getString("turnUser","").orEmpty())}
    var password by remember {mutableStateOf("")}
    var error by remember {mutableStateOf<String?>(null)}
    AlertDialog(onDismissRequest=onClose,title={Text("Connect over the internet")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Text("Both people need the same relay URL and invitation. Share the invitation privately; the relay only sees its hash.")
        OutlinedTextField(relay,{relay=it},label={Text("Relay URL · wss://your-server")},singleLine=true)
        OutlinedTextField(invite,{invite=it.trim()},label={Text("Invitation code")},singleLine=true)
        Row { TextButton(onClick={invite=Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also{SecureRandom().nextBytes(it)})}){Text("Generate")};TextButton(enabled=invite.isNotBlank(),onClick={(context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Invitation",invite))}){Text("Copy")}}
        Text("TURN is recommended for reliable calls across networks. Use short-lived credentials from your provider.",style=MaterialTheme.typography.bodySmall)
        OutlinedTextField(turn,{turn=it},label={Text("TURN URL (optional)")},singleLine=true)
        OutlinedTextField(user,{user=it},label={Text("TURN username")},singleLine=true)
        OutlinedTextField(password,{password=it},label={Text("TURN password")},singleLine=true,visualTransformation=PasswordVisualTransformation())
        error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
    }},confirmButton={TextButton(onClick={runCatching{
        require(turn.isBlank()||turn.startsWith("turn:")||turn.startsWith("turns:")){"Invalid TURN URL"}
        prefs.edit().putString("relay",relay).putString("turn",turn).putString("turnUser",user).apply()
        // TURN password remains process-memory only; never persisted to preferences.
        CallCredentials.password=password
        transport.connectInternet(relay,invite);onClose()
    }.onFailure{error=it.message}}){Text("Connect")}},dismissButton={TextButton(onClick=onClose){Text("Cancel")}})
}
object CallCredentials {var password=""}
private class VoiceRecorder(private val context:Context) {
    private var recorder:MediaRecorder?=null
    private var file:File?=null
    fun start() {
        cancel();file=File.createTempFile("voice-",".m4a",context.cacheDir)
        @Suppress("DEPRECATION")
        val r=if(Build.VERSION.SDK_INT>=31)MediaRecorder(context) else MediaRecorder()
        recorder=r
        r.setAudioSource(MediaRecorder.AudioSource.MIC);r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);r.setAudioEncodingBitRate(32000);r.setAudioSamplingRate(16000);r.setMaxDuration(61000);r.setMaxFileSize(1800000);r.setOutputFile(file!!.absolutePath);r.prepare();r.start()
    }
    fun finish():ByteArray? {return try{recorder?.stop();file?.readBytes()?.also {require(it.size<=2000000)}}finally{cancel()}}
    fun cancel(){runCatching{recorder?.reset();recorder?.release()};recorder=null;file?.delete();file=null}
}
private class VoicePlayer {
    private var player:MediaPlayer?=null
    fun play(bytes:ByteArray){close();player=MediaPlayer().apply{
        setDataSource(object:MediaDataSource(){override fun getSize()=bytes.size.toLong();override fun close(){bytes.fill(0)};override fun readAt(position:Long,buffer:ByteArray,offset:Int,size:Int):Int{if(position>=bytes.size)return -1;val count=minOf(size,bytes.size-position.toInt());bytes.copyInto(buffer,offset,position.toInt(),position.toInt()+count);return count}})
        setOnCompletionListener{close()};prepare();start()
    }}
    fun close(){player?.release();player=null}
}
