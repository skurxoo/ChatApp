package com.example.chatapp

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.chatapp.ui.theme.ChatAppTheme
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.net.URI
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.DateFormat
import java.util.Date
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {
    private val messages = mutableStateListOf<ChatMessage>()
    private val onlineUsers = mutableStateListOf<String>()
    private val sharedFiles = mutableStateListOf<SharedFile>()
    private var connectionState by mutableStateOf(ConnectionState.DISCONNECTED)
    private var fileTransferStatus by mutableStateOf("")
    private var client: WebSocketClient? = null
    private var currentUsername = ""
    private val reconnectHandler = Handler(Looper.getMainLooper())
    private var reconnectAttempt = 0
    private var shouldStayConnected = false
    private val reconnectRunnable = Runnable {
        if (shouldStayConnected && currentUsername.isNotBlank()) openConnection()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            ChatAppTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ChatApp(
                        messages = messages,
                        onlineUsers = onlineUsers,
                        sharedFiles = sharedFiles,
                        connectionState = connectionState,
                        fileTransferStatus = fileTransferStatus,
                        onJoin = ::connect,
                        onSend = ::sendMessage,
                        onRefreshFiles = ::refreshFiles,
                        onUploadFile = ::uploadFile,
                        onDownloadFile = ::downloadFile,
                        onDeleteFile = ::deleteFile,
                    )
                }
            }
        }
    }

    private fun connect(username: String) {
        currentUsername = username.trim().take(24)
        if (currentUsername.isBlank()) return
        shouldStayConnected = true
        reconnectAttempt = 0
        openConnection()
    }

    private fun openConnection() {
        if (!shouldStayConnected || client?.isOpen == true || connectionState == ConnectionState.CONNECTING) return

        reconnectHandler.removeCallbacks(reconnectRunnable)
        connectionState = ConnectionState.CONNECTING
        val newClient = object : WebSocketClient(URI(SERVER_URL)) {
            override fun onOpen(handshakedata: ServerHandshake?) {
                send(ChatProtocol.encodeJoin(currentUsername))
                runOnUiThread {
                    if (client !== this) return@runOnUiThread
                    reconnectAttempt = 0
                    connectionState = ConnectionState.CONNECTED
                }
            }

            override fun onMessage(message: String?) {
                val value = message ?: return
                val presence = ChatProtocol.decodePresence(value)
                if (presence != null) {
                    runOnUiThread {
                        onlineUsers.clear()
                        onlineUsers.addAll(presence)
                    }
                    return
                }
                val decoded = ChatProtocol.decodeServerMessage(value) ?: return
                runOnUiThread {
                    if (messages.none { it.id == decoded.id }) messages += decoded
                }
            }

            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                runOnUiThread {
                    if (client !== this) return@runOnUiThread
                    client = null
                    scheduleReconnect()
                }
            }

            override fun onError(ex: Exception?) {
                runOnUiThread {
                    if (client !== this) return@runOnUiThread
                    connectionState = ConnectionState.ERROR
                    scheduleReconnect()
                }
            }
        }
        newClient.setConnectionLostTimeout(90)
        client = newClient
        newClient.connect()
    }

    private fun scheduleReconnect() {
        if (!shouldStayConnected || isFinishing || isDestroyed) {
            connectionState = ConnectionState.DISCONNECTED
            return
        }

        reconnectHandler.removeCallbacks(reconnectRunnable)
        val delaySeconds = when (reconnectAttempt.coerceAtMost(5)) {
            0 -> 2L
            1 -> 4L
            2 -> 8L
            3 -> 15L
            else -> 30L
        }
        reconnectAttempt++
        connectionState = ConnectionState.RECONNECTING
        reconnectHandler.postDelayed(reconnectRunnable, delaySeconds * 1_000)
    }

    private fun sendMessage(text: String) {
        val socket = client ?: return
        if (!socket.isOpen || text.isBlank()) return
        socket.send(ChatProtocol.encodeClientMessage(currentUsername, text.trim()))
    }

    private fun refreshFiles() {
        fileTransferStatus = "Loading files..."
        thread(name = "refresh-files") {
            runCatching {
                val connection = openHttpConnection("$HTTP_BASE_URL/files")
                try {
                    if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                        error("Server returned ${connection.responseCode}")
                    }
                    ChatProtocol.decodeFileList(connection.inputStream.bufferedReader().use { it.readText() })
                } finally {
                    connection.disconnect()
                }
            }.onSuccess { files ->
                runOnUiThread {
                    sharedFiles.clear()
                    sharedFiles.addAll(files)
                    fileTransferStatus = ""
                }
            }.onFailure {
                runOnUiThread { fileTransferStatus = "Could not load files" }
            }
        }
    }

    private fun uploadFile(uri: Uri) {
        val fileName = displayName(uri) ?: "shared-file"
        fileTransferStatus = "Uploading $fileName..."
        thread(name = "upload-file") {
            runCatching {
                val encodedName = URLEncoder.encode(fileName, Charsets.UTF_8.name()).replace("+", "%20")
                val connection = openHttpConnection("$HTTP_BASE_URL/files/upload/$encodedName").apply {
                    requestMethod = "POST"
                    doOutput = true
                    setChunkedStreamingMode(16 * 1024)
                    setRequestProperty("Content-Type", "application/octet-stream")
                }
                try {
                    contentResolver.openInputStream(uri)?.use { input ->
                        connection.outputStream.use { output -> input.copyTo(output, 16 * 1024) }
                    } ?: error("Could not open selected file")
                    if (connection.responseCode !in 200..299) {
                        val detail = connection.errorStream?.bufferedReader()?.use { it.readText() }
                        error(detail ?: "Upload failed")
                    }
                } finally {
                    connection.disconnect()
                }
            }.onSuccess {
                runOnUiThread {
                    fileTransferStatus = "$fileName uploaded"
                    refreshFiles()
                }
            }.onFailure { error ->
                runOnUiThread { fileTransferStatus = error.message ?: "Upload failed" }
            }
        }
    }

    private fun downloadFile(file: SharedFile, destination: Uri) {
        fileTransferStatus = "Downloading ${file.name}..."
        thread(name = "download-file") {
            runCatching {
                val encodedName = URLEncoder.encode(file.name, Charsets.UTF_8.name()).replace("+", "%20")
                val connection = openHttpConnection("$HTTP_BASE_URL/files/$encodedName")
                try {
                    if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                        error("Download failed")
                    }
                    contentResolver.openOutputStream(destination)?.use { output ->
                        connection.inputStream.use { input -> input.copyTo(output, 16 * 1024) }
                    } ?: error("Could not create destination file")
                } finally {
                    connection.disconnect()
                }
            }.onSuccess {
                runOnUiThread { fileTransferStatus = "${file.name} downloaded" }
            }.onFailure { error ->
                runOnUiThread { fileTransferStatus = error.message ?: "Download failed" }
            }
        }
    }

    private fun deleteFile(file: SharedFile) {
        fileTransferStatus = "Deleting ${file.name}..."
        thread(name = "delete-file") {
            runCatching {
                val encodedName = URLEncoder.encode(file.name, Charsets.UTF_8.name()).replace("+", "%20")
                val connection = openHttpConnection("$HTTP_BASE_URL/files/$encodedName").apply {
                    requestMethod = "DELETE"
                }
                try {
                    if (connection.responseCode !in 200..299) error("Delete failed")
                } finally {
                    connection.disconnect()
                }
            }.onSuccess {
                runOnUiThread {
                    fileTransferStatus = "${file.name} deleted"
                    refreshFiles()
                }
            }.onFailure { error ->
                runOnUiThread { fileTransferStatus = error.message ?: "Delete failed" }
            }
        }
    }

    private fun displayName(uri: Uri): String? = contentResolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME),
        null,
        null,
        null,
    )?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }

    private fun openHttpConnection(address: String): HttpURLConnection =
        (URL(address).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 60_000
        }

    override fun onDestroy() {
        shouldStayConnected = false
        reconnectHandler.removeCallbacks(reconnectRunnable)
        client?.close()
        client = null
        super.onDestroy()
    }

    companion object {
        private const val SERVER_URL = "wss://somechatapp.ddns.net/chat"
        private const val HTTP_BASE_URL = "https://somechatapp.ddns.net"
    }
}

private enum class ConnectionState(val label: String) {
    DISCONNECTED("Disconnected"),
    CONNECTING("Connecting..."),
    RECONNECTING("Reconnecting..."),
    CONNECTED("Connected"),
    ERROR("Connection failed"),
}

private data class ChatMessage(
    val id: String,
    val timestamp: Long,
    val username: String,
    val text: String,
    val isSystem: Boolean,
)

private data class SharedFile(
    val name: String,
    val size: Long,
    val modifiedAt: Long,
)

@Composable
private fun ChatApp(
    messages: List<ChatMessage>,
    onlineUsers: List<String>,
    sharedFiles: List<SharedFile>,
    connectionState: ConnectionState,
    fileTransferStatus: String,
    onJoin: (String) -> Unit,
    onSend: (String) -> Unit,
    onRefreshFiles: () -> Unit,
    onUploadFile: (Uri) -> Unit,
    onDownloadFile: (SharedFile, Uri) -> Unit,
    onDeleteFile: (SharedFile) -> Unit,
) {
    var username by rememberSaveable { mutableStateOf("") }
    var hasJoined by rememberSaveable { mutableStateOf(false) }

    if (!hasJoined) {
        JoinScreen(
            username = username,
            onUsernameChange = { username = it.take(24) },
            onJoin = {
                if (username.isNotBlank()) {
                    hasJoined = true
                    onJoin(username)
                }
            },
        )
    } else {
        ChatScreen(
            username = username.trim(),
            messages = messages,
            onlineUsers = onlineUsers,
            sharedFiles = sharedFiles,
            connectionState = connectionState,
            fileTransferStatus = fileTransferStatus,
            onReconnect = { onJoin(username) },
            onSend = onSend,
            onRefreshFiles = onRefreshFiles,
            onUploadFile = onUploadFile,
            onDownloadFile = onDownloadFile,
            onDeleteFile = onDeleteFile,
        )
    }
}

@Composable
private fun JoinScreen(
    username: String,
    onUsernameChange: (String) -> Unit,
    onJoin: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "C",
                color = MaterialTheme.colorScheme.onPrimary,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.ExtraBold,
            )
        }
        Spacer(Modifier.height(18.dp))
        Text(
            "ChatApp",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.ExtraBold
        )
        Text("A little place to talk.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(28.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        ) {
            Column(Modifier.padding(20.dp)) {
                Text("What should we call you?", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = username,
                    onValueChange = onUsernameChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Your name") },
                    shape = RoundedCornerShape(16.dp),
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = onJoin,
                    enabled = username.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    contentPadding = PaddingValues(vertical = 14.dp),
                ) {
                    Text("Join our chat", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun ChatScreen(
    username: String,
    messages: List<ChatMessage>,
    onlineUsers: List<String>,
    sharedFiles: List<SharedFile>,
    connectionState: ConnectionState,
    fileTransferStatus: String,
    onReconnect: () -> Unit,
    onSend: (String) -> Unit,
    onRefreshFiles: () -> Unit,
    onUploadFile: (Uri) -> Unit,
    onDownloadFile: (SharedFile, Uri) -> Unit,
    onDeleteFile: (SharedFile) -> Unit,
) {
    var input by remember { mutableStateOf("") }
    var showPeople by rememberSaveable { mutableStateOf(false) }
    var selectedTab by rememberSaveable { mutableStateOf(0) }
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }
    LaunchedEffect(selectedTab) {
        if (selectedTab == 1) onRefreshFiles()
    }

    Scaffold(
        topBar = {
            Column {
                ChatHeader(
                    connectionState = connectionState,
                    onlineCount = onlineUsers.size,
                    onShowPeople = { showPeople = true },
                    onReconnect = onReconnect,
                )
                TabRow(selectedTabIndex = selectedTab) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("Chat") },
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("Files (${sharedFiles.size})") },
                    )
                }
            }
        },
        bottomBar = {
            if (selectedTab == 0) {
                MessageComposer(
                    input = input,
                    onInputChange = { input = it.take(2_000) },
                    enabled = connectionState == ConnectionState.CONNECTED,
                    onSend = {
                        onSend(input)
                        input = ""
                    },
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        if (selectedTab == 0) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 12.dp),
                contentPadding = PaddingValues(vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (messages.isEmpty()) {
                    item {
                        Text(
                            "No messages yet. Say hello!",
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 32.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(messages, key = { it.id }) { message ->
                    MessageBubble(message = message, isMine = message.username == username)
                }
            }
        } else {
            FilesScreen(
                files = sharedFiles,
                status = fileTransferStatus,
                onRefresh = onRefreshFiles,
                onUpload = onUploadFile,
                onDownload = onDownloadFile,
                onDelete = onDeleteFile,
                modifier = Modifier.padding(padding),
            )
        }
    }

    if (showPeople) {
        PeopleDialog(onlineUsers = onlineUsers, onDismiss = { showPeople = false })
    }
}

@Composable
private fun ChatHeader(
    connectionState: ConnectionState,
    onlineCount: Int,
    onShowPeople: () -> Unit,
    onReconnect: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.primary,
        shadowElevation = 6.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    "Our chat",
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .background(statusColor(connectionState), CircleShape),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        connectionState.label,
                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.9f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onShowPeople) { Text("$onlineCount online") }
                if (connectionState == ConnectionState.ERROR || connectionState == ConnectionState.DISCONNECTED) {
                    Button(onClick = onReconnect) { Text("Reconnect") }
                }
            }
        }
    }
}

@Composable
private fun PeopleDialog(onlineUsers: List<String>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("People online") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (onlineUsers.isEmpty()) {
                    Text("Nobody is online yet.")
                } else {
                    onlineUsers.take(50).forEach { username ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(9.dp)
                                    .background(Color(0xFF2E7D32), CircleShape),
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(username, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

@Composable
private fun FilesScreen(
    files: List<SharedFile>,
    status: String,
    onRefresh: () -> Unit,
    onUpload: (Uri) -> Unit,
    onDownload: (SharedFile, Uri) -> Unit,
    onDelete: (SharedFile) -> Unit,
    modifier: Modifier = Modifier,
) {
    var pendingDownload by remember { mutableStateOf<SharedFile?>(null) }
    var pendingDelete by remember { mutableStateOf<SharedFile?>(null) }
    val uploadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(onUpload)
    }
    val downloadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val file = pendingDownload
        if (uri != null && file != null) onDownload(file, uri)
        pendingDownload = null
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Button(
                onClick = { uploadLauncher.launch(arrayOf("*/*")) },
                modifier = Modifier.weight(1f),
            ) {
                Text("Upload file")
            }
            Button(onClick = onRefresh) {
                Text("Refresh")
            }
        }

        if (status.isNotBlank()) {
            Text(
                status,
                modifier = Modifier.padding(vertical = 10.dp),
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
            )
        } else {
            Spacer(Modifier.height(12.dp))
        }

        if (files.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Text(
                    "No shared files yet. Upload the first one.",
                    modifier = Modifier.padding(20.dp),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                items(files, key = { it.name }) { file ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(42.dp)
                                        .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text("FILE", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(file.name, fontWeight = FontWeight.Bold)
                                    Text(
                                        "${formatFileSize(file.size)} - ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(file.modifiedAt))}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                Button(
                                    onClick = {
                                        pendingDownload = file
                                        downloadLauncher.launch(file.name)
                                    },
                                ) {
                                    Text("Download")
                                }
                                Spacer(Modifier.width(8.dp))
                                Button(onClick = { pendingDelete = file }) {
                                    Text("Delete")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    pendingDelete?.let { file ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete file?") },
            text = { Text("Delete ${file.name} for everyone? This cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        pendingDelete = null
                        onDelete(file)
                    },
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            },
        )
    }
}

private fun formatFileSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

@Composable
private fun MessageComposer(
    input: String,
    onInputChange: (String) -> Unit,
    enabled: Boolean,
    onSend: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding(),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 12.dp,
        tonalElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = onInputChange,
                modifier = Modifier.weight(1f),
                enabled = enabled,
                maxLines = 4,
                placeholder = { Text(if (enabled) "Write a message..." else "Waiting for connection...") },
                shape = RoundedCornerShape(24.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.background,
                    unfocusedContainerColor = MaterialTheme.colorScheme.background,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                ),
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = onSend,
                enabled = input.isNotBlank() && enabled,
                shape = RoundedCornerShape(20.dp),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 15.dp),
            ) {
                Text("Send", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage, isMine: Boolean) {
    if (message.isSystem) {
        Text(
            text = message.text,
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        return
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start,
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(0.82f),
            shape = RoundedCornerShape(
                topStart = 18.dp,
                topEnd = 18.dp,
                bottomStart = if (isMine) 18.dp else 4.dp,
                bottomEnd = if (isMine) 4.dp else 18.dp,
            ),
            colors = CardDefaults.cardColors(
                containerColor = if (isMine) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 11.dp)) {
                Text(
                    message.username,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelLarge
                )
                Spacer(Modifier.height(2.dp))
                Text(message.text, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(3.dp))
                Text(
                    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(message.timestamp)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun statusColor(state: ConnectionState): Color = when (state) {
    ConnectionState.CONNECTED -> Color(0xFF7CFC98)
    ConnectionState.CONNECTING, ConnectionState.RECONNECTING -> Color(0xFFFFD166)
    ConnectionState.DISCONNECTED, ConnectionState.ERROR -> Color(0xFFFFB4AB)
}

private object ChatProtocol {
    fun encodeJoin(username: String): String = "JOIN|${encode(username)}"

    fun encodeClientMessage(username: String, text: String): String =
        "SEND|${encode(username)}|${encode(text)}"

    fun decodeServerMessage(value: String): ChatMessage? {
        val parts = value.split('|')
        if (parts.size != 5 || parts[0] !in setOf("MESSAGE", "SYSTEM")) return null

        return runCatching {
            ChatMessage(
                id = parts[1],
                timestamp = parts[2].toLong(),
                username = decode(parts[3]),
                text = decode(parts[4]),
                isSystem = parts[0] == "SYSTEM",
            )
        }.getOrNull()
    }

    fun decodePresence(value: String): List<String>? {
        if (!value.startsWith("PRESENCE|")) return null
        val encodedUsers = value.substringAfter('|')
        if (encodedUsers.isBlank()) return emptyList()
        return runCatching { encodedUsers.split(',').map(::decode) }.getOrNull()
    }

    fun decodeFileList(value: String): List<SharedFile> = value
        .lineSequence()
        .filter { it.isNotBlank() }
        .mapNotNull { line ->
            val parts = line.split('|')
            if (parts.size != 3) return@mapNotNull null
            runCatching {
                SharedFile(
                    name = decode(parts[0]),
                    size = parts[1].toLong(),
                    modifiedAt = parts[2].toLong(),
                )
            }.getOrNull()
        }
        .toList()

    private fun encode(value: String): String = Base64.encodeToString(
        value.toByteArray(Charsets.UTF_8),
        Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
    )

    private fun decode(value: String): String = String(
        Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING),
        Charsets.UTF_8,
    )
}
