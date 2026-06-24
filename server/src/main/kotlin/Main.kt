import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.application.call
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.receiveStream
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.delete
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Base64
import java.util.Properties
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.io.BufferedOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private const val PORT = 8080
private const val MAX_FILE_SIZE = 100L * 1024 * 1024
private const val MAX_TOTAL_STORAGE = 500L * 1024 * 1024
private const val HISTORY_RETENTION_MS = 24L * 60 * 60 * 1000
private const val MAX_HISTORY_EVENTS = 10_000
private val clients = ConcurrentHashMap<DefaultWebSocketServerSession, String>()
private val broadcastMutex = Mutex()
private val serverDataDir: Path = System.getenv("CHATAPP_DATA_DIR")
    ?.let(Path::of)
    ?.toAbsolutePath()
    ?.normalize()
    ?: Path.of("server-data").toAbsolutePath().normalize()
private val publicFilesDir: Path = serverDataDir.resolve("public")
private val gamePacksDir: Path = System.getenv("CHATAPP_GAMES_DIR")
    ?.let(Path::of)
    ?.toAbsolutePath()
    ?.normalize()
    ?: listOf(Path.of("game-packs"), Path.of("server", "game-packs"))
        .map { it.toAbsolutePath().normalize() }
        .firstOrNull(Files::isDirectory)
    ?: Path.of("game-packs").toAbsolutePath().normalize()

fun main() {
    Files.createDirectories(publicFilesDir)
    Files.createDirectories(gamePacksDir)
    HistoryStore.initialize()
    embeddedServer(Netty, host = "127.0.0.1", port = PORT) {
        install(WebSockets) {
            // Mobile radios can briefly sleep or switch between Wi-Fi and cellular.
            // Keep the heartbeat, but allow enough time for those short interruptions.
            pingPeriodMillis = 30_000
            timeoutMillis = 120_000
        }

        routing {
            webSocket("/chat") {
                clients[this] = "Guest"
                var username = "Guest"

                try {
                    for (frame in incoming) {
                        if (frame !is Frame.Text) continue

                        val incomingMessage = ChatProtocol.decodeClientMessage(frame.readText()) ?: continue
                        if (incomingMessage.isJoin) {
                            username = incomingMessage.username
                            clients[this] = username
                            sendRecentHistory(this)
                            recordAndBroadcast(
                                ChatProtocol.encodeServerMessage(
                                    type = "SYSTEM",
                                    username = "ChatApp",
                                    text = "$username joined the chat",
                                ),
                            )
                            broadcastPresence()
                        } else {
                            val registeredUsername = clients[this]
                            if (registeredUsername.isNullOrBlank() || registeredUsername == "Guest") continue
                            username = registeredUsername
                            recordAndBroadcast(
                                ChatProtocol.encodeServerMessage(
                                    type = "MESSAGE",
                                    username = username,
                                    text = incomingMessage.text,
                                ),
                            )
                        }
                    }
                } finally {
                    clients.remove(this)
                    if (username != "Guest") {
                        recordAndBroadcast(
                            ChatProtocol.encodeServerMessage(
                                type = "SYSTEM",
                                username = "ChatApp",
                                text = "$username left the chat",
                            ),
                        )
                    }
                    broadcastPresence()
                }
            }

            webSocket("/game") {
                handleRaceGameSocket(this)
            }

            webSocket("/swords") {
                handleSwordsSocket(this)
            }

            get("/files") {
                val files = Files.list(publicFilesDir).use { stream ->
                    stream.iterator().asSequence()
                        .filter { Files.isRegularFile(it) }
                        .sortedByDescending { Files.getLastModifiedTime(it).toMillis() }
                        .joinToString("\n") { path ->
                            listOf(
                                encodeText(path.fileName.toString()),
                                Files.size(path).toString(),
                                Files.getLastModifiedTime(path).toMillis().toString(),
                            ).joinToString("|")
                        }
                }
                call.respondText(files, ContentType.Text.Plain)
            }

            get("/health") {
                call.response.header(HttpHeaders.AccessControlAllowOrigin, "*")
                call.respondText("ok", ContentType.Text.Plain)
            }

            get("/games/catalog") {
                call.response.header(HttpHeaders.CacheControl, "no-store")
                call.respondText(gameCatalogJson(), ContentType.Application.Json)
            }

            get("/games/{gameId}/download") {
                val gameId = call.parameters["gameId"].orEmpty()
                val gameRoot = resolveGameRoot(gameId)
                if (gameRoot == null) {
                    call.respond(HttpStatusCode.NotFound, "Game not found")
                    return@get
                }
                val archive = withContext(Dispatchers.IO) { createGameArchive(gameRoot, gameId) }
                try {
                    call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"$gameId.zip\"")
                    call.respondFile(archive.toFile())
                } finally {
                    Files.deleteIfExists(archive)
                }
            }

            get("/games/{gameId}") {
                val gameId = call.parameters["gameId"].orEmpty()
                val file = resolveGameAsset(gameId, "index.html")
                if (file == null) call.respond(HttpStatusCode.NotFound, "Game not found") else call.respondFile(file.toFile())
            }

            get("/games/{gameId}/{asset...}") {
                val gameId = call.parameters["gameId"].orEmpty()
                val asset = call.parameters.getAll("asset")?.joinToString("/").orEmpty().ifBlank { "index.html" }
                val file = resolveGameAsset(gameId, asset)
                if (file == null) call.respond(HttpStatusCode.NotFound, "Game file not found") else call.respondFile(file.toFile())
            }

            get("/files/{name}") {
                val name = call.parameters["name"]
                val file = name?.let(::resolvePublicFile)
                if (file == null || !Files.isRegularFile(file)) {
                    call.respond(HttpStatusCode.NotFound, "File not found")
                    return@get
                }

                call.response.header(
                    HttpHeaders.ContentDisposition,
                    "attachment; filename=\"${file.fileName.toString().replace("\"", "")}\"",
                )
                call.respondFile(file.toFile())
            }

            delete("/files/{name}") {
                val name = call.parameters["name"]
                val file = name?.let(::resolvePublicFile)
                if (file == null || !Files.isRegularFile(file)) {
                    call.respond(HttpStatusCode.NotFound, "File not found")
                    return@delete
                }

                val deleted = withContext(Dispatchers.IO) { Files.deleteIfExists(file) }
                if (deleted) call.respond(HttpStatusCode.OK, "File deleted")
                else call.respond(HttpStatusCode.NotFound, "File not found")
            }

            post("/files/upload/{name}") {
                val requestedName = call.parameters["name"]
                val safeName = requestedName?.let(::sanitizeFileName)
                if (safeName.isNullOrBlank() || safeName != requestedName) {
                    call.respond(HttpStatusCode.BadRequest, "Invalid filename")
                    return@post
                }

                val temporaryFile = Files.createTempFile(publicFilesDir, ".upload-", ".tmp")
                try {
                    val uploadStream = call.receiveStream()
                    val destination = withContext(Dispatchers.IO) {
                        var totalBytes = 0L
                        uploadStream.use { input ->
                            Files.newOutputStream(temporaryFile).use { output ->
                                val buffer = ByteArray(16 * 1024)
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    totalBytes += count
                                    if (totalBytes > MAX_FILE_SIZE) throw FileTooLargeException()
                                    output.write(buffer, 0, count)
                                }
                            }
                        }

                        if (currentStorageBytes() > MAX_TOTAL_STORAGE) throw StorageFullException()

                        val destination = nextAvailableFile(safeName)
                        Files.move(temporaryFile, destination, StandardCopyOption.ATOMIC_MOVE)
                        destination
                    }
                    call.respond(HttpStatusCode.Created, destination.fileName.toString())
                } catch (_: FileTooLargeException) {
                    Files.deleteIfExists(temporaryFile)
                    call.respond(HttpStatusCode.PayloadTooLarge, "Maximum file size is 100 MB")
                } catch (_: StorageFullException) {
                    Files.deleteIfExists(temporaryFile)
                    call.respond(HttpStatusCode.InsufficientStorage, "Shared folder storage limit reached")
                } catch (error: Exception) {
                    Files.deleteIfExists(temporaryFile)
                    throw error
                }
            }
        }
    }.start(wait = true)
}

private data class ServerGamePack(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val shortDescription: String,
    val playerCount: String,
    val iconLetter: String,
    val accentColor: String,
)

private val safeGameId = Regex("[a-z0-9][a-z0-9-]{0,39}")

private fun loadGamePacks(): List<ServerGamePack> {
    if (!Files.isDirectory(gamePacksDir)) return emptyList()
    return Files.list(gamePacksDir).use { folders ->
        folders.filter { Files.isDirectory(it) && !it.fileName.toString().startsWith("_") }
            .map { folder ->
                runCatching {
                    val properties = Properties().apply { Files.newInputStream(folder.resolve("game.properties")).use(::load) }
                    val id = properties.getProperty("id").orEmpty().trim()
                    require(id == folder.fileName.toString() && safeGameId.matches(id))
                    require(properties.getProperty("enabled", "true").toBoolean())
                    require(Files.isRegularFile(folder.resolve("index.html")))
                    ServerGamePack(
                        id = id,
                        name = properties.getProperty("name").orEmpty().trim().ifBlank { id },
                        version = properties.getProperty("version", "1.0.0").trim(),
                        description = properties.getProperty("description", "A ChatApp game.").trim(),
                        shortDescription = properties.getProperty("shortDescription", "Server game").trim(),
                        playerCount = properties.getProperty("playerCount", "1 player").trim(),
                        iconLetter = properties.getProperty("iconLetter", id.take(1).uppercase()).trim().take(2),
                        accentColor = properties.getProperty("accentColor", "#EF584A").trim(),
                    )
                }.getOrNull()
            }
            .toList()
            .filterNotNull()
            .sortedBy { it.name.lowercase() }
    }
}

private fun gameCatalogJson(): String = loadGamePacks().joinToString(prefix = "[", postfix = "]") { game ->
    """{"id":"${jsonEscape(game.id)}","name":"${jsonEscape(game.name)}","version":"${jsonEscape(game.version)}","description":"${jsonEscape(game.description)}","shortDescription":"${jsonEscape(game.shortDescription)}","playerCount":"${jsonEscape(game.playerCount)}","iconLetter":"${jsonEscape(game.iconLetter)}","accentColor":"${jsonEscape(game.accentColor)}","launchPath":"/games/${jsonEscape(game.id)}/","packagePath":"/games/${jsonEscape(game.id)}/download"}"""
}

private fun jsonEscape(value: String): String = buildString(value.length + 8) {
    value.forEach { char ->
        when (char) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (char.code < 32) append("\\u%04x".format(char.code)) else append(char)
        }
    }
}

private fun resolveGameAsset(gameId: String, asset: String): Path? {
    if (asset.contains('\\')) return null
    val gameRoot = resolveGameRoot(gameId) ?: return null
    val resolved = gameRoot.resolve(asset).normalize()
    if (!resolved.startsWith(gameRoot) || resolved.fileName.toString() == "game.properties" || !Files.isRegularFile(resolved)) return null
    return resolved
}

private fun resolveGameRoot(gameId: String): Path? {
    if (!safeGameId.matches(gameId)) return null
    val gameRoot = gamePacksDir.resolve(gameId).normalize()
    return gameRoot.takeIf { it.startsWith(gamePacksDir) && Files.isRegularFile(it.resolve("game.properties")) }
}

private fun createGameArchive(gameRoot: Path, gameId: String): Path {
    Files.createDirectories(serverDataDir)
    val archive = Files.createTempFile(serverDataDir, "$gameId-", ".zip")
    try {
        ZipOutputStream(BufferedOutputStream(Files.newOutputStream(archive))).use { zip ->
            Files.walk(gameRoot).use { paths ->
                paths.filter(Files::isRegularFile)
                    .filter { it.fileName.toString() != "game.properties" }
                    .forEach { file ->
                        val name = gameRoot.relativize(file).joinToString("/") { it.toString() }
                        zip.putNextEntry(ZipEntry(name))
                        Files.copy(file, zip)
                        zip.closeEntry()
                    }
            }
        }
        return archive
    } catch (error: Exception) {
        Files.deleteIfExists(archive)
        throw error
    }
}

private class FileTooLargeException : RuntimeException()
private class StorageFullException : RuntimeException()

private fun currentStorageBytes(): Long = Files.list(publicFilesDir).use { stream ->
    stream.iterator().asSequence()
        .filter { Files.isRegularFile(it) }
        .sumOf { Files.size(it) }
}

private fun sanitizeFileName(value: String): String = value
    .substringAfterLast('/')
    .substringAfterLast('\\')
    .replace(Regex("[<>:\"/\\\\|?*\\u0000-\\u001F]"), "_")
    .trim()
    .trimEnd('.')
    .take(120)

private fun resolvePublicFile(name: String): Path? {
    if (sanitizeFileName(name) != name) return null
    return publicFilesDir.resolve(name).normalize().takeIf { it.startsWith(publicFilesDir) }
}

private fun nextAvailableFile(name: String): Path {
    val initial = publicFilesDir.resolve(name)
    if (!Files.exists(initial)) return initial

    val extensionIndex = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
    val baseName = name.substring(0, extensionIndex)
    val extension = name.substring(extensionIndex)
    var number = 2
    while (true) {
        val candidate = publicFilesDir.resolve("$baseName ($number)$extension")
        if (!Files.exists(candidate)) return candidate
        number++
    }
}

private fun encodeText(value: String): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))

private suspend fun broadcast(message: String) {
    broadcastMutex.withLock {
        clients.keys.toList().forEach { client ->
            runCatching { client.send(message) }
                .onFailure { clients.remove(client) }
        }
    }
}

private suspend fun recordAndBroadcast(message: String) {
    HistoryStore.append(message)
    broadcast(message)
}

private suspend fun sendRecentHistory(client: DefaultWebSocketServerSession) {
    val recentEvents = HistoryStore.recent()
    broadcastMutex.withLock {
        recentEvents.forEach { event -> client.send(event) }
    }
}

private suspend fun broadcastPresence() {
    val usernames = clients.values
        .filter { it != "Guest" }
        .distinct()
        .sortedBy { it.lowercase() }
    broadcast(ChatProtocol.encodePresence(usernames))
}

private data class ClientMessage(val username: String, val text: String, val isJoin: Boolean)

private object HistoryStore {
    private val directory = serverDataDir.resolve("history")
    private val file = directory.resolve("events.log")
    private val mutex = Mutex()
    private var lastCleanupAt = 0L

    fun initialize() {
        Files.createDirectories(directory)
        pruneBlocking(System.currentTimeMillis())
    }

    suspend fun append(event: String) {
        mutex.withLock {
            withContext(Dispatchers.IO) {
                Files.writeString(
                    file,
                    event + System.lineSeparator(),
                    Charsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND,
                )
                val now = System.currentTimeMillis()
                if (now - lastCleanupAt >= 60L * 60 * 1000) pruneBlocking(now)
            }
        }
    }

    suspend fun recent(): List<String> = mutex.withLock {
        withContext(Dispatchers.IO) { pruneBlocking(System.currentTimeMillis()) }
    }

    private fun pruneBlocking(now: Long): List<String> {
        if (!Files.exists(file)) {
            lastCleanupAt = now
            return emptyList()
        }

        val cutoff = now - HISTORY_RETENTION_MS
        val retained = Files.readAllLines(file, Charsets.UTF_8)
            .asSequence()
            .filter { event ->
                ChatProtocol.eventTimestamp(event)?.let { it >= cutoff } == true
            }
            .toList()
            .takeLast(MAX_HISTORY_EVENTS)

        Files.write(
            file,
            retained,
            Charsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
        )
        lastCleanupAt = now
        return retained
    }
}

private object ChatProtocol {
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun decodeClientMessage(value: String): ClientMessage? {
        val parts = value.split('|')
        if (parts.size !in 2..3 || parts[0] !in setOf("JOIN", "SEND")) return null

        return runCatching {
            val username = decode(parts[1]).trim().take(24)
            val isJoin = parts[0] == "JOIN"
            val text = if (isJoin) "" else decode(parts[2]).trim().take(2_000)
            if (username.isBlank() || (!isJoin && text.isBlank())) null
            else ClientMessage(username, text, isJoin)
        }.getOrNull()
    }

    fun encodeServerMessage(type: String, username: String, text: String): String =
        listOf(
            type,
            UUID.randomUUID().toString(),
            System.currentTimeMillis().toString(),
            encode(username),
            encode(text),
        ).joinToString("|")

    fun encodePresence(usernames: List<String>): String =
        "PRESENCE|${usernames.joinToString(",") { encode(it) }}"

    fun eventTimestamp(value: String): Long? {
        val parts = value.split('|', limit = 4)
        if (parts.size < 3 || parts[0] !in setOf("MESSAGE", "SYSTEM")) return null
        return parts[2].toLongOrNull()
    }

    private fun encode(value: String): String =
        encoder.encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun decode(value: String): String =
        decoder.decode(value).toString(Charsets.UTF_8)
}
