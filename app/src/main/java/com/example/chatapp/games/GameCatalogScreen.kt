package com.example.chatapp.games

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun GamesScreen(onOpenGame: (GameDefinition) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val packageManager = remember { GamePackageManager(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var remoteGames by remember { mutableStateOf<List<GameDefinition>>(emptyList()) }
    var installedVersions by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var downloadingGameId by remember { mutableStateOf<String?>(null) }
    var catalogStatus by remember { mutableStateOf("Checking server games…") }
    var refreshKey by remember { mutableStateOf(0) }
    LaunchedEffect(refreshKey) {
        catalogStatus = "Checking server games…"
        val result = runCatching { RemoteGameCatalog.load() }
        result.onSuccess { games ->
            remoteGames = games
            installedVersions = games.mapNotNull { game ->
                packageManager.installedVersion(game.id)?.let { game.id to it }
            }.toMap()
            catalogStatus = "${games.size} server game${if (games.size == 1) "" else "s"} available"
        }
            .onFailure { catalogStatus = "Server games unavailable" }
    }
    val nativeIds = GameCatalog.games.mapTo(mutableSetOf()) { it.id }
    val allGames = GameCatalog.games + remoteGames.filterNot { it.id in nativeIds }

    Column(modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Games", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
        Text("Choose a game to open it full screen.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(catalogStatus, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
            TextButton(onClick = { refreshKey++ }) { Text("Refresh") }
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            items(allGames, key = { it.id }) { game ->
            Card(shape = RoundedCornerShape(24.dp), elevation = CardDefaults.cardElevation(4.dp)) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(64.dp).background(Color(game.accentColor), RoundedCornerShape(18.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(game.iconLetter, color = Color.White, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.ExtraBold)
                        }
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text(game.name, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleLarge)
                            Text("${game.shortDescription} • ${game.playerCount}")
                            Text(
                                "Game v${game.version} • ${if (game.delivery == GameDelivery.SERVER) {
                                    val installed = installedVersions[game.id]
                                    when {
                                        installed == null -> "Not downloaded"
                                        installed == game.version -> "Downloaded"
                                        else -> "Update available"
                                    }
                                } else "Bundled"}",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                    Text(game.description, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(
                        onClick = {
                            if (game.delivery == GameDelivery.BUNDLED) {
                                onOpenGame(game)
                            } else {
                                val installed = installedVersions[game.id]
                                if (installed == game.version) {
                                    packageManager.launchUrl(game.id)?.let { onOpenGame(game.copy(launchUrl = it)) }
                                } else {
                                    downloadingGameId = game.id
                                    catalogStatus = "Downloading ${game.name}…"
                                    scope.launch {
                                        runCatching { packageManager.install(game) }
                                            .onSuccess {
                                                installedVersions = installedVersions + (game.id to game.version)
                                                catalogStatus = "${game.name} is ready to play"
                                            }
                                            .onFailure {
                                                catalogStatus = "Could not download ${game.name}: ${it.message.orEmpty()}"
                                            }
                                        downloadingGameId = null
                                    }
                                }
                            }
                        },
                        enabled = downloadingGameId == null,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        contentPadding = PaddingValues(vertical = 14.dp),
                    ) {
                        val installed = installedVersions[game.id]
                        Text(
                            when {
                                downloadingGameId == game.id -> "Downloading…"
                                game.delivery == GameDelivery.BUNDLED || installed == game.version -> "Play ${game.name}"
                                installed == null -> "Download ${game.name}"
                                else -> "Update ${game.name}"
                            },
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
        }
    }
}
