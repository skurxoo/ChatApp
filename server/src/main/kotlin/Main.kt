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
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

private const val PORT = 8081
private const val MAX_FILE_SIZE = 100L * 1024 * 1024
private const val MAX_TOTAL_STORAGE = 500L * 1024 * 1024
private val clients = ConcurrentHashMap<DefaultWebSocketServerSession, String>()
private val broadcastMutex = Mutex()
private val publicFilesDir: Path = Path.of("server-data", "public").toAbsolutePath().normalize()

fun main() {
    Files.createDirectories(publicFilesDir)
    embeddedServer(Netty, host = "127.0.0.1", port = PORT) {
        install(WebSockets) {
            pingPeriodMillis = 15_000
            timeoutMillis = 30_000
        }

        routing {
            webSocket("/chat") {
                clients[this] = "Guest"
                var username = "Guest"

                try {
                    for (frame in incoming) {
                        if (frame !is Frame.Text) continue

                        val incomingMessage = ChatProtocol.decodeClientMessage(frame.readText()) ?: continue
                        username = incomingMessage.username
                        if (incomingMessage.isJoin) clients[this] = username
                        val type = if (incomingMessage.isJoin) "SYSTEM" else "MESSAGE"
                        val sender = if (incomingMessage.isJoin) "ChatApp" else username
                        val text = if (incomingMessage.isJoin) "$username joined the chat" else incomingMessage.text
                        broadcast(ChatProtocol.encodeServerMessage(type, sender, text))
                        if (incomingMessage.isJoin) broadcastPresence()
                    }
                } finally {
                    clients.remove(this)
                    if (username != "Guest") {
                        broadcast(
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

private suspend fun broadcastPresence() {
    val usernames = clients.values
        .filter { it != "Guest" }
        .distinct()
        .sortedBy { it.lowercase() }
    broadcast(ChatProtocol.encodePresence(usernames))
}

private data class ClientMessage(val username: String, val text: String, val isJoin: Boolean)

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

    private fun encode(value: String): String =
        encoder.encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun decode(value: String): String =
        decoder.decode(value).toString(Charsets.UTF_8)
}
