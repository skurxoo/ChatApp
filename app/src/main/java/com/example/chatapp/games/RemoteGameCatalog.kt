package com.example.chatapp.games

import android.graphics.Color
import com.example.chatapp.games.web.WebGameActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

object RemoteGameCatalog {
    private const val SERVER_ORIGIN = "https://somechatapp.ddns.net"

    suspend fun load(): List<GameDefinition> = withContext(Dispatchers.IO) {
        val connection = (URL("$SERVER_ORIGIN/games/catalog").openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 8_000
            useCaches = false
        }
        try {
            if (connection.responseCode !in 200..299) error("Game catalog unavailable")
            val array = JSONArray(connection.inputStream.bufferedReader().use { it.readText() })
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    val id = item.getString("id")
                    val launchPath = item.getString("launchPath")
                    val packagePath = item.getString("packagePath")
                    if (!id.matches(Regex("[a-z0-9][a-z0-9-]{0,39}")) ||
                        !launchPath.startsWith("/games/$id/") || packagePath != "/games/$id/download"
                    ) continue
                    val color = runCatching { Color.parseColor(item.optString("accentColor", "#EF584A")) }
                        .getOrDefault(Color.rgb(239, 88, 74)).toLong() and 0xFFFFFFFFL
                    add(
                        GameDefinition(
                            id = id,
                            name = item.getString("name").take(50),
                            version = item.getString("version").take(24),
                            description = item.getString("description").take(300),
                            shortDescription = item.getString("shortDescription").take(50),
                            playerCount = item.getString("playerCount").take(30),
                            iconLetter = item.getString("iconLetter").take(2),
                            accentColor = color,
                            activityClass = WebGameActivity::class.java,
                            delivery = GameDelivery.SERVER,
                            launchUrl = "$SERVER_ORIGIN$launchPath",
                            packageUrl = "$SERVER_ORIGIN$packagePath",
                        )
                    )
                }
            }
        } finally {
            connection.disconnect()
        }
    }
}
