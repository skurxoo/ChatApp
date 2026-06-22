import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import java.util.Base64
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

private const val MAX_RACE_PLAYERS = 6
private val raceRooms = ConcurrentHashMap<String, RaceRoom>()

private data class RaceState(var x: Float = .5f, var y: Float = .78f, var heading: Float = 180f, var health: Float = 100f)
private class RaceRoom(val code: String) {
    val members = ConcurrentHashMap<DefaultWebSocketServerSession, String>()
    val states = ConcurrentHashMap<String, RaceState>()
}

suspend fun handleRaceGameSocket(session: DefaultWebSocketServerSession) {
    var room: RaceRoom? = null
    var username = ""
    try {
        for (frame in session.incoming) {
            if (frame !is Frame.Text) continue
            val parts = frame.readText().split('|')
            when (parts.firstOrNull()) {
                "CREATE", "QUICK", "JOIN" -> {
                    val decodedName = parts.getOrNull(1)?.let(::raceDecode)?.trim()?.take(24).orEmpty()
                    if (decodedName.isBlank()) continue
                    leaveRaceRoom(session, room, username)
                    username = decodedName
                    room = when (parts[0]) {
                        "CREATE" -> createRaceRoom()
                        "QUICK" -> raceRooms.values.firstOrNull { it.members.size < MAX_RACE_PLAYERS } ?: createRaceRoom()
                        else -> parts.getOrNull(2)?.uppercase(Locale.US)?.let(raceRooms::get)
                    }
                    val selected = room
                    if (selected == null || selected.members.size >= MAX_RACE_PLAYERS) {
                        session.send("ERROR|Room not found or full")
                        room = null
                        continue
                    }
                    selected.members[session] = username
                    selected.states.putIfAbsent(username, RaceState())
                    session.send("ROOM|${selected.code}")
                    selected.states.forEach { (name, state) ->
                        session.send(raceStateMessage(name, state))
                    }
                    broadcastRacePlayers(selected)
                }
                "STATE" -> {
                    val selected = room ?: continue
                    if (selected.members[session] != username || parts.size != 5) continue
                    val x = parts[1].toFloatOrNull()?.coerceIn(.02f, .98f) ?: continue
                    val y = parts[2].toFloatOrNull()?.coerceIn(.04f, .96f) ?: continue
                    val heading = parts[3].toFloatOrNull()?.rem(360f) ?: continue
                    val health = parts[4].toFloatOrNull()?.coerceIn(0f, 100f) ?: continue
                    val state = RaceState(x, y, heading, health)
                    selected.states[username] = state
                    broadcastRace(selected, raceStateMessage(username, state))
                }
                "LEAVE" -> {
                    leaveRaceRoom(session, room, username)
                    room = null
                }
            }
        }
    } finally {
        leaveRaceRoom(session, room, username)
    }
}

private fun createRaceRoom(): RaceRoom {
    while (true) {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ"
        val code = buildString(3) { repeat(3) { append(alphabet[Random.nextInt(alphabet.length)]) } }
        val room = RaceRoom(code)
        if (raceRooms.putIfAbsent(code, room) == null) return room
    }
}

private suspend fun leaveRaceRoom(session: DefaultWebSocketServerSession, room: RaceRoom?, username: String) {
    if (room == null) return
    room.members.remove(session)
    if (username.isNotBlank() && username !in room.members.values) room.states.remove(username)
    if (room.members.isEmpty()) raceRooms.remove(room.code, room)
    else broadcastRacePlayers(room)
}

private suspend fun broadcastRacePlayers(room: RaceRoom) {
    val names = room.members.values.distinct().sortedBy { it.lowercase() }
    broadcastRace(room, "PLAYERS|${names.joinToString(",") { raceEncode(it) }}")
}

private suspend fun broadcastRace(room: RaceRoom, message: String) {
    room.members.keys.toList().forEach { member ->
        runCatching { member.send(message) }.onFailure { room.members.remove(member) }
    }
}

private fun raceStateMessage(username: String, state: RaceState): String =
    "STATE|${raceEncode(username)}|${state.x}|${state.y}|${state.heading}|${state.health}"

private fun raceEncode(value: String): String = Base64.getUrlEncoder().withoutPadding()
    .encodeToString(value.toByteArray(Charsets.UTF_8))

private fun raceDecode(value: String): String = Base64.getUrlDecoder().decode(value).toString(Charsets.UTF_8)
