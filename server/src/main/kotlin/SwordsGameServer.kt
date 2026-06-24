import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Base64
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

private const val MAX_SWORD_PLAYERS = 6
private val swordRooms = ConcurrentHashMap<String, SwordRoom>()
private val swordRoundScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
private data class SwordState(var x:Float=0f,var y:Float=0f,var face:Float=1f,var angle:Float=-.35f,var health:Float=100f,var map:Int=0,var level:Int=1)
private class SwordRoom(val code:String){val members=ConcurrentHashMap<DefaultWebSocketServerSession,String>();val states=ConcurrentHashMap<String,SwordState>();val roundEnding=AtomicBoolean(false);val round=AtomicInteger(1)}

suspend fun handleSwordsSocket(session:DefaultWebSocketServerSession){
    var room:SwordRoom?=null;var username=""
    try{for(frame in session.incoming){if(frame !is Frame.Text)continue;val parts=frame.readText().split('|');when(parts.firstOrNull()){
        "CREATE","QUICK","JOIN"->{
            leaveSwordRoom(session,room,username);username=parts.getOrNull(1)?.let(::swordDecode)?.trim()?.take(24).orEmpty();if(username.isBlank())continue
            room=when(parts[0]){"CREATE"->createSwordRoom();"QUICK"->swordRooms.values.firstOrNull{it.members.size<MAX_SWORD_PLAYERS}?:createSwordRoom();else->parts.getOrNull(2)?.uppercase(Locale.US)?.let(swordRooms::get)}
            val selected=room;if(selected==null||selected.members.size>=MAX_SWORD_PLAYERS){session.send("ERROR|Room not found or full");room=null;continue}
            if(username in selected.members.values)username="${username.take(21)}${Random.nextInt(10,99)}";selected.members[session]=username;selected.states[username]=SwordState();session.send("ROOM|${selected.code}");selected.states.forEach{(n,s)->session.send(swordStateMessage(n,s))};broadcastSwordPlayers(selected)
        }
        "STATE"->{val selected=room?:continue;if(selected.members[session]!=username||parts.size!=8)continue;val old=selected.states[username]?:SwordState();val state=SwordState(parts[1].toFloatOrNull()?.coerceIn(-800f,800f)?:continue,parts[2].toFloatOrNull()?.coerceIn(-400f,20f)?:continue,parts[3].toFloatOrNull()?.coerceIn(-1f,1f)?:continue,parts[4].toFloatOrNull()?.coerceIn(-7f,7f)?:continue,minOf(old.health,parts[5].toFloatOrNull()?.coerceIn(0f,100f)?:continue),parts[6].toIntOrNull()?.coerceIn(0,6)?:continue,parts[7].toIntOrNull()?.coerceIn(1,3)?:continue);selected.states[username]=state;broadcastSword(selected,swordStateMessage(username,state));maybeFinishSwordRound(selected)}
        "HIT"->{val selected=room?:continue;if(selected.members[session]!=username||selected.roundEnding.get())continue;val target=parts.getOrNull(1)?.let(::swordDecode)?:continue;val damage=parts.getOrNull(2)?.toFloatOrNull()?.coerceIn(1f,25f)?:continue;val state=selected.states[target]?:continue;state.health=(state.health-damage).coerceAtLeast(0f);selected.members.entries.firstOrNull{it.value==target}?.key?.send("DAMAGE|${state.health}");broadcastSword(selected,swordStateMessage(target,state));maybeFinishSwordRound(selected)}
        "LEAVE"->{leaveSwordRoom(session,room,username);room=null}
    }}}finally{leaveSwordRoom(session,room,username)}
}
private fun createSwordRoom():SwordRoom{val alphabet="ABCDEFGHJKLMNPQRSTUVWXYZ";while(true){val code=buildString{repeat(3){append(alphabet.random())}};val room=SwordRoom(code);if(swordRooms.putIfAbsent(code,room)==null)return room}}
private suspend fun maybeFinishSwordRound(room:SwordRoom){val players=room.members.values.toSet();if(players.size<=1)return;val alive=players.filter{(room.states[it]?.health?:0f)>0f};if(alive.size>1||!room.roundEnding.compareAndSet(false,true))return;val winner=alive.singleOrNull().orEmpty();broadcastSword(room,"ROUND|${swordEncode(winner)}|5");swordRoundScope.launch{delay(5_000);if(swordRooms[room.code]===room){room.states.values.forEach{it.health=100f};val next=room.round.incrementAndGet();broadcastSword(room,"RESET|$next");delay(250);room.roundEnding.set(false)}}}
private suspend fun leaveSwordRoom(session:DefaultWebSocketServerSession,room:SwordRoom?,username:String){if(room==null)return;room.members.remove(session);room.states.remove(username);if(room.members.isEmpty())swordRooms.remove(room.code,room)else{broadcastSword(room,"LEFT|${swordEncode(username)}");broadcastSwordPlayers(room)}}
private suspend fun broadcastSwordPlayers(room:SwordRoom)=broadcastSword(room,"PLAYERS|${room.members.values.distinct().joinToString("|"){swordEncode(it)}}")
private suspend fun broadcastSword(room:SwordRoom,message:String){room.members.keys.toList().forEach{member->runCatching{member.send(message)}.onFailure{room.members.remove(member)}}}
private fun swordStateMessage(name:String,s:SwordState)="STATE|${swordEncode(name)}|${s.x}|${s.y}|${s.face}|${s.angle}|${s.health}|${s.map}|${s.level}"
private fun swordEncode(value:String)=Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))
private fun swordDecode(value:String)=Base64.getUrlDecoder().decode(value).toString(Charsets.UTF_8)
