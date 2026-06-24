package com.example.chatapp.games.racepark

import com.example.chatapp.R
import android.os.Bundle
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.net.URI
import java.util.Locale
import java.util.Random
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import com.example.chatapp.ui.theme.ChatAppTheme

internal data class RacePlayer(val name: String, val x: Float, val y: Float, val heading: Float, val health: Float)

internal class RaceGameConnection(private val username: String) {
    val players = mutableStateListOf<RacePlayer>()
    var roomCode by mutableStateOf("")
        private set
    var status by mutableStateOf("Choose how to join")
        private set
    private var socket: WebSocketClient? = null
    private val main = Handler(Looper.getMainLooper())

    fun createRoom() = connect("CREATE")
    fun quickMatch() = connect("QUICK")
    fun joinRoom(code: String) = connect("JOIN", code.trim().uppercase(Locale.US))

    private fun connect(command: String, code: String = "") {
        socket?.close()
        roomCode = ""
        players.clear()
        status = "Connecting..."
        val newSocket = object : WebSocketClient(URI(GAME_SERVER_URL)) {
            override fun onOpen(handshakedata: ServerHandshake?) {
                val suffix = if (code.isBlank()) "" else "|$code"
                send("$command|${encode(username)}$suffix")
            }
            override fun onMessage(message: String?) {
                val value = message ?: return
                main.post { handleMessage(value) }
            }
            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                main.post { if (this@RaceGameConnection.socket === this && roomCode.isNotBlank()) status = "Race connection closed" }
            }
            override fun onError(ex: Exception?) {
                main.post { if (this@RaceGameConnection.socket === this) status = "Could not join the race server" }
            }
        }
        socket = newSocket
        newSocket.connect()
    }

    private fun handleMessage(value: String) {
        val parts = value.split('|')
        when (parts.firstOrNull()) {
            "ROOM" -> { roomCode = parts.getOrNull(1).orEmpty(); status = "Room $roomCode" }
            "STATE" -> if (parts.size == 6) {
                val player = runCatching { RacePlayer(decode(parts[1]), parts[2].toFloat(), parts[3].toFloat(), parts[4].toFloat(), parts[5].toFloat()) }.getOrNull() ?: return
                val index = players.indexOfFirst { it.name == player.name }
                if (index < 0) players += player else players[index] = player
            }
            "PLAYERS" -> {
                val active = parts.getOrNull(1).orEmpty().split(',').filter(String::isNotBlank).mapNotNull { runCatching { decode(it) }.getOrNull() }.toSet()
                players.removeAll { it.name !in active }
                status = "${active.size} racer${if (active.size == 1) "" else "s"} in room"
            }
            "ERROR" -> status = parts.getOrNull(1) ?: "Could not join room"
        }
    }

    fun sendState(x: Float, y: Float, heading: Float, health: Float) {
        socket?.takeIf { it.isOpen }?.send("STATE|$x|$y|$heading|$health")
    }
    fun leave() {
        socket?.takeIf { it.isOpen }?.send("LEAVE")
        socket?.close(); socket = null; roomCode = ""; players.clear(); status = "Choose how to join"
    }
    private fun encode(value: String) = Base64.encodeToString(value.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    private fun decode(value: String) = String(Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
    companion object { private const val GAME_SERVER_URL = "wss://somechatapp.ddns.net/game" }
}

private class RaceSoundEngine(context:android.content.Context) {
    private val pool=SoundPool.Builder().setMaxStreams(8).setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build()
    private val engine=pool.load(context,R.raw.engine_loop,1)
    private val tree=pool.load(context,R.raw.crash_tree,1)
    private val solid=pool.load(context,R.raw.crash_solid,1)
    private val car=pool.load(context,R.raw.crash_car,1)
    private val destroyed=pool.load(context,R.raw.car_destroyed,1)
    private var engineStream=0
    fun updateEngine(speedRatio:Float,running:Boolean){
        if(!running){stopEngine();return}
        val ratio=speedRatio.coerceIn(0f,1f);val rate=.62f+ratio*1.35f;val volume=.2f+ratio*.42f
        if(engineStream==0)engineStream=pool.play(engine,volume,volume,1,-1,rate)
        else{pool.setRate(engineStream,rate);pool.setVolume(engineStream,volume,volume)}
    }
    fun stopEngine(){if(engineStream!=0){pool.stop(engineStream);engineStream=0}}
    fun treeCrash(){pool.play(tree,.82f,.82f,2,0,1f)}
    fun solidCrash(){pool.play(solid,.82f,.82f,2,0,1f)}
    fun carCrash(){pool.play(car,.92f,.92f,3,0,1f)}
    fun destroyed(){stopEngine();pool.play(destroyed,1f,1f,4,0,1f)}
    fun release(){stopEngine();pool.release()}
}

class RaceGameActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        val username = intent.getStringExtra("username").orEmpty().ifBlank { "Racer" }.take(24)
        setContent {
            ChatAppTheme {
                Surface(Modifier.fillMaxSize()) {
                    RaceGameApp(username) { code ->
                        RaceInviteBridge.share(code)
                        Toast.makeText(this, "Room $code shared in chat", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }
}

internal object RaceInviteBridge {
    var sender: ((String) -> Unit)? = null
    fun share(code: String) = sender?.invoke(code)
}

@Composable
private fun RaceGameApp(username: String, onShareInvite: (String) -> Unit) {
    val connection = remember(username) { RaceGameConnection(username) }
    DisposableEffect(connection) { onDispose { connection.leave() } }
    if (connection.roomCode.isBlank()) {
        GamesLobby(connection, Modifier.fillMaxSize())
    } else {
        RacePark(connection, username, onShareInvite, Modifier.fillMaxSize())
    }
}

@Composable
private fun GamesLobby(connection: RaceGameConnection, modifier: Modifier) {
    var code by rememberSaveable { mutableStateOf("") }
    Column(modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Games", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
        Text("Play together without leaving ChatApp.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Card(shape = RoundedCornerShape(24.dp), elevation = CardDefaults.cardElevation(4.dp)) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(58.dp).background(Color(0xFFEF584A), RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                        Text("🏁", style = MaterialTheme.typography.headlineMedium)
                    }
                    Spacer(Modifier.width(14.dp))
                    Column { Text("Race Park", fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleLarge); Text("1–6 players • live racing") }
                }
                Button(connection::quickMatch, Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) { Text("Quick Match", fontWeight = FontWeight.Bold) }
                OutlinedButton(connection::createRoom, Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) { Text("Create private room") }
                OutlinedTextField(code, { code = it.filter(Char::isLetter).uppercase().take(3) }, Modifier.fillMaxWidth(), label = { Text("3-letter room code") }, singleLine = true)
                Button({ connection.joinRoom(code) }, Modifier.fillMaxWidth(), enabled = code.length == 3, shape = RoundedCornerShape(16.dp)) { Text("Join by code") }
                Text(connection.status, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun RacePark(connection: RaceGameConnection, username: String, onShareInvite: (String) -> Unit, modifier: Modifier) {
    val variant = remember(connection.roomCode) { Math.floorMod(connection.roomCode.hashCode(), 3) }
    val spawn = listOf(.525f to .70f, .46f to .66f, .835f to .41f)[variant]
    var x by rememberSaveable(connection.roomCode) { mutableStateOf(spawn.first) }
    var y by rememberSaveable(connection.roomCode) { mutableStateOf(spawn.second) }
    var heading by rememberSaveable(connection.roomCode) { mutableStateOf(if(variant==2) 90f else 180f) }
    var travelHeading by rememberSaveable(connection.roomCode) { mutableStateOf(heading) }
    var velocity by rememberSaveable(connection.roomCode) { mutableFloatStateOf(0f) }
    var health by rememberSaveable(connection.roomCode) { mutableFloatStateOf(100f) }
    var gameTime by remember { mutableFloatStateOf(0f) }
    var surface by remember { mutableStateOf(TrackSurface.ROAD) }
    var destructionSoundPlayed by remember(connection.roomCode) { mutableStateOf(false) }
    var leftForward by remember { mutableStateOf(false) }; var leftBack by remember { mutableStateOf(false) }
    var rightForward by remember { mutableStateOf(false) }; var rightBack by remember { mutableStateOf(false) }
    val collisionTimes=remember(connection.roomCode) { mutableMapOf<String,Long>() }
    var lastEnvironmentImpact by remember { mutableStateOf(0L) }
    var lastBlockedAt by remember(connection.roomCode) { mutableStateOf(0L) }
    var freelyDrivingSince by remember(connection.roomCode) { mutableStateOf(0L) }
    var stuckAnchorX by remember(connection.roomCode) { mutableFloatStateOf(spawn.first) }
    var stuckAnchorY by remember(connection.roomCode) { mutableFloatStateOf(spawn.second) }
    var stuckSince by remember(connection.roomCode) { mutableStateOf(0L) }
    var recoveryFromX by remember { mutableFloatStateOf(0f) }; var recoveryFromY by remember { mutableFloatStateOf(0f) }
    var recoveryToX by remember { mutableFloatStateOf(0f) }; var recoveryToY by remember { mutableFloatStateOf(0f) }
    var recoveryProgress by remember { mutableFloatStateOf(0f) }
    var recovering by remember(connection.roomCode) { mutableStateOf(false) }
    var towSecondsLeft by remember(connection.roomCode) { mutableStateOf(0) }
    val context = LocalContext.current
    val sounds=remember { RaceSoundEngine(context.applicationContext) }
    DisposableEffect(sounds){onDispose{sounds.release()}}
    val trackResource = listOf(R.drawable.race_track_1,R.drawable.race_track_2,R.drawable.race_track_3)[variant]
    val trackImage = remember(trackResource) { ImageBitmap.imageResource(context.resources,trackResource) }
    val roadBitmap = remember(trackResource) { BitmapFactory.decodeResource(context.resources,trackResource) }
    val carImages = remember {
        listOf(
            R.drawable.race_car_red,R.drawable.race_car_blue,R.drawable.race_car_yellow,
            R.drawable.race_car_purple,R.drawable.race_car_green,R.drawable.race_car_pink,
        ).map { ImageBitmap.imageResource(context.resources,it) }
    }

    LaunchedEffect(connection.roomCode) {
        var tick = 0
        while (true) {
            gameTime += .016f
            val left=(if(leftForward)1 else 0)-(if(leftBack)1 else 0); val right=(if(rightForward)1 else 0)-(if(rightBack)1 else 0)
            if(recovering) {
                recoveryProgress=(recoveryProgress+.016f/2.2f).coerceAtMost(1f)
                val smooth=recoveryProgress*recoveryProgress*(3f-2f*recoveryProgress)
                x=recoveryFromX+(recoveryToX-recoveryFromX)*smooth
                y=recoveryFromY+(recoveryToY-recoveryFromY)*smooth
                velocity=0f
                if(recoveryProgress>=1f){
                    recovering=false;stuckAnchorX=x;stuckAnchorY=y;stuckSince=0L
                    towSecondsLeft=0
                    surface=classifySurface(roadBitmap,x,y)
                }
            } else if(health>0f) {
                surface=if(isBridgeUnderpass(x,y,variant))TrackSurface.ROAD else classifySurface(roadBitmap,x,y)
                val throttle=(left+right)*.5f
                val reverseFactor=if(throttle<0f).55f else 1f
                val maxSpeed=when(surface){TrackSurface.ROAD,TrackSurface.CURB->.22f;TrackSurface.GRAVEL->.12f;TrackSurface.GRASS->.082f;TrackSurface.WATER->.035f}
                val targetSpeed=throttle*maxSpeed*reverseFactor
                val acceleration=when{throttle==0f->.22f;targetSpeed*velocity<0f->.48f;else->.30f}
                velocity=moveToward(velocity,targetSpeed,acceleration*.016f)
                val grip=when(surface){TrackSurface.ROAD->1f;TrackSurface.CURB->.82f;TrackSurface.GRAVEL->.58f;TrackSurface.GRASS->.48f;TrackSurface.WATER->.28f}
                val turnInput=(right-left).toFloat()
                val spinInPlace=left!=0&&right!=0&&left==-right
                val speedRatio=(kotlin.math.abs(velocity)/.22f).coerceIn(0f,1f)
                val turnRate=if(spinInPlace)135f else 190f*(.38f+.62f*speedRatio)*grip
                heading+=turnInput*turnRate*.016f
                travelHeading=moveAngleToward(travelHeading,heading,(4.2f+grip*6f)*.016f)
                val r=Math.toRadians(travelHeading.toDouble())
                val nextX=x+cos(r).toFloat()*velocity*.016f
                val nextY=y+sin(r).toFloat()*velocity*.027f
                val now=System.currentTimeMillis()
                val impactSpeed=kotlin.math.abs(velocity)
                val blocked=nextX !in 0.018f..0.982f||nextY !in 0.035f..0.965f||isSolidEnvironment(roadBitmap,nextX,nextY,variant)
                if(blocked)lastBlockedAt=now
                if(!blocked){x=nextX;y=nextY}
                else {
                    val canSlideX=nextX in 0.018f..0.982f&&!isSolidEnvironment(roadBitmap,nextX,y,variant)
                    val canSlideY=nextY in 0.035f..0.965f&&!isSolidEnvironment(roadBitmap,x,nextY,variant)
                    when {
                        canSlideX&&canSlideY->{if(kotlin.math.abs(nextX-x)>=kotlin.math.abs(nextY-y))x=nextX else y=nextY;velocity*=.78f}
                        canSlideX->{x=nextX;velocity*=.78f}
                        canSlideY->{y=nextY;velocity*=.78f}
                        else->{
                            val dx=nextX-x;val dy=nextY-y;val step=hypot(dx,dy);val base=kotlin.math.atan2(dy,dx)
                            var escaped=false
                            for(degrees in listOf(18,-18,34,-34,52,-52,72,-72,95,-95)){
                                val angle=base+Math.toRadians(degrees.toDouble()).toFloat()
                                val candidateX=x+cos(angle.toDouble()).toFloat()*step*1.12f;val candidateY=y+sin(angle.toDouble()).toFloat()*step*1.12f
                                if(candidateX in 0.018f..0.982f&&candidateY in 0.035f..0.965f&&!isSolidEnvironment(roadBitmap,candidateX,candidateY,variant)){
                                    x=candidateX;y=candidateY;velocity*=.64f;travelHeading=Math.toDegrees(angle.toDouble()).toFloat();escaped=true;break
                                }
                            }
                            if(!escaped){
                                velocity*=.18f
                                val kind=environmentImpactKind(roadBitmap,nextX.coerceIn(0f,1f),nextY.coerceIn(0f,1f),variant)
                                val threshold=if(kind==ImpactKind.TREE).035f else .075f
                                val cooldown=if(kind==ImpactKind.TREE)1000L else 1800L
                                if(impactSpeed>threshold&&now-lastEnvironmentImpact>cooldown){
                                    val damage=if(kind==ImpactKind.TREE)4f else (impactSpeed/.22f*3f).coerceIn(1f,3f)
                                    health=(health-damage).coerceAtLeast(0f);lastEnvironmentImpact=now
                                    if(kind==ImpactKind.TREE)sounds.treeCrash() else sounds.solidCrash()
                                }
                            }
                        }
                    }
                }
                val drainPerSecond=when(surface){TrackSurface.GRASS->.65f;TrackSurface.GRAVEL->.18f;TrackSurface.WATER->3.2f;else->0f}
                health=(health-drainPerSecond*.016f).coerceAtLeast(0f)
                connection.players.filter { it.name!=username && it.health>0f }.forEach { other ->
                    val dx=x-other.x;val dy=y-other.y;val distance=hypot(dx,dy)
                    if(distance<.025f && now-(collisionTimes[other.name]?:0L)>700L) {
                        health=(health-12f).coerceAtLeast(0f);collisionTimes[other.name]=now;velocity*=-.32f
                        sounds.carCrash()
                        if(distance>.001f){x=(x+dx/distance*.012f).coerceIn(.02f,.98f);y=(y+dy/distance*.012f).coerceIn(.04f,.96f)}
                    }
                }
                val offTrack=surface !in setOf(TrackSurface.ROAD,TrackSurface.CURB)
                val freelyDriving=!offTrack&&!blocked&&kotlin.math.abs(velocity)>.018f
                if(freelyDriving){
                    if(freelyDrivingSince==0L)freelyDrivingSince=now
                    if(now-freelyDrivingSince>=700L){
                        lastBlockedAt=0L;stuckSince=0L;towSecondsLeft=0
                        stuckAnchorX=x;stuckAnchorY=y
                    }
                } else freelyDrivingSince=0L
                val watchForStuck=offTrack||now-lastBlockedAt<12_000L
                if(!watchForStuck){
                    stuckAnchorX=x;stuckAnchorY=y;stuckSince=0L;towSecondsLeft=0
                } else {
                    if(stuckSince==0L)stuckSince=now
                    val stuckFor=now-stuckSince
                    towSecondsLeft=((10_000L-stuckFor+999L)/1000L).toInt().coerceIn(0,10)
                    if(stuckFor>=10_000L){
                        val safe=nearestSafeRoadPoint(roadBitmap,x,y,variant,spawn)
                        recoveryFromX=x;recoveryFromY=y;recoveryToX=safe.first;recoveryToY=safe.second
                        recoveryProgress=0f;recovering=true;velocity=0f;towSecondsLeft=0
                    }
                }
            } else {
                leftForward=false;leftBack=false;rightForward=false;rightBack=false;velocity=0f
            }
            sounds.updateEngine((kotlin.math.abs(velocity)/.22f).coerceIn(0f,1f),health>0f&&kotlin.math.abs(velocity)>.006f)
            if(health<=0f&&!destructionSoundPlayed){destructionSoundPlayed=true;sounds.destroyed()}
            if(tick++%4==0) connection.sendState(x,y,heading,health)
            delay(16)
        }
    }

    val racers=(connection.players.filter { it.name!=username }+RacePlayer(username,x,y,heading,health)).distinctBy { it.name }
    val survivors=racers.filter { it.health>0f }
    val winner=survivors.singleOrNull()?.takeIf { racers.size>=2 }?.name

    BoxWithConstraints(modifier.fillMaxSize().background(Color(0xFF4F9A5D))) {
        val controlWidth = maxWidth * .18f
        val controlHeight = maxHeight * .18f
        Canvas(Modifier.fillMaxSize()) {
            drawImage(
                image=trackImage,
                srcOffset=IntOffset.Zero,
                srcSize=IntSize(trackImage.width,trackImage.height),
                dstOffset=IntOffset.Zero,
                dstSize=IntSize(size.width.toInt(),size.height.toInt()),
                filterQuality=FilterQuality.High,
            )
            val carHeight=minOf(size.width,size.height)*.055f
            val racerNames=(connection.players.map { it.name }+username).distinct().sortedBy { it.lowercase() }
            connection.players.filter { it.name != username }.forEach { p ->
                val slot=racerNames.indexOf(p.name).coerceAtLeast(0)%carImages.size
                drawSurfaceEffect(Offset(p.x*size.width,p.y*size.height),classifySurface(roadBitmap,p.x,p.y),gameTime+slot,carHeight,.55f)
                drawRaceCar(Offset(p.x*size.width,p.y*size.height),p.heading,p.name,carImages[slot],carHeight)
                if(p.health<=0f) drawBurningEffect(Offset(p.x*size.width,p.y*size.height),carHeight,gameTime+slot)
            }
            val mySlot=racerNames.indexOf(username).coerceAtLeast(0)%carImages.size
            drawSurfaceEffect(Offset(x*size.width,y*size.height),surface,gameTime,carHeight,(kotlin.math.abs(velocity)/.22f).coerceIn(0f,1f))
            drawRaceCar(Offset(x*size.width,y*size.height),heading,username,carImages[mySlot],carHeight)
            if(health<=0f) drawBurningEffect(Offset(x*size.width,y*size.height),carHeight,gameTime)
            bridgeOverlayPolygons[variant].forEach { points ->
                val bridgePath=Path().apply {
                    moveTo(points.first().x*size.width,points.first().y*size.height)
                    points.drop(1).forEach { lineTo(it.x*size.width,it.y*size.height) }
                    close()
                }
                clipPath(bridgePath) {
                    drawImage(image=trackImage,dstOffset=IntOffset.Zero,dstSize=IntSize(size.width.toInt(),size.height.toInt()),filterQuality=FilterQuality.High)
                }
            }
            if(recovering) {
                val dx=(recoveryToX-recoveryFromX)*size.width;val dy=(recoveryToY-recoveryFromY)*size.height
                val length=hypot(dx,dy).coerceAtLeast(1f)
                val carAt=Offset(x*size.width,y*size.height)
                val truckAt=Offset(carAt.x+dx/length*carHeight*1.45f,carAt.y+dy/length*carHeight*1.45f)
                drawTowTruck(truckAt,Math.toDegrees(kotlin.math.atan2(dy,dx).toDouble()).toFloat(),carHeight*1.25f)
            }
        }
        Column(Modifier.align(Alignment.BottomStart).padding(12.dp),verticalArrangement=Arrangement.spacedBy(9.dp)) {
            HoldControl("▲","LEFT GO",Modifier.width(controlWidth).height(controlHeight)){leftForward=it}
            HoldControl("▼","LEFT BACK",Modifier.width(controlWidth).height(controlHeight)){leftBack=it}
        }
        Column(Modifier.align(Alignment.BottomEnd).padding(12.dp),verticalArrangement=Arrangement.spacedBy(9.dp)) {
            HoldControl("▲","RIGHT GO",Modifier.width(controlWidth).height(controlHeight)){rightForward=it}
            HoldControl("▼","RIGHT BACK",Modifier.width(controlWidth).height(controlHeight)){rightBack=it}
        }
        Column(
            Modifier.align(Alignment.TopCenter).padding(top=8.dp).background(Color(0xC9182833),RoundedCornerShape(18.dp)).padding(horizontal=12.dp,vertical=4.dp),
            horizontalAlignment=Alignment.CenterHorizontally,
        ) {
            Row(verticalAlignment=Alignment.CenterVertically) { Text("ROOM ${connection.roomCode}",color=Color.White,fontWeight=FontWeight.ExtraBold);TextButton({onShareInvite(connection.roomCode)}){Text("Share")};TextButton(connection::leave){Text("Leave") } }
            Text(connection.status,color=Color.White,style=MaterialTheme.typography.labelSmall)
        }
        Column(
            Modifier.align(Alignment.TopStart).padding(12.dp).width(190.dp).background(Color(0xC9182833),RoundedCornerShape(14.dp)).padding(10.dp),
        ) {
            Text("HEALTH ${health.toInt()}%",color=Color.White,fontWeight=FontWeight.ExtraBold)
            Spacer(Modifier.height(5.dp))
            Box(Modifier.fillMaxWidth().height(12.dp).background(Color(0xFF401E22),RoundedCornerShape(6.dp))) {
                Box(Modifier.fillMaxWidth((health/100f).coerceIn(0f,1f)).height(12.dp).background(if(health>35)Color(0xFF55D66B) else Color(0xFFFF574D),RoundedCornerShape(6.dp)))
            }
        }
        if(winner!=null) {
            Text(
                "$winner WINS!",
                modifier=Modifier.align(Alignment.Center).background(Color(0xDB172A36),RoundedCornerShape(22.dp)).padding(horizontal=28.dp,vertical=16.dp),
                color=Color(0xFFFFD861),fontWeight=FontWeight.ExtraBold,style=MaterialTheme.typography.headlineMedium,
            )
        } else if(health<=0f) {
            Text("WRECKED",modifier=Modifier.align(Alignment.Center).background(Color(0xDB421D1D),RoundedCornerShape(20.dp)).padding(horizontal=24.dp,vertical=14.dp),color=Color.White,fontWeight=FontWeight.ExtraBold,style=MaterialTheme.typography.headlineMedium)
        }
        if(recovering) {
            Text(
                "TRACK RECOVERY",
                modifier=Modifier.align(Alignment.Center).background(Color(0xD9223440),RoundedCornerShape(18.dp)).padding(horizontal=22.dp,vertical=11.dp),
                color=Color.White,fontWeight=FontWeight.ExtraBold,
            )
        } else if(towSecondsLeft in 1..10) {
            Text(
                "TOW TRUCK IN $towSecondsLeft",
                modifier=Modifier.align(Alignment.Center).background(Color(0xD9223440),RoundedCornerShape(18.dp)).padding(horizontal=22.dp,vertical=11.dp),
                color=Color.White,fontWeight=FontWeight.ExtraBold,
            )
        }
    }
}

@Composable private fun HoldControl(arrow:String,label:String,modifier:Modifier=Modifier,onHeld:(Boolean)->Unit) {
    Box(modifier.background(Color(0xB9223542),RoundedCornerShape(20.dp)).border(2.dp,Color.White.copy(alpha=.65f),RoundedCornerShape(20.dp)).pointerInput(Unit){detectTapGestures(onPress={onHeld(true);tryAwaitRelease();onHeld(false)})},contentAlignment=Alignment.Center) {
        Column(horizontalAlignment=Alignment.CenterHorizontally){Text(arrow,color=Color.White,fontWeight=FontWeight.ExtraBold,style=MaterialTheme.typography.headlineSmall);Text(label,color=Color.White,fontWeight=FontWeight.Bold,style=MaterialTheme.typography.labelSmall)}
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRaceCar(at:Offset,heading:Float,name:String,image:ImageBitmap,carHeight:Float) {
    val carWidth=carHeight*image.width/image.height
    rotate(heading+90,at){
        drawOval(Color(0x66000000),Offset(at.x-carWidth*.44f,at.y+carHeight*.23f),androidx.compose.ui.geometry.Size(carWidth*.95f,carHeight*.28f))
        drawImage(
            image=image,
            srcOffset=IntOffset.Zero,
            srcSize=IntSize(image.width,image.height),
            dstOffset=IntOffset((at.x-carWidth/2).toInt(),(at.y-carHeight/2).toInt()),
            dstSize=IntSize(carWidth.toInt(),carHeight.toInt()),
            filterQuality=FilterQuality.High,
        )
    }
    drawContext.canvas.nativeCanvas.drawText(name,at.x,at.y-carHeight*.57f,android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply{this.color=android.graphics.Color.WHITE;textAlign=android.graphics.Paint.Align.CENTER;textSize=carHeight*.16f;isFakeBoldText=true;setShadowLayer(4f,0f,2f,android.graphics.Color.BLACK)})
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSurfaceEffect(at:Offset,surface:TrackSurface,time:Float,size:Float,intensity:Float) {
    if(intensity<.08f||surface==TrackSurface.ROAD)return
    when(surface){
        TrackSurface.GRASS,TrackSurface.GRAVEL->{
            val color=if(surface==TrackSurface.GRAVEL)Color(0xFFB99C68) else Color(0xFF8C9B61)
            repeat(6){i->val phase=time*3f+i*1.17f;val spread=size*(.16f+i*.045f);val p=Offset(at.x+sin(phase)*spread,at.y+size*(.28f+i*.07f));drawCircle(color.copy(alpha=(.28f-i*.025f)*intensity),size*(.08f+i*.018f),p)}
        }
        TrackSurface.WATER->{
            repeat(7){i->val phase=time*5f+i*.9f;val p=Offset(at.x+sin(phase)*size*.32f,at.y+cos(phase*.7f)*size*.25f);drawCircle(Color(0xFFB9EDFF).copy(alpha=.55f*intensity),size*(.035f+i*.008f),p,style=Stroke(2f))}
        }
        TrackSurface.CURB->{
            repeat(3){i->val phase=time*9f+i*2f;drawCircle(Color(0xFFFFD75A).copy(alpha=.7f*intensity),size*.025f,Offset(at.x+sin(phase)*size*.25f,at.y+cos(phase)*size*.25f))}
        }
        else->Unit
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBurningEffect(at:Offset,size:Float,time:Float) {
    repeat(5){i->
        val phase=time*2.2f+i*1.31f
        val smokeY=at.y-size*(.18f+i*.18f)+(phase%1f)*-size*.16f
        val smokeX=at.x+sin(phase)*size*.18f
        drawCircle(Color(0x66404A4D).copy(alpha=(.42f-i*.055f).coerceAtLeast(.12f)),size*(.16f+i*.035f),Offset(smokeX,smokeY))
    }
    repeat(4){i->
        val flicker=.78f+sin(time*8f+i*1.7f)*.2f
        val flame=Offset(at.x+(i-1.5f)*size*.13f,at.y-size*(.08f+flicker*.18f))
        drawCircle(Color(0xFFE94127),size*.14f*flicker,flame)
        drawCircle(Color(0xFFFFB51E),size*.085f*flicker,Offset(flame.x,flame.y+size*.025f))
        drawCircle(Color(0xFFFFFF84),size*.038f*flicker,Offset(flame.x,flame.y+size*.04f))
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawTowTruck(at:Offset,angle:Float,size:Float) {
    rotate(angle,at) {
        drawRoundRect(Color(0xFFFFC928),Offset(at.x-size*.48f,at.y-size*.31f),androidx.compose.ui.geometry.Size(size*.70f,size*.62f),CornerRadius(size*.10f))
        drawRoundRect(Color(0xFFFFE26C),Offset(at.x+size*.12f,at.y-size*.27f),androidx.compose.ui.geometry.Size(size*.40f,size*.54f),CornerRadius(size*.09f))
        drawRect(Color(0xFF5CB8E8),Offset(at.x+size*.25f,at.y-size*.20f),androidx.compose.ui.geometry.Size(size*.19f,size*.20f))
        drawLine(Color(0xFF353F47),Offset(at.x-size*.38f,at.y-size*.04f),Offset(at.x-size*.82f,at.y-size*.04f),size*.08f)
        drawLine(Color(0xFFFFC928),Offset(at.x-size*.25f,at.y-size*.08f),Offset(at.x-size*.56f,at.y-size*.48f),size*.09f)
        drawLine(Color(0xFF353F47),Offset(at.x-size*.56f,at.y-size*.48f),Offset(at.x-size*.72f,at.y-size*.05f),size*.045f)
        drawCircle(Color(0xFF20272B),size*.14f,Offset(at.x-size*.25f,at.y+size*.30f))
        drawCircle(Color(0xFF20272B),size*.14f,Offset(at.x+size*.30f,at.y+size*.30f))
        drawCircle(Color(0xFFBBC4C8),size*.065f,Offset(at.x-size*.25f,at.y+size*.30f))
        drawCircle(Color(0xFFBBC4C8),size*.065f,Offset(at.x+size*.30f,at.y+size*.30f))
    }
}

private enum class TrackSurface { ROAD, CURB, GRAVEL, GRASS, WATER }
private enum class ImpactKind { TREE, SOLID }

private fun classifySurface(bitmap:Bitmap,x:Float,y:Float):TrackSurface {
    val px=bitmap.getPixel((x*(bitmap.width-1)).toInt().coerceIn(0,bitmap.width-1),(y*(bitmap.height-1)).toInt().coerceIn(0,bitmap.height-1))
    val r=android.graphics.Color.red(px);val g=android.graphics.Color.green(px);val b=android.graphics.Color.blue(px)
    val max=maxOf(r,g,b);val min=minOf(r,g,b);val average=(r+g+b)/3
    if(b>r+24&&g>r+14&&b>85)return TrackSurface.WATER
    if(max-min<44&&average in 34..198)return TrackSurface.ROAD
    if((max-min<48&&average>=198)||(r>145&&r>g*1.35&&r>b*1.28))return TrackSurface.CURB
    if(r>105&&g>82&&b<125&&r>b*1.18)return TrackSurface.GRAVEL
    return TrackSurface.GRASS
}

private data class SolidZone(val left:Float,val top:Float,val right:Float,val bottom:Float) {
    fun contains(x:Float,y:Float)=x in left..right&&y in top..bottom
}

private val solidZones=listOf(
    listOf(SolidZone(.68f,.015f,.94f,.18f),SolidZone(.34f,.54f,.61f,.64f),SolidZone(.31f,.76f,.65f,.845f)),
    listOf(SolidZone(.23f,.01f,.47f,.16f),SolidZone(.28f,.76f,.65f,.94f)),
    listOf(SolidZone(.0f,.0f,.19f,.19f),SolidZone(.49f,.0f,.69f,.14f),SolidZone(.89f,.19f,1f,.80f)),
)

private val bridgeUnderpasses=listOf(
    emptyList(),
    listOf(SolidZone(.555f,.085f,.645f,.325f),SolidZone(.79f,.48f,.89f,.75f)),
    emptyList(),
)

private val bridgeOverlayPolygons=listOf(
    emptyList(),
    listOf(
        listOf(Offset(.589f,.09f),Offset(.637f,.14f),Offset(.608f,.315f),Offset(.563f,.275f)),
        listOf(Offset(.795f,.575f),Offset(.868f,.545f),Offset(.892f,.635f),Offset(.818f,.69f)),
    ),
    emptyList(),
)

private fun isBridgeUnderpass(x:Float,y:Float,variant:Int)=
    bridgeUnderpasses[variant.coerceIn(0,bridgeUnderpasses.lastIndex)].any { it.contains(x,y) }

private fun isSolidEnvironment(bitmap:Bitmap,x:Float,y:Float,variant:Int):Boolean {
    if(isBridgeUnderpass(x,y,variant))return false
    if(solidZones[variant.coerceIn(0,solidZones.lastIndex)].any { it.contains(x,y) })return true
    if(isInsideRoadCorridor(bitmap,x,y))return false
    val cx=(x*(bitmap.width-1)).toInt().coerceIn(0,bitmap.width-1);val cy=(y*(bitmap.height-1)).toInt().coerceIn(0,bitmap.height-1)
    val center=classifySurface(bitmap,x,y)
    if(center==TrackSurface.WATER)return false
    val radius=maxOf(5,bitmap.width/260)
    val colors=listOf(-1 to -1,0 to -1,1 to -1,-1 to 0,0 to 0,1 to 0,-1 to 1,0 to 1,1 to 1).map{(ox,oy)->bitmap.getPixel((cx+ox*radius).coerceIn(0,bitmap.width-1),(cy+oy*radius).coerceIn(0,bitmap.height-1))}
    var roadSamples=0;val brightness=mutableListOf<Int>();var saturated=0
    colors.forEach{pixel->
        val r=android.graphics.Color.red(pixel);val g=android.graphics.Color.green(pixel);val b=android.graphics.Color.blue(pixel);val max=maxOf(r,g,b);val min=minOf(r,g,b);val avg=(r+g+b)/3
        brightness+=avg;if(max-min<48&&avg in 34..205)roadSamples++;if(max-min>85)saturated++
    }
    val paintedRoadMarking=saturated<=2&&brightness.count{it>205}>=2&&brightness.count{it<80}>=2
    if(paintedRoadMarking)return false
    if(roadSamples>=1&&center in setOf(TrackSurface.ROAD,TrackSurface.CURB,TrackSurface.GRAVEL))return false
    val mean=brightness.average();val variance=brightness.sumOf{(it-mean)*(it-mean)}/brightness.size
    val centerPixel=colors[4];val cr=android.graphics.Color.red(centerPixel);val cg=android.graphics.Color.green(centerPixel);val cb=android.graphics.Color.blue(centerPixel)
    val darkTree=cg>cr*1.08&&cg>cb*.82&&mean<95&&variance>180
    val rockOrStructure=variance>720&&mean<150
    val colorfulObject=saturated>=6&&variance>390
    return darkTree||rockOrStructure||colorfulObject
}

private fun isInsideRoadCorridor(bitmap:Bitmap,x:Float,y:Float):Boolean {
    var roadLike=0
    var samples=0
    for(radius in listOf(.012f,.024f)) {
        for(i in 0 until 12) {
            val angle=Math.PI*2.0*i/12.0
            val px=x+cos(angle).toFloat()*radius
            val py=y+sin(angle).toFloat()*radius
            if(px !in 0f..1f||py !in 0f..1f)continue
            samples++
            if(classifySurface(bitmap,px,py) in setOf(TrackSurface.ROAD,TrackSurface.CURB))roadLike++
        }
    }
    return samples>=18&&roadLike>=15
}

private fun environmentImpactKind(bitmap:Bitmap,x:Float,y:Float,variant:Int):ImpactKind {
    if(solidZones[variant.coerceIn(0,solidZones.lastIndex)].any { it.contains(x,y) })return ImpactKind.SOLID
    val px=bitmap.getPixel((x*(bitmap.width-1)).toInt().coerceIn(0,bitmap.width-1),(y*(bitmap.height-1)).toInt().coerceIn(0,bitmap.height-1))
    val r=android.graphics.Color.red(px);val g=android.graphics.Color.green(px);val b=android.graphics.Color.blue(px)
    return if(g>r*1.08&&g>b*.82)ImpactKind.TREE else ImpactKind.SOLID
}

private fun nearestSafeRoadPoint(bitmap:Bitmap,x:Float,y:Float,variant:Int,fallback:Pair<Float,Float>):Pair<Float,Float> {
    fun isClearRoad(px:Float,py:Float):Boolean {
        if(px !in .025f..975f||py !in .045f..955f||isSolidEnvironment(bitmap,px,py,variant))return false
        if(classifySurface(bitmap,px,py) !in setOf(TrackSurface.ROAD,TrackSurface.CURB))return false
        val clearance=.018f
        return listOf(clearance to 0f,-clearance to 0f,0f to clearance,0f to -clearance).all { (dx,dy) ->
            !isSolidEnvironment(bitmap,px+dx,py+dy,variant)&&classifySurface(bitmap,px+dx,py+dy) in setOf(TrackSurface.ROAD,TrackSurface.CURB)
        }
    }
    for(ring in 2..50) {
        val radius=ring*.010f
        val samples=maxOf(24,ring*3)
        for(i in 0 until samples) {
            val angle=Math.PI*2.0*i/samples
            val px=x+cos(angle).toFloat()*radius
            val py=y+sin(angle).toFloat()*radius
            if(isClearRoad(px,py))return px to py
        }
    }
    return fallback
}

private fun moveToward(value:Float,target:Float,amount:Float):Float = when {
    value<target->minOf(value+amount,target)
    value>target->maxOf(value-amount,target)
    else->target
}

private fun moveAngleToward(value:Float,target:Float,fraction:Float):Float {
    val delta=((target-value+540f)%360f)-180f
    return value+delta*fraction.coerceIn(0f,1f)
}
private fun playerColor(index:Int)=listOf(Color(0xFFFF4D32),Color(0xFF3293FF),Color(0xFFFFC928),Color(0xFF9C61F1),Color(0xFF37C96B),Color(0xFFFF63AF))[index%6]

private data class TrackLayout(val points:List<Offset>,val things:List<FunnyThing>)
private data class FunnyThing(val x:Float,val y:Float,val type:Int)

private fun generateTrackLayout(roomCode:String):TrackLayout {
    val random=Random(roomCode.hashCode().toLong())
    val points=(0 until 11).map{i->
        val angle=Math.PI*2*i/11.0
        val rx=.39f+(random.nextFloat()-.5f)*.055f
        val ry=.355f+(random.nextFloat()-.5f)*.055f
        Offset(.5f+cos(angle).toFloat()*rx,.52f+sin(angle).toFloat()*ry)
    }
    val positions=listOf(.07f to .13f,.92f to .12f,.07f to .88f,.93f to .87f,.46f to .47f,.54f to .48f,.47f to .59f,.56f to .60f,.50f to .68f,.50f to .38f,.16f to .50f,.84f to .50f)
    return TrackLayout(points,positions.mapIndexed{i,p->FunnyThing(p.first,p.second,i%5)})
}

private fun smoothClosedPath(points:List<Offset>):Path {
    val path=Path()
    if(points.size<3)return path
    fun middle(a:Offset,b:Offset)=Offset((a.x+b.x)/2f,(a.y+b.y)/2f)
    path.moveTo(middle(points.last(),points.first()).x,middle(points.last(),points.first()).y)
    for(i in points.indices){val current=points[i];val next=points[(i+1)%points.size];val end=middle(current,next);path.quadraticBezierTo(current.x,current.y,end.x,end.y)}
    path.close();return path
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCheckeredFinish(points:List<Offset>,roadWidth:Float) {
    if(points.size<2)return
    val center=Offset((points[0].x+points[1].x)/2f,(points[0].y+points[1].y)/2f)
    val dx=points[1].x-points[0].x;val dy=points[1].y-points[0].y
    val angle=Math.toDegrees(kotlin.math.atan2(dy,dx).toDouble()).toFloat()
    val rows=10;val cell=roadWidth*.088f
    rotate(angle,center){
        for(row in 0 until rows)for(col in 0..1){
            val color=if((row+col)%2==0)Color.White else Color(0xFF171B1E)
            drawRect(color,Offset(center.x-cell,center.y-roadWidth*.44f+row*cell),androidx.compose.ui.geometry.Size(cell,cell))
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawFunnyThing(thing:FunnyThing,at:Offset,scale:Float) {
    val s=scale.coerceIn(.7f,1.5f)
    when(thing.type){
        0->{ // mushroom
            drawRoundRect(Color(0xFFF4DEB0),Offset(at.x-5*s,at.y),androidx.compose.ui.geometry.Size(10*s,17*s),CornerRadius(4*s))
            drawOval(Color(0xFFEB5359),Offset(at.x-16*s,at.y-11*s),androidx.compose.ui.geometry.Size(32*s,18*s))
            drawCircle(Color.White,3*s,Offset(at.x-7*s,at.y-5*s));drawCircle(Color.White,3.5f*s,Offset(at.x+7*s,at.y-3*s))
        }
        1->{ // beach ball
            drawCircle(Color(0xFFFFF4D7),15*s,at);drawArc(Color(0xFFEF584A),-90f,95f,true,Offset(at.x-15*s,at.y-15*s),androidx.compose.ui.geometry.Size(30*s,30*s));drawArc(Color(0xFF4E9EEA),90f,95f,true,Offset(at.x-15*s,at.y-15*s),androidx.compose.ui.geometry.Size(30*s,30*s));drawCircle(Color(0xFFFFD04A),4*s,Offset(at.x,at.y-10*s))
        }
        2->{ // rubber duck
            drawOval(Color(0xFFFFD339),Offset(at.x-17*s,at.y-5*s),androidx.compose.ui.geometry.Size(31*s,20*s));drawCircle(Color(0xFFFFD339),9*s,Offset(at.x+9*s,at.y-8*s));drawCircle(Color(0xFF263033),1.5f*s,Offset(at.x+11*s,at.y-11*s));drawOval(Color(0xFFFF8938),Offset(at.x+16*s,at.y-8*s),androidx.compose.ui.geometry.Size(11*s,6*s))
        }
        3->{ // cone
            val cone=Path().apply{moveTo(at.x,at.y-18*s);lineTo(at.x-11*s,at.y+13*s);lineTo(at.x+11*s,at.y+13*s);close()};drawPath(cone,Color(0xFFF57D2E));drawRect(Color.White,Offset(at.x-7*s,at.y),androidx.compose.ui.geometry.Size(14*s,5*s));drawRoundRect(Color(0xFFD95D27),Offset(at.x-16*s,at.y+12*s),androidx.compose.ui.geometry.Size(32*s,6*s),CornerRadius(2*s))
        }
        else->{ // flower patch
            repeat(6){i->val a=Math.PI*2*i/6;val flower=Offset(at.x+cos(a).toFloat()*11*s,at.y+sin(a).toFloat()*11*s);drawCircle(if(i%2==0)Color(0xFFFF8EB5) else Color(0xFFFFE06B),4*s,flower);drawCircle(Color.White,1.5f*s,flower)};drawCircle(Color(0xFF3A8D4D),5*s,at)
        }
    }
}
