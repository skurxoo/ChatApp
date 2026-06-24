package com.example.chatapp.games

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

class GamePackageManager(private val context: Context) {
    private val root = File(context.filesDir, "game-packs")
    private val preferences = context.getSharedPreferences("installed_games", Context.MODE_PRIVATE)

    fun installedVersion(gameId: String): String? {
        val version = preferences.getString(gameId, null)
        return version?.takeIf { File(root, "$gameId/index.html").isFile }
    }

    fun launchUrl(gameId: String): String? = File(root, "$gameId/index.html")
        .takeIf(File::isFile)
        ?.toURI()
        ?.toString()

    suspend fun install(game: GameDefinition) = withContext(Dispatchers.IO) {
        val packageUrl = requireNotNull(game.packageUrl) { "Missing game package URL" }
        require(game.id.matches(Regex("[a-z0-9][a-z0-9-]{0,39}"))) { "Invalid game ID" }
        root.mkdirs()
        val staging = File(root, ".${game.id}-installing").apply { deleteRecursively(); mkdirs() }
        val connection = (URL(packageUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 30_000
            useCaches = false
        }
        try {
            if (connection.responseCode !in 200..299) error("Download failed (${connection.responseCode})")
            val declaredSize = connection.contentLengthLong
            if (declaredSize > MAX_PACKAGE_BYTES) error("Game package is too large")
            var received = 0L
            ZipInputStream(BufferedInputStream(connection.inputStream)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val destination = File(staging, entry.name).canonicalFile
                    if (!destination.path.startsWith(staging.canonicalPath + File.separator)) error("Unsafe game package")
                    if (entry.isDirectory) destination.mkdirs() else {
                        destination.parentFile?.mkdirs()
                        destination.outputStream().buffered().use { output ->
                            val buffer = ByteArray(16 * 1024)
                            while (true) {
                                val count = zip.read(buffer)
                                if (count < 0) break
                                received += count
                                if (received > MAX_PACKAGE_BYTES) error("Game package is too large")
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }
            require(File(staging, "index.html").isFile) { "Game package has no index.html" }
            val destination = File(root, game.id)
            val backup = File(root, ".${game.id}-previous").apply { deleteRecursively() }
            if (destination.exists() && !destination.renameTo(backup)) error("Could not prepare game update")
            if (!staging.renameTo(destination)) {
                backup.renameTo(destination)
                error("Could not install game")
            }
            backup.deleteRecursively()
            preferences.edit().putString(game.id, game.version).apply()
        } finally {
            connection.disconnect()
            staging.deleteRecursively()
        }
    }

    companion object {
        private const val MAX_PACKAGE_BYTES = 100L * 1024 * 1024
    }
}
